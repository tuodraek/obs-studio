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

# --- KernelSU / isolated-namespace handling -------------------------------------
# On KernelSU the root shell often runs in its own mount namespace where
# /data/data shows almost nothing. The camera and system_server live in the real
# namespace. If we can't see the camera dir, re-exec this script inside
# system_server's mount namespace (still uid 0) so /data/data is the real one.
if [ ! -d "/data/data/$PKG" ] && [ -z "${PPZ_NSENTERED:-}" ]; then
  SSPID=$(pidof system_server 2>/dev/null | tr ' ' '\n' | head -1)
  [ -n "$SSPID" ] || SSPID=$(pidof zygote64 2>/dev/null | tr ' ' '\n' | head -1)
  [ -n "$SSPID" ] || SSPID=$(pidof zygote 2>/dev/null | tr ' ' '\n' | head -1)
  NS=""
  if command -v nsenter >/dev/null 2>&1; then NS="nsenter";
  elif [ -x /system/bin/nsenter ]; then NS="/system/bin/nsenter";
  elif command -v toybox >/dev/null 2>&1 && toybox nsenter --help >/dev/null 2>&1; then NS="toybox nsenter"; fi
  if [ -n "$SSPID" ] && [ -n "$NS" ]; then
    say "Camera dir not visible here (KernelSU isolated namespace)."
    say "Re-entering system_server's namespace (pid $SSPID) to reach the real /data..."
    export PPZ_NSENTERED=1
    exec $NS -t "$SSPID" -m -- /system/bin/sh "$0" "$@"
  else
    err "Cannot see /data/data/$PKG and nsenter/system_server unavailable."
    err "SSPID='$SSPID' nsenter='$NS'. Paste this line so I can adjust."
    exit 1
  fi
fi

# --- locate the camera data dir -------------------------------------------------
CAMDIR=/data/data/$PKG
[ -d "$CAMDIR" ] || CAMDIR=/data/user/0/$PKG
[ -d "$CAMDIR" ] || { err "Camera data dir not found ($PKG not installed?) even in real namespace."; exit 1; }
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
# Force the file's SELinux context to match the camera's files/ dir exactly
# (per-app app_data_file categories), so the camera process can read it.
DIRCTX=$(stat -c %C "$FILES" 2>/dev/null)
if [ -n "$DIRCTX" ] && [ "$DIRCTX" != "?" ]; then
  chcon "$DIRCTX" "$DST" 2>/dev/null && say "Context set to $DIRCTX"
fi
say "Staged -> $DST"
ls -lZ "$DST" 2>/dev/null || ls -l "$DST"

# --- re-arm the module so the native part loads AND Pro Zoom runs the model -----
# These are the exact files Native.retry() and Guard.retry() delete. Without the
# first two the native part stays disabled (its openat hooks never install) and
# the module falls back to crop, so the denoiser is never opened.
for f in gcam100x_native_off gcam100x_native_starting \
         gcam100x_google_disabled gcam100x_stats \
         gcam100x_model_running gcam100x_google_shot; do
  rm -f "$FILES/$f" 2>/dev/null
