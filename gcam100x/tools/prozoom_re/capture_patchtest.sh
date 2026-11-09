#!/system/bin/sh
# Pro Zoom chip-patch interoperability test — capture harness (run as ROOT).
#
# What it does (all reversible, nothing in firmware or the original model is touched):
#   1. Stages the ALREADY-PATCHED denoiser (made in step 1, on /sdcard) into the
#      camera's own data dir with the camera's uid + SELinux context, so the
#      camera process can read it. The module's native part (v6.0.0-patchtest)
#      then substitutes it for the real denoiser at the moment the camera opens a
#      938,529,728-byte file — i.e. the patched graph goes through the camera's
#      OWN registration path, after MDD's hash check, so MDD cannot reject it.
#   2. Captures a full system log across one 120x+ Pro Zoom shot.
#   3. Greps the log for the decisive lines and prints a YES/NO verdict.
#
# Prereq (do this FIRST, in normal Termux — NOT root):
#   cd ~ && cp /sdcard/Download/denoiser.bin /sdcard/Download/ppz_denoiser.bin && \
#   python3 chippatch.py verify /sdcard/Download/ppz_denoiser.bin && \
#   python3 chippatch.py apply  /sdcard/Download/ppz_denoiser.bin both && \
#   sha256sum /sdcard/Download/ppz_denoiser.bin
# Then: su  ;  sh /sdcard/Download/capture_patchtest.sh

set -u
PKG=com.google.android.GoogleCamera
SRC=/sdcard/Download/ppz_denoiser.bin
LOG=/sdcard/Download/ppz_cap.log
SUMMARY=/sdcard/Download/ppz_verdict.txt
EXPECT_SIZE=938529728

say(){ echo "[*] $*"; }
err(){ echo "[!] $*"; }

[ "$(id -u)" = "0" ] || { err "Run this as root (type: su   then re-run)."; exit 1; }

# --- locate the camera data dir -------------------------------------------------
CAMDIR=/data/user/0/$PKG
[ -d "$CAMDIR" ] || CAMDIR=/data/data/$PKG
[ -d "$CAMDIR" ] || { err "Camera data dir not found ($PKG not installed?)."; exit 1; }
FILES="$CAMDIR/files"
mkdir -p "$FILES" 2>/dev/null
DST="$FILES/ppz_denoiser.bin"

# --- check the patched source ---------------------------------------------------
[ -f "$SRC" ] || { err "Patched file missing: $SRC  (do the Termux prep step first)."; exit 1; }
SZ=$(stat -c %s "$SRC" 2>/dev/null || wc -c < "$SRC")
say "Patched source: $SRC  size=$SZ"
if [ "$SZ" != "$EXPECT_SIZE" ]; then
  err "Patched file is $SZ bytes, expected $EXPECT_SIZE. The chip patch is offset-preserving;"
  err "a different size means the wrong file or a bad patch. Stopping."
  exit 1
fi
say "Patched sha256: $(sha256sum "$SRC" 2>/dev/null | cut -d' ' -f1)"

# --- stage into the camera's own data dir (SELinux-correct) ---------------------
UID_CAM=$(stat -c %u "$CAMDIR" 2>/dev/null)
GID_CAM=$(stat -c %g "$CAMDIR" 2>/dev/null)
say "Camera uid:gid = $UID_CAM:$GID_CAM   dir=$CAMDIR"
cp -f "$SRC" "$DST" || { err "copy to $DST failed"; exit 1; }
chown "$UID_CAM:$GID_CAM" "$DST" 2>/dev/null
chmod 0640 "$DST" 2>/dev/null
restorecon "$DST" 2>/dev/null
# make sure the files dir itself is traversable by the app
chown "$UID_CAM:$GID_CAM" "$FILES" 2>/dev/null; restorecon "$FILES" 2>/dev/null
say "Staged -> $DST"
ls -lZ "$DST" 2>/dev/null || ls -l "$DST"

# --- clear any module guard flags so the native part is fully active ------------
for f in gcam100x_google_disabled gcam100x_stats gcam100x_exit_seen gcam100x_disabled; do
  rm -f "$FILES/$f" 2>/dev/null
done

