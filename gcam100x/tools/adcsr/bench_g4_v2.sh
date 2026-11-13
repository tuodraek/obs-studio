#!/data/data/com.termux/files/usr/bin/sh
# AdcSR on Tensor G4 — benchmark v2: current LiteRT 2.3.2 runtime (ML Drift GPU + Tensor NPU).
# Run in normal Termux:   sh /sdcard/Download/bench_g4_v2.sh      (it asks for root itself)
# Needs /sdcard/Download/lrbench_bundle.tar.gz and the model unpacked by the first
# benchmark (~/adcsr/adcsr_bench_fp16.tflite). Touches nothing in the camera/module.
# Result: /sdcard/Download/adcsr_bench2.txt
set -u
H=/data/data/com.termux/files/home/adcsr
M=$H/adcsr_bench_fp16.tflite
T=/sdcard/Download/lrbench_bundle.tar.gz
OUT=/sdcard/Download/adcsr_bench2.txt
W=/data/local/tmp/adcsr
[ -f "$M" ] || { echo "[!] $M missing — run the first bench_g4.sh once (it unpacks the model)."; exit 1; }
[ -f "$T" ] || { echo "[!] Put lrbench_bundle.tar.gz in /sdcard/Download first."; exit 1; }
[ "$(sha256sum "$T" | cut -d' ' -f1)" = "cda4f7d0cb49c490c8313aa3e7a8b73a9a039e80a81c386d4772d86d023b1889" ] || { echo "[!] bundle hash mismatch (re-download it)"; exit 1; }
rm -rf "$H/bundle"; mkdir -p "$H/bundle" && tar xzf "$T" -C "$H/bundle" || { echo "[!] unpack failed"; exit 1; }

cat > "$H/inner.sh" <<INNER
W=$W; M=$M; H=$H
rm -rf \$W; mkdir -p \$W && cp \$H/bundle/* \$W/ && chmod 755 \$W/lrbench && cd \$W || { echo "[!] cannot set up \$W"; exit 0; }
export LD_LIBRARY_PATH=\$W:/vendor/lib64:/vendor/lib64/egl:/system/lib64
temp(){ for z in /sys/class/thermal/thermal_zone*/; do t=\$(cat \$z/type 2>/dev/null); case "\$t" in *skin*|BIG|LITTLE|MID|G3D|TPU) echo "\$t=\$(( \$(cat \$z/temp 2>/dev/null) / 1000 ))C";; esac; done | tr '\n' ' '; }
one(){ # \$1 accelerator, \$2 runs
  echo; echo "===== \$1 ====="; echo "temps before: \$(temp)"
  logcat -c 2>/dev/null
  ./lrbench \$M \$1 \$2 \$W > \$W/run.txt 2>&1; rc=\$?
  grep -E "RESULT|run [0-9]+:|FAILED|input " \$W/run.txt
  echo "exit code \$rc; temps after: \$(temp)"
  echo "--- runtime messages ---"
  grep -v -E "RESULT|run [0-9]+:|input " \$W/run.txt | grep -i -E "error|fail|warn|not supported|unsupported|fallback|delegat|accelerat|opencl|partition|compil|npu|dispatch|fully" | cut -c1-400 | head -40
  logcat -d 2>/dev/null | grep -i -E "litert|tflite|ml_drift|opencl|darwinn|edgetpu" | grep -i -E "error|fail|warn|not supported|unsupported|fallback|replac|partition|compil|chip|delegate" | cut -c1-400 | tail -40
}
echo "AdcSR G4 benchmark v2 (LiteRT 2.3.2)  \$(date)"
echo "device: \$(getprop ro.product.model) / \$(getprop ro.soc.model) / Android \$(getprop ro.build.version.release) / \$(getprop ro.build.id)"
one gpu 8
one npu 4
rm -rf \$W
exit 0
INNER

# KernelSU root shells can sit in an isolated mount namespace: enter init's.
su -c "nsenter -t 1 -m -- /system/bin/sh $H/inner.sh 2>&1 || /system/bin/sh $H/inner.sh 2>&1" | tee "$OUT"
echo; echo "[*] Saved: $OUT — send me that file."
