#!/data/data/com.termux/files/usr/bin/sh
# Tensor G4 TPU probe for the AdcSR engine. Run in normal Termux:  sh /sdcard/Download/tpu_probe.sh
# Read-only inspection + three inference attempts; changes no system setting.
# Needs in /sdcard/Download: adcsr_128_w8a16.tflite.part00 + .part01 (joined here), and the
# files left in ~/adcsr by the earlier benchmarks. Result: /sdcard/Download/adcsr_tpu.txt
set -u
H=/data/data/com.termux/files/home/adcsr
D=/sdcard/Download
Q=$H/adcsr_128_w8a16.tflite
F=$H/adcsr_bench_fp16.tflite
OUT=$D/adcsr_tpu.txt
QSHA=66701eb97823b8f96d54651070ef49ebc7b2be9df3ba971f562ffde77a75869c
mkdir -p "$H"
if [ ! -f "$Q" ] || [ "$(sha256sum "$Q" | cut -d' ' -f1)" != "$QSHA" ]; then
  [ -f $D/adcsr_128_w8a16.tflite.part00 ] && [ -f $D/adcsr_128_w8a16.tflite.part01 ] || { echo "[!] Put adcsr_128_w8a16.tflite.part00 and .part01 in Download first."; exit 1; }
  echo "[*] Joining the quantized model (463 MB)..."; cat $D/adcsr_128_w8a16.tflite.part00 $D/adcsr_128_w8a16.tflite.part01 > "$Q"
  [ "$(sha256sum "$Q" | cut -d' ' -f1)" = "$QSHA" ] || { echo "[!] model hash mismatch — re-download the parts"; exit 1; }
fi
[ -x "$H/bundle/lrbench" ] || { echo "[!] $H/bundle missing — put lrbench_bundle.tar.gz in Download and re-run"; [ -f $D/lrbench_bundle.tar.gz ] && mkdir -p $H/bundle && tar xzf $D/lrbench_bundle.tar.gz -C $H/bundle || exit 1; }

{
echo "AdcSR TPU probe  $(date)   $(getprop ro.product.model) / $(getprop ro.soc.model) / $(getprop ro.build.id)"
echo; echo "##### 1. NNAPI -> google-edgetpu, quantized model (no root) #####"
if [ -x "$H/benchmark_model" ]; then
  timeout 600 "$H/benchmark_model" --graph="$Q" --use_nnapi=true --nnapi_accelerator_name=google-edgetpu --disable_nnapi_cpu=true --num_runs=3 --warmup_runs=1 --min_secs=0 2>&1 | grep -v "^$" | grep -i -E "nnapi|delegate|replac|partition|not supported|unsupported|error|fail|Init|Inference|memory|accelerator" | cut -c1-300 | head -40
  echo "exit: $?"
else echo "benchmark_model not in $H (skipped)"; fi
} 2>&1 | tee "$OUT"

cat > "$H/tpu_inner.sh" <<INNER
H=$H; Q=$Q; F=$F; W=/data/local/tmp/adcsr
echo; echo "##### 2. device inspection (root, read-only) #####"
echo "--- NNAPI / edgetpu services"; service list 2>/dev/null | grep -i -E "neural|edgetpu"
echo "--- TPU libraries"; ls -l /vendor/lib64/*edgetpu* /vendor/lib64/*darwinn* 2>/dev/null
echo "--- public vendor libraries"; grep -i -E "edgetpu|OpenCL" /vendor/etc/public.libraries.txt 2>/dev/null
echo "--- allowlist property: [\$(getprop vendor.edgetpu.service.allow_unlisted_app)]"; getprop | grep -i edgetpu
echo "--- TPU sysfs"; ls /sys/devices/platform/ 2>/dev/null | grep -i -E "tpu|edgetpu"; find /sys/devices/platform -maxdepth 3 -iname "*inference*" -o -maxdepth 3 -iname "*usage*" 2>/dev/null | grep -i -E "tpu|edgetpu" | head
rm -rf \$W; mkdir -p \$W && cp \$H/bundle/* \$W/ && chmod 755 \$W/lrbench && cd \$W || { echo "[!] cannot set up \$W"; exit 0; }
export LD_LIBRARY_PATH=\$W:/vendor/lib64:/vendor/lib64/egl:/system/lib64
tpu(){ cat /sys/class/thermal/thermal_zone*/type 2>/dev/null | grep -n -x TPU | head -1 | cut -d: -f1; }
one(){ # \$1 label, \$2 model
  echo; echo "##### \$1 #####"
  logcat -c 2>/dev/null
  timeout 900 ./lrbench \$2 npu 4 \$W > \$W/run.txt 2>&1; rc=\$?
  echo "exit code \$rc (124 = timed out, 139 = crashed)"
  grep -v -E "Attempting to load GPU|RegisterAccelerator|XNNPACK CPU accelerator registered" \$W/run.txt | cut -c1-500 | tail -60
  echo "--- logcat (edgetpu / darwinn / litert)"
  logcat -d 2>/dev/null | grep -i -E "darwinn|edgetpu|litert|tflite" | grep -i -E "error|fail|warn|not supported|unsupported|chip|compil|regist|allow|denied|fatal|signal" | cut -c1-400 | tail -40
}
one "3. LiteRT NPU (Google Tensor plugin), quantized model, root" \$Q
[ -f \$F ] && one "4. LiteRT NPU (Google Tensor plugin), float model, root" \$F
rm -rf \$W
exit 0
INNER
su -c "nsenter -t 1 -m -- /system/bin/sh $H/tpu_inner.sh 2>&1 || /system/bin/sh $H/tpu_inner.sh 2>&1" | tee -a "$OUT"
echo; echo "[*] Saved: $OUT — send me that file."
