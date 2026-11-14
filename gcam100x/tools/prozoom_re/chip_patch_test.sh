#!/system/bin/sh
# Pro Zoom G4 chip-patch registration test (reversible). Run as ROOT in a shell:
#   su           (then)   sh /sdcard/Download/chip_patch_test.sh
# Requires: chippatch.py + python3 in the same dir (push both to /sdcard/Download).
# It patches ONLY chip-identity metadata in the on-device denoiser, in place, and
# ALWAYS restores the original bytes. The original model is never permanently changed.
set -u
DIR=$(dirname "$0")
PY=$(command -v python3 || echo /data/data/com.termux/files/usr/bin/python3)
PKG=com.google.android.GoogleCamera
WORK=/data/local/tmp/ppz; mkdir -p "$WORK"
JOURNAL="$WORK/journal.json"; REF="$WORK/mtime.ref"; LOG="$WORK/cap.log"
GUARD=/data/user/0/$PKG/files/gcam100x_google_disabled
STATS=/data/user/0/$PKG/files/gcam100x_stats

echo "[*] locating denoiser model file (~938 MB, DGC0+buenos_b0)..."
MODEL=""
for f in $(find /data/user/0/$PKG /data/data/$PKG -type f -size +800M 2>/dev/null); do
  if "$PY" "$DIR/chippatch.py" verify "$f" >/dev/null 2>&1; then MODEL="$f"; break; fi
done
[ -z "$MODEL" ] && { echo "[!] denoiser not found. Open the camera once at 120x so MDD downloads it, then retry."; exit 1; }
echo "[*] model: $MODEL"
"$PY" "$DIR/chippatch.py" save "$MODEL" "$JOURNAL" || exit 1
touch -r "$MODEL" "$REF"

restore() { "$PY" "$DIR/chippatch.py" restore "$MODEL" "$JOURNAL" >/dev/null 2>&1; touch -r "$REF" "$MODEL" 2>/dev/null; }
trap 'echo; echo "[*] restoring original..."; restore; echo "[*] done."' EXIT INT TERM

run_variant() {
  V="$1"
  echo; echo "==================== VARIANT: $V ===================="
  am force-stop $PKG 2>/dev/null; sleep 1
  rm -f "$GUARD" "$STATS" 2>/dev/null                 # re-arm module's Pro Zoom path
  restore                                             # start from pristine
  "$PY" "$DIR/chippatch.py" apply "$MODEL" "$V" || return
  touch -r "$REF" "$MODEL"
  : > "$LOG"; logcat -c 2>/dev/null
  logcat -b all -v threadtime -f "$LOG" '*:V' >/dev/null 2>&1 &
  LPID=$!
  echo "[>] NOW: open Camera, zoom to 120x, take ONE photo, wait ~5s for it to finish/close."
  printf "[>] then press ENTER here... "; read _
  sleep 1; kill "$LPID" 2>/dev/null
  echo "---- key lines (variant $V) ----"
  grep -aE "Expected chip type|RegisterGraphRequest|Graph registration|failed to prepare|Failed to allocate tensors|Centaur|chip type|registration.cc|Registration setup|Running model|INF .*cluster" "$LOG" | tail -20
  cp "$LOG" "$WORK/cap_$V.log"
  echo "---- (full log saved: $WORK/cap_$V.log) ----"
}

for V in wrapper string both; do run_variant "$V"; done
echo
echo "[*] ALL VARIANTS DONE. Logs: $WORK/cap_wrapper.log cap_string.log cap_both.log"
echo "[*] Original model restored. Pull the logs:"
echo "    cp $WORK/cap_*.log /sdcard/Download/"