done
say "Re-armed: native part + Pro Zoom path (cleared off/disabled latches)."
LEFT=$(ls "$FILES" 2>/dev/null | grep -i "gcam100x_native_off\|gcam100x_google_disabled" )
[ -z "$LEFT" ] && say "Confirmed: no disable latch remains." || err "Latch still present: $LEFT"

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
HIT_INIT=$(grep -i "native_init\|GCam100x .* starting in" "$LOG" | head -1)
HIT_NATLOAD=$(grep -i "Native part loaded\|Native part NOT loaded\|Native part not loaded" "$LOG" | head -1)
HIT_INSTALL=$(grep -i "installed .*/3 open hooks\|staged file present" "$LOG" | head -1)
HIT_NOFILE=$(grep -i "no staged file" "$LOG" | head -1)
HIT_PZON=$(grep -i "Pro Zoom path on\|Google's Pro Zoom path on" "$LOG" | head -1)
HIT_PZOFF=$(grep -i "Pro Zoom path off\|switched off\|normal HDR+\|this module crops\|normal capture" "$LOG" | head -1)
HIT_MODELRUN=$(grep -i "START native model run\|native model run (shot" "$LOG" | head -1)
HIT_SWAP=$(grep -i "Denoiser substitution: swapped" "$LOG" | head -1)
HIT_CHIP=$(grep -i "Expected chip type\|chip type .* got\|got 255\|got 16" "$LOG" | head -1)
HIT_REG=$(grep -i "RegisterGraph\|registration.cc\|register.*graph" "$LOG" | head -1)
HIT_GUARD=$(grep -i "Guard: ModelRunner" "$LOG" | head -1)
HIT_CRASH=$(grep -i "Fatal signal\|SIGSEGV\|super_resolution\|Failed to allocate tensors" "$LOG" | head -1)

{
echo "=== Pro Zoom chip-patch test verdict ==="
echo "time: $(date)"
echo "staged file: $DST  (sha256 $(sha256sum "$DST" 2>/dev/null|cut -d' ' -f1))"
echo
echo "--- chain (each step must happen for the test to be valid) ---"
echo "1 module init:     ${HIT_INIT:-<none>}"
echo "2 native loaded:   ${HIT_NATLOAD:-<none>}"
echo "3 hooks installed: ${HIT_INSTALL:-<none>}"
echo "  (no-file msg):   ${HIT_NOFILE:-<none>}"
echo "4 Pro Zoom path:   ${HIT_PZON:-${HIT_PZOFF:-<none>}}"
echo "5 model run start: ${HIT_MODELRUN:-<none>}"
echo "6 SUBSTITUTED:     ${HIT_SWAP:-<none>}"
echo
echo "--- firmware / registration ---"
echo "chip type:   ${HIT_CHIP:-<none>}"
echo "register:    ${HIT_REG:-<none>}"
echo "guard:       ${HIT_GUARD:-<none>}"
echo "crash:       ${HIT_CRASH:-<none>}"
echo
echo "--- VERDICT ---"
if echo "$HIT_NATLOAD" | grep -qi "NOT loaded\|not loaded"; then
  echo "BLOCKED BEFORE THE TEST — native part did not load this run."
  echo "  reason: $HIT_NATLOAD"
  echo "  The disable latch was not cleared, or the camera re-latched it. Re-run this"
  echo "  script (it clears gcam100x_native_off + gcam100x_native_starting and"
  echo "  force-stops first). If it persists, open the module's Settings and tap Retry,"
  echo "  then immediately re-run."
elif [ -z "$HIT_MODELRUN" ]; then
  echo "BLOCKED BEFORE THE TEST — Google's model never ran (shot took the crop path)."
  echo "  Pro Zoom line: ${HIT_PZON:-${HIT_PZOFF:-<none>}}"
  echo "  The denoiser is only opened when the model runs, so substitution could not"
  echo "  fire. Make sure the native part is active (step 2 above says 'loaded' with the"
  echo "  crash guard ACTIVE) and the shot is a real Pro Zoom capture at 120x, then re-run."
elif [ -z "$HIT_SWAP" ]; then
  echo "MODEL RAN, but SUBSTITUTION DID NOT FIRE."
  if [ -n "$HIT_NOFILE" ]; then
    echo "  -> native part saw no staged file at init (perms/context). Re-run."
  else
    echo "  -> the camera did not open a $EXPECT_SIZE-byte file during the run. The"
    echo "     denoiser is delivered to the TPU some other way (memfd / streamed / a"
    echo "     different size on disk). The file-substitution route cannot inject here;"
    echo "     the MDD-manifest route is next."
  fi
  if [ -n "$HIT_CHIP" ]; then echo "  (ran on the ORIGINAL model; chip line: $HIT_CHIP)"; fi
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
