#!/system/bin/sh
# Pro Zoom chip-patch test via on-disk model swap (run as ROOT).
#
# Google Camera keeps the Pro Zoom denoiser on disk (MDD download). We back it
# up, overwrite its CONTENTS in place with your patched copy (same inode, owner
# and SELinux context; original mtime restored), let the camera load it through
# its own pipeline, capture whether the G4 firmware reads chip type 16 or 255,
# then ALWAYS restore the original bytes.
#
# Reversible: a permanent backup (<file>.ppzorig) is kept and an exit-trap
# restores the original. Needs module 6.1.0-safe installed + enabled. The patched
# copy is /sdcard/Download/ppz_denoiser.bin (made earlier with chippatch apply).
#
# Prereq once (normal Termux, NOT root), only if you haven't already:
#   cd ~ && cp /sdcard/Download/denoiser.bin /sdcard/Download/ppz_denoiser.bin && \
#   python3 chippatch.py apply /sdcard/Download/ppz_denoiser.bin both
# Then: su ; sh /sdcard/Download/diskswap_test.sh

set -u
PKG=com.google.android.GoogleCamera
PATCHED=/sdcard/Download/ppz_denoiser.bin
LOG=/sdcard/Download/ppz_cap.log
SUMMARY=/sdcard/Download/ppz_verdict.txt
EXPECT_SIZE=938529728

say(){ echo "[*] $*"; }
err(){ echo "[!] $*"; }
[ "$(id -u)" = "0" ] || { err "Run as root: type  su  then re-run."; exit 1; }

# --- reach the real /data on KernelSU (isolated-namespace shells) ----------------
if [ ! -d "/data/data/$PKG" ] && [ -z "${PPZ_NSENTERED:-}" ]; then
  SSPID=$(pidof system_server 2>/dev/null | tr ' ' '\n' | head -1)
  [ -n "$SSPID" ] || SSPID=$(pidof zygote64 2>/dev/null | tr ' ' '\n' | head -1)
  NS=""
  if command -v nsenter >/dev/null 2>&1; then NS="nsenter"; elif [ -x /system/bin/nsenter ]; then NS="/system/bin/nsenter"; fi
  if [ -n "$SSPID" ] && [ -n "$NS" ]; then
    say "Entering system_server namespace (pid $SSPID) to reach the real /data..."
    export PPZ_NSENTERED=1
    exec $NS -t "$SSPID" -m -- /system/bin/sh "$0" "$@"
  fi
fi

FILES="/data/data/$PKG/files"
[ -d "$FILES" ] || { err "Camera files dir not visible even in real namespace."; exit 1; }
[ -f "$PATCHED" ] || { err "Patched file missing: $PATCHED"; exit 1; }
PSZ=$(stat -c %s "$PATCHED" 2>/dev/null)
[ "$PSZ" = "$EXPECT_SIZE" ] || { err "Patched file is $PSZ bytes, expected $EXPECT_SIZE. Stopping."; exit 1; }

# --- locate the on-disk denoiser (the 938529728-byte MDD file) ------------------
MDD=$(find "$FILES/datadownload" -type f -size ${EXPECT_SIZE}c 2>/dev/null | head -1)
[ -n "$MDD" ] || { err "On-disk denoiser not found. Open the camera, shoot once at 120x to trigger the MDD download, then re-run."; exit 1; }
BAK="$MDD.ppzorig"
say "On-disk denoiser: $MDD"
say "Patched copy:     $PATCHED  (sha256 $(sha256sum "$PATCHED" 2>/dev/null|cut -d' ' -f1))"

# --- permanent backup of the pristine original ----------------------------------
if [ ! -f "$BAK" ]; then
  cp -a "$MDD" "$BAK" || { err "backup failed"; exit 1; }
  say "Backup saved: $BAK"
else
  # Verify the existing backup is the right size before trusting it.
  [ "$(stat -c %s "$BAK")" = "$EXPECT_SIZE" ] || { err "Existing backup $BAK is wrong size; refusing. Delete it and re-run."; exit 1; }
  say "Backup already present: $BAK"
fi
ORIG_SHA=$(sha256sum "$BAK" 2>/dev/null | cut -d' ' -f1)
CTX=$(stat -c %C "$MDD" 2>/dev/null)
OWN=$(stat -c %u:%g "$MDD" 2>/dev/null)
PERM=$(stat -c %a "$MDD" 2>/dev/null)

# --- restore-on-exit (runs on normal end, Ctrl-C, or kill) ----------------------
LPID=""
restore() {
  [ -n "$LPID" ] && kill "$LPID" 2>/dev/null
  if [ -f "$BAK" ]; then
    cat "$BAK" > "$MDD" 2>/dev/null
    touch -r "$BAK" "$MDD" 2>/dev/null
    [ -n "$CTX" ] && chcon "$CTX" "$MDD" 2>/dev/null
    NOW=$(sha256sum "$MDD" 2>/dev/null | cut -d' ' -f1)
    if [ "$NOW" = "$ORIG_SHA" ]; then echo "[*] Original denoiser restored (sha matches)."; else echo "[!] Restore check: on-disk sha=$NOW expected=$ORIG_SHA — run:  cat \"$BAK\" > \"$MDD\""; fi
  fi
}
trap restore EXIT INT TERM

