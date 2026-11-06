# GCam 100x Experimental (LSPosed module)

A first-stage experimental LSPosed module for Google Camera
(`com.google.android.GoogleCamera`). It raises the rear camera's reported
zoom limit to 100× and logs what the camera really supports, so we can tell
where the zoom ceiling comes from.

Target setup: Pixel 9 Pro XL, Android 17, Google Camera `10.4.117.936816638.14`,
LSPosed with the modern LibXposed API (102).

This is a standalone module with its own package (`com.experimental.gcam100x`).
It does **not** contain any GCam Boost code, so you can run it next to GCam
Boost without hooking anything twice.

## What it does

Turns on Google Camera 11.1's own Pro Zoom UI (slider, label, viewfinder
crop) up to 120× on a Pixel 9 Pro XL, and produces the Pro Zoom photo inside
Google Camera's save pipeline:

1. **Framing (1:1).** The viewfinder past 30× is an exact centre crop of the
   30× preview stream, but the 30× still is framed slightly differently. The
   still is registered against the viewfinder frame on screen at the shutter
   (normalised cross-correlation over position, scale and rotation, coarse to
   fine; `Align.kt`, tested in `AlignTest`). If the scene is too flat to
   match, it falls back to the centre.
2. **Detail.** Real-ESRGAN SRVGGNetCompact x4 (general model, denoise strength
   0.5; `tools/build_prozoom_models.py`) runs on the Edge TPU through NNAPI
   (GPU/CPU fallback). It runs at the crop's real detail scale (1/4), the result
   is at the crop's native resolution, and only luminance detail is
   applied, so colour, tone and grain stay the photo's own.
3. Preview stabilization is turned off while Pro Zoom is active, so the
   magnified preview does not drift.

Settings → GCam100x (last entry) shows versions, backend, the last photo's
timing and framing result, a "Copy log" button and the startup toast switch.

## Build

Requirements: JDK 21 (Android Studio's bundled runtime works) and Android
SDK Platform 37.

```powershell
cd gcam100x
.\gradlew.bat :app:assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

On Linux or macOS, use `./gradlew` instead.

## Install

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

1. In LSPosed Manager, enable **GCam 100x Experimental**.
2. Check that its scope includes **Google Camera** (`com.google.android.GoogleCamera`).
3. Leave GCam Boost enabled if you use it.
4. Force-stop the camera: `adb shell am force-stop com.google.android.GoogleCamera`

## Test

```powershell
adb logcat -c
# open Google Camera, switch to the rear camera, zoom all the way in, take a photo
adb logcat -d | findstr GCam100x
```

The module writes every message to logcat as well as to the LSPosed log.
If the command
above prints nothing at all, not even `Module loaded`, the module never
ran inside the camera app: reboot once and check the LSPosed scope.

You should see lines like:

```text
GCam100x: Hooks installed. Target max zoom: 100.0x (rear cameras only)
GCam100x: Camera=0 Facing=BACK OriginalMaxDigitalZoom=... OriginalZoomRatioRange=[..., ...] PhysicalIds=[...]
GCam100x: Overriding zoom limits on rear camera (...): android.control.zoomRatioRange [...] -> [..., 100.0]
GCam100x: CaptureRequest CONTROL_ZOOM_RATIO=...
```

### How to read the results

* **A – the slider now goes past 30×.** Google Camera accepted the new limit.
  Take a photo at the maximum zoom and check whether the field of view really
  gets narrower.
* **B – the slider still stops at 30×, and `CONTROL_ZOOM_RATIO` never goes above 30.**
  Google Camera sets its own zoom ceiling in its app code or config, not from
  this metadata. The next step is to find that limit in the APK with JADX and
  hook it.
* **C – `CONTROL_ZOOM_RATIO` goes above 30, but photos stop getting narrower.**
  The camera HAL clamps the crop to its real limit. The next step is to work
  on Google Camera's processing pipeline.

## Disable or remove

Turn the module off in LSPosed Manager and force-stop Google Camera. To
remove it completely, run `adb uninstall com.experimental.gcam100x`.
