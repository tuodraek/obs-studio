#!/data/data/com.termux/files/usr/bin/sh
# Lets non-allowlisted apps use the Tensor TPU until the next reboot, so the AdcSR bench
# app can compile and run on it. Run in normal Termux:  sh /sdcard/Download/tpu_allow.sh
# Undo: reboot the phone (or: su -c 'setprop vendor.edgetpu.service.allow_unlisted_app ""').
# Result: /sdcard/Download/adcsr_tpu_allow.txt
OUT=/sdcard/Download/adcsr_tpu_allow.txt
su -c '
echo "TPU allowlist switch  $(date)"
echo "before: allow_unlisted_app=[$(getprop vendor.edgetpu.service.allow_unlisted_app)]"
setprop vendor.edgetpu.service.allow_unlisted_app true
echo "after:  allow_unlisted_app=[$(getprop vendor.edgetpu.service.allow_unlisted_app)]"
# The service may only read the property when it starts: restart it (the camera must be closed).
am force-stop com.google.android.GoogleCamera 2>/dev/null
for s in edgetpu_app_service edgetpu_vendor_service; do stop $s 2>/dev/null; start $s 2>/dev/null; done
sleep 2
echo "services: app=[$(getprop init.svc.edgetpu_app_service)] vendor=[$(getprop init.svc.edgetpu_vendor_service)]"
echo "--- how the service decides (read-only, for the record)"
for f in /vendor/bin/hw/*edgetpu*; do echo "$f"; strings "$f" 2>/dev/null | grep -i -E "allow_unlisted|allowlist|should not be allowed|unlisted" | sort -u | head -12; done
' 2>&1 | tee "$OUT"
echo
echo "[*] Now open the AdcSR bench app and tap \"1. Decoder on TPU\". When it says ALL DONE,"
echo "    send me /sdcard/Download/adcsr_bench4.txt (and $OUT)."