# --- swap in the patched bytes (keep inode / owner / context; fix mtime) --------
cat "$PATCHED" > "$MDD" || { err "swap write failed"; exit 1; }
touch -r "$BAK" "$MDD" 2>/dev/null
[ -n "$CTX" ]  && chcon "$CTX" "$MDD" 2>/dev/null
[ -n "$OWN" ]  && chown "$OWN" "$MDD" 2>/dev/null
[ -n "$PERM" ] && chmod "$PERM" "$MDD" 2>/dev/null
say "Swapped in patched denoiser (sha now $(sha256sum "$MDD" 2>/dev/null|cut -d' ' -f1))"

# --- re-arm the module so the native guard loads and the model runs -------------
for f in gcam100x_native_off gcam100x_native_starting gcam100x_google_disabled \
         gcam100x_stats gcam100x_model_running gcam100x_google_shot; do
  rm -f "$FILES/$f" 2>/dev/null
done
say "Re-armed native guard + Pro Zoom path."

# --- capture --------------------------------------------------------------------
say "Force-stopping the camera..."
am force-stop "$PKG" 2>/dev/null
sleep 1
logcat -b all -c 2>/dev/null
rm -f "$LOG" 2>/dev/null
logcat -b all -v threadtime > "$LOG" 2>/dev/null &
LPID=$!
echo
echo "==================================================================="
echo "  NOW: open Google Camera, zoom to 120x, take ONE photo, let it"
echo "       finish, then come back here and press ENTER."
echo "  (If the shot fails or the camera closes, that's fine - come back"
echo "   and press ENTER anyway; the answer is already in the log.)"
echo "==================================================================="
echo
read _dummy
sleep 1
kill "$LPID" 2>/dev/null; LPID=""

# --- analyse --------------------------------------------------------------------
H_INIT=$(grep -i "native_init\|GCam100x .* starting in" "$LOG" | head -1)
H_NAT=$(grep -i "Native part loaded\|Native part NOT loaded\|Native part not loaded\|crash guard" "$LOG" | head -1)
H_PZ=$(grep -i "Pro Zoom path on\|Pro Zoom path off\|normal capture\|this module crops" "$LOG" | head -1)
H_RUN=$(grep -i "START native model run\|native model run (shot" "$LOG" | head -1)
H_CHIP=$(grep -i "Expected chip type\|chip type .* got\|got 255\|got 16" "$LOG" | head -1)
H_REG=$(grep -i "RegisterGraph\|registration.cc" "$LOG" | head -1)
H_VERIFY=$(grep -i "checksum\|hash mismatch\|corrupt\|verifyChecksum\|re-download\|redownload\|integrity" "$LOG" | head -1)
H_CRASH=$(grep -i "Fatal signal\|SIGSEGV\|super_resolution\|Failed to allocate tensors" "$LOG" | head -1)

{
echo "=== Pro Zoom chip-patch test (disk swap) verdict ==="
echo "time: $(date)"
echo "denoiser file: $MDD"
echo
echo "1 module init:   ${H_INIT:-<none>}"
echo "2 native guard:  ${H_NAT:-<none>}"
echo "3 Pro Zoom path: ${H_PZ:-<none>}"
echo "4 model run:     ${H_RUN:-<none>}"
echo "5 chip type:     ${H_CHIP:-<none>}"
echo "  register:      ${H_REG:-<none>}"
echo "  MDD verify:    ${H_VERIFY:-<none>}"
echo "  crash:         ${H_CRASH:-<none>}"
echo
echo "--- VERDICT ---"
if [ -z "$H_RUN" ]; then
  echo "MODEL DID NOT RUN this shot (took crop path)."
  echo "  native: ${H_NAT:-<none>} | pro zoom: ${H_PZ:-<none>}"
  echo "  If MDD verify line is set, Google re-checked the file hash and rejected the"
  echo "  patched bytes before loading: $H_VERIFY"
  echo "  Otherwise the native guard didn't load - re-run (it re-arms + force-stops)."
elif echo "$H_CHIP" | grep -qi "got 16"; then
  echo "YES - patched denoiser ACCEPTED: firmware read chip type 16 (rio/G4)."
  echo "      The patched G5 executable registered on G4. Inference stage is next."
  [ -n "$H_CRASH" ] && echo "      (a later failure followed - see crash line)"
elif echo "$H_CHIP" | grep -qi "got 255"; then
  echo "NO - the camera loaded the patched bytes but the firmware STILL read 255."
  echo "     Chip identity is not a single-field flip; rejection is deeper in the"
  echo "     container/version encoding. Definitive negative for variant 'both'."
elif [ -n "$H_CHIP" ]; then
  echo "Model ran; chip line present but unclear: $H_CHIP"
  echo "  Inspect: grep -iE 'chip|register|edgetpu|darwinn|tpu' $LOG | head -60"
else
  echo "Model ran but no chip-type line was logged. Inspect:"
  echo "  grep -iE 'chip|register|edgetpu|darwinn|tpu|tflite' $LOG | head -80"
fi
echo
echo "full log: $LOG"
} | tee "$SUMMARY"

# restore() runs now via the EXIT trap
echo
say "Restoring the original denoiser..."
