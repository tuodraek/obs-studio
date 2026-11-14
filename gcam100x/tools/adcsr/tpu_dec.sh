#!/data/data/com.termux/files/usr/bin/sh
# First real TPU timing: the AdcSR decoder compiled and run on the Tensor G4 TPU as root.
# Run in normal Termux:  sh /sdcard/Download/tpu_dec.sh     (asks for root itself)
# Needs adcsr_dec_w8a16.tflite and adcsr_dec_fp16.tflite in /sdcard/Download (already there
# from the bench app) and ~/adcsr/bundle from the earlier benchmark. Changes no system setting.
# Result: /sdcard/Download/adcsr_tpu_dec.txt
set -u
H=/data/data/com.termux/files/home/adcsr
OUT=/sdcard/Download/adcsr_tpu_dec.txt
[ -x "$H/bundle/lrbench" ] || { echo "[!] $H/bundle/lrbench missing (run the earlier tpu_probe.sh once, or put lrbench_bundle.tar.gz in Download)"; exit 1; }
cat > "$H/dec_inner.sh" <<INNER
H=$H; W=/data/local/tmp/adcsr; D=/data/media/0/Download
rm -rf \$W; mkdir -p \$W && cp \$H/bundle/* \$W/ && chmod 755 \$W/lrbench && cd \$W || { echo "[!] cannot set up \$W"; exit 0; }
export LD_LIBRARY_PATH=\$W:/vendor/lib64:/vendor/lib64/egl:/system/lib64
echo "AdcSR decoder on the TPU (root)  \$(date)   \$(getprop ro.product.model) / \$(getprop ro.soc.model)"
echo "allow_unlisted_app property: [\$(getprop vendor.edgetpu.service.allow_unlisted_app)]"
usage(){ cat /sys/devices/platform/1a000000.rio/tpu_usage 2>/dev/null | tr '\n' ' ' | cut -c1-300; }
one(){ # \$1 label, \$2 model file
  echo; echo "##### \$1 #####"
  [ -f \$D/\$2 ] || { echo "missing \$D/\$2"; return; }
  cp \$D/\$2 \$W/m.tflite
  echo "tpu_usage before: \$(usage)"
  logcat -c 2>/dev/null; t0=\$(date +%s)
  timeout 1500 ./lrbench \$W/m.tflite npu 10 \$W > \$W/run.txt 2>&1; rc=\$?
  echo "exit code \$rc after \$(( \$(date +%s) - t0 )) s   (0 = ran, 124 = timed out after 25 min, 139 = crashed)"
  echo "tpu_usage after:  \$(usage)"
  grep -E "RESULT|run [0-9]+:|FAILED|input |Partitioned|Compil|compil|error|Error|fail" \$W/run.txt | cut -c1-400 | tail -40
  echo "--- logcat"
  logcat -d 2>/dev/null | grep -i -E "darwinn|edgetpu" | grep -i -E "error|fail|not supported|unsupported|chip|compil|regist|allow|fatal" | cut -c1-300 | tail -15
}
one "decoder, quantized W8A16" adcsr_dec_w8a16.tflite
one "decoder, float" adcsr_dec_fp16.tflite
rm -rf \$W
exit 0
INNER
su -c "nsenter -t 1 -m -- /system/bin/sh $H/dec_inner.sh 2>&1 || /system/bin/sh $H/dec_inner.sh 2>&1" | tee "$OUT"
echo; echo "[*] Saved: $OUT — send me that file."