# --- fresh capture --------------------------------------------------------------
say "Force-stopping the camera so the module re-initialises with the staged file..."
am force-stop "$PKG" 2>/dev/null
sleep 1
logcat -b all -c 2>/dev/null
rm -f "$LOG" 2>/dev/null
logcat -b all -v threadtime > "$LOG" 2>/dev/null &
LPID=$!
say "Logging to $LOG (pid $LPID)."
echo
echo "==================================================================="
echo "  NOW:  open Google Camera, zoom to 120x (or more), take ONE photo,"
echo "        wait for it to finish processing, then come back here and"
echo "        press ENTER."
echo "==================================================================="
echo
read _dummy
sleep 1
kill "$LPID" 2>/dev/null

# --- analyse --------------------------------------------------------------------
echo
say "===== SIGNAL SCAN ====="
scan(){ n=$(grep -c -i "$1" "$LOG" 2>/dev/null); echo "  [$n] $2"; }
HIT_INSTALL=$(grep -i "installed .*/3 open hooks" "$LOG")
HIT_SWAP=$(grep -i "Denoiser substitution: swapped" "$LOG")
HIT_NOFILE=$(grep -i "no staged file" "$LOG")
HIT_CHIP=$(grep -i "Expected chip type\|chip type .* got\|got 255\|got 16" "$LOG")
HIT_REG=$(grep -i "RegisterGraph\|registration.cc\|register.*graph" "$LOG")
HIT_GUARD=$(grep -i "Guard: ModelRunner" "$LOG")
HIT_CRASH=$(grep -i "Fatal signal\|SIGSEGV\|super_resolution\|Failed to allocate tensors" "$LOG")
HIT_INIT=$(grep -i "native_init\|GCam100x .* starting" "$LOG")

{
echo "=== Pro Zoom chip-patch test verdict ==="
echo "time: $(date)"
echo "staged file: $DST  (sha256 $(sha256sum "$DST" 2>/dev/null|cut -d' ' -f1))"
echo
echo "--- native part ---"
echo "init:        ${HIT_INIT:-<none>}"
echo "hook install:${HIT_INSTALL:-<none>}"
echo "no-file msg: ${HIT_NOFILE:-<none>}"
echo "SUBSTITUTED: ${HIT_SWAP:-<none>}"
echo
echo "--- firmware / registration ---"
echo "chip type:   ${HIT_CHIP:-<none>}"
echo "register:    ${HIT_REG:-<none>}"
echo "guard:       ${HIT_GUARD:-<none>}"
echo "crash:       ${HIT_CRASH:-<none>}"
echo
echo "--- VERDICT ---"
if [ -z "$HIT_SWAP" ]; then
  echo "SUBSTITUTION DID NOT FIRE."
  if [ -n "$HIT_NOFILE" ]; then
    echo "  -> native part saw no staged file at init. The file was staged AFTER the"
    echo "     process started, or perms/context blocked the read. Re-run (it force-stops"
    echo "     first); if it persists, the camera uid cannot read $DST."
  else
    echo "  -> the camera did not open a $EXPECT_SIZE-byte file. The denoiser is not"
    echo "     delivered to the TPU as a plain on-disk file (memfd / streamed). The"
    echo "     file-substitution route cannot inject here; the MDD-manifest route is next."
  fi
  if [ -n "$HIT_CHIP" ]; then echo "  (pipeline still ran on the ORIGINAL model; chip line: $HIT_CHIP)"; fi
elif echo "$HIT_CHIP" | grep -qi "got 16"; then
  echo "YES — patched denoiser ACCEPTED: firmware read chip type 16 (rio/G4)."
  echo "      Registration of the patched G5 executable proceeded on G4."
  [ -n "$HIT_CRASH" ] && echo "      (but a later failure occurred — see crash line; inference stage next)"
elif echo "$HIT_CHIP" | grep -qi "got 255"; then
  echo "NO — patched denoiser was FED to the camera but the firmware STILL read 255."
  echo "     The chip identity is not a single-field flip; the rejection is deeper in"
  echo "     the container/version encoding. This is a definitive negative for variant 'both'."
else
  echo "SUBSTITUTION FIRED but no clear chip-type line was logged. Inspect $LOG:"
  echo "  grep -iE 'chip|register|edgetpu|tensor|darwinn|tpu' $LOG | head -60"
fi
echo
echo "full log: $LOG"
} | tee "$SUMMARY"

echo
say "===== ROLLBACK (nothing permanent was changed) ====="
echo "  Original model:   /sdcard/Download/denoiser.bin  — untouched."
echo "  Staged test copy: $DST"
echo "  To remove it:     rm -f \"$DST\""
echo "  The module only substitutes when that file exists; delete it (or disable the"
echo "  module in LSPosed) and the camera is exactly as before. Firmware: never touched."
