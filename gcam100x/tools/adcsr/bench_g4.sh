#!/data/data/com.termux/files/usr/bin/sh
# AdcSR on Tensor G4 — Phase 3 gate: real GPU latency + memory for one 128->512 tile.
# Run in normal Termux (NOT root):   sh /sdcard/Download/bench_g4.sh
# Needs /sdcard/Download/adcsr_bench_fp16.tflite.xz (the exact AdcSR graph with
# placeholder weights: timing/memory are the same, output is not a real image).
# Touches nothing in the camera or the module. Result: /sdcard/Download/adcsr_bench.txt
set -u
D=$HOME/adcsr; mkdir -p "$D"; cd "$D" || exit 1
OUT=/sdcard/Download/adcsr_bench.txt
XZ=/sdcard/Download/adcsr_bench_fp16.tflite.xz
M=$D/adcsr_bench_fp16.tflite
B=$D/benchmark_model
URL=https://storage.googleapis.com/tensorflow-nightly-public/prod/tensorflow/release/lite/tools/nightly/latest/android_aarch64_benchmark_model
BSHA=5d45116eb86e3a23ea03318e93bfc8efb4dd0149d4692c127e7b0343faba1374
MSHA=78035dbb3808c9c755208ecd4cdca02d0251f9c0a306aac51e384c5b497b2f0a

if [ ! -f "$M" ] || [ "$(sha256sum "$M" | cut -d' ' -f1)" != "$MSHA" ]; then
  [ -f "$XZ" ] || { echo "[!] Put adcsr_bench_fp16.tflite.xz in /sdcard/Download first."; exit 1; }
  echo "[*] Unpacking model (915 MB)..."; xz -dc "$XZ" > "$M" || { echo "[!] unpack failed (pkg install xz-utils)"; exit 1; }
  [ "$(sha256sum "$M" | cut -d' ' -f1)" = "$MSHA" ] || { echo "[!] model hash mismatch"; exit 1; }
fi
if [ ! -f "$B" ] || [ "$(sha256sum "$B" | cut -d' ' -f1)" != "$BSHA" ]; then
  echo "[*] Downloading Google's LiteRT benchmark tool (7 MB)..."
  curl -sSL -o "$B" "$URL" || curl -sSLk -o "$B" "$URL"
  [ "$(sha256sum "$B" | cut -d' ' -f1)" = "$BSHA" ] || { echo "[!] benchmark tool hash mismatch"; exit 1; }
fi
chmod 755 "$B"

temp(){ for z in /sys/class/thermal/thermal_zone*/; do t=$(cat "$z/type" 2>/dev/null); case "$t" in *skin*|*SKIN*|BIG|LITTLE|MID|G3D) echo "$t=$(cat "$z/temp" 2>/dev/null)";; esac; done | tr '\n' ' '; }
run(){ # $1 = label, rest = flags; tries as this user, then as root
  echo; echo "===== $1 ====="; shift
  "$B" --graph="$M" "$@" 2>&1 | grep -v "^$" | tail -60 > "$D/last.txt"
  if ! grep -q "Inference timings\|Inference (avg)" "$D/last.txt"; then
    echo "(retrying as root)"; su -c "LD_LIBRARY_PATH=/vendor/lib64:/system/lib64 $B --graph=$M $*" 2>&1 | grep -v "^$" | tail -60 > "$D/last.txt"
  fi
  grep -i -E "delegate|executed by|partition|fallback|Init|Inference|Warmup|memory|footprint|error|fail|OpenCL|precision" "$D/last.txt" | head -40
}
{
echo "AdcSR G4 benchmark  $(date)"
echo "device: $(getprop ro.product.model) / $(getprop ro.soc.model) / Android $(getprop ro.build.version.release) / $(getprop ro.build.id)"
echo "temps before: $(temp)"
run "GPU (OpenCL, FP16)" --use_gpu=true --gpu_backend=cl --gpu_precision_loss_allowed=true --num_runs=6 --warmup_runs=1 --min_secs=0 --run_delay=0
echo "temps after GPU: $(temp)"
run "GPU, 6 more runs back-to-back (heat / drift check)" --use_gpu=true --gpu_backend=cl --gpu_precision_loss_allowed=true --num_runs=6 --warmup_runs=0 --min_secs=0
echo "temps after 2nd GPU: $(temp)"
run "CPU reference (XNNPACK, 4 threads, 1 run)" --use_xnnpack=true --num_threads=4 --num_runs=1 --warmup_runs=0 --min_secs=0
} 2>&1 | tee "$OUT"
echo; echo "[*] Saved: $OUT  — send me that file (or paste it)."
