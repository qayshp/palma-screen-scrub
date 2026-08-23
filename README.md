# Palma Screen Scrub

A deliberately small Kotlin diagnostic/workaround app for persistent BOOX Palma E Ink ghosting. It never changes VCOM, front-light settings, display calibration, firmware, or any hardware setting.

## Production launch behavior

**Palma Screen Scrub** is the primary launcher and BOOX side-button entry. It immediately runs the saved production profile as a full-display overlay, removes the overlay when finished, and exposes the app that was already underneath. It does not open a menu or leave a result Activity behind.

**Screen Scrub Settings** is a separate launcher entry with three persistent choices:

| Profile | Tested implementation |
| --- | --- |
| Progressive (fresh-install default) | Existing T2 pipelined progressive reveal; 16 bands; top-to-bottom; 220 ms black; minimum 220 ms white; one submission |
| Fast | Existing T0 opaque sweep; 16 bands; top-to-bottom; 100 ms black; 100 ms white; `duplicates=2` |
| Safe | Existing T0 opaque sweep; 16 bands; top-to-bottom; 220 ms black; 220 ms white; one submission |

The preference stores only the stable profile identifier in `production_profile_id`; missing or unknown values resolve to Progressive. **Palma Screen Scrub / Diagnostics** remains a separate launcher entry for the earlier test controls. Diagnostic numeric settings do not affect the production profiles.

## Current BOOX SDK compatibility

The current public upstream source checked on 2026-08-11 is [onyx-intl/OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo), commit `689ff7f8c4ea971b1b436e5c967c921dc8596f3a` (2026-08-04). Its `EpdDemoActivity` uses `EpdController.repaintEveryThing(UpdateMode.GC)` and the repository documents both of these APIs:

```java
EpdController.invalidate(view, UpdateMode.GC);
EpdDeviceManager.applyGCUpdate(view);
```

The demo's app module currently declares `com.onyx.android.sdk:onyxsdk-device:1.3.5`, although BOOX Maven metadata now lists patch releases through `1.3.5.2`. Static comparison of `1.3.5`, `1.3.5.1`, and `1.3.5.2` found no refresh, repaint, or wait API changes. BOOX does not publish a stable, versioned compatibility table for current Palma firmware. Therefore the default app build intentionally has **no BOOX SDK dependency**. It invokes the exact public class/method names by reflection only when the classes are packaged and available, reporting an unavailable/error result rather than crashing.

If the BOOX artifact can be fetched and compiled, make an optional diagnostic build:

```powershell
.\gradlew.bat assembleDebug -PincludeBooxSdk=true
```

That property adds `onyxsdk-device:1.3.5` from BOOX's public Maven repository. The two GC buttons are still separate tests: one calls `EpdController.invalidate(view, UpdateMode.GC)` and the other calls `EpdDeviceManager.applyGCUpdate(view)`. If that dependency cannot resolve or is incompatible, use the normal build and the black/white tests; they are the safe fallback and directly test the observed dirty-region hypothesis.

On the connected first-generation Palma, both buttons reported that their API call was invoked, but neither produced a significant clearing effect. The sequential stripe scrub did clear the screen. This is device evidence that successful Java-level invocation of these public GC APIs does not, by itself, establish a panel-wide update of unchanged pixels on this firmware.

Firmware analysis on 2026-08-18 found a concrete SDK mismatch. SDK 1.3.5 maps `UpdateMode.GC` to native `0` on this firmware, which SurfaceFlinger rejects. The firmware's actual `UI_GC_MODE` is `0x62`; guarded direct `View.refreshScreen(0x62)` tests are accepted as GC16 waveform `2` and remain behind the experimental opt-in. They cover a bounded app-view rectangle, not the complete physical display, and still require camera verification. The SDK's no-view `repaintEveryThing(GC)` path is a silent no-op here because Android's hidden-API enforcement prevents the SDK from caching the firmware method. See [AUTOMATED_FINDINGS_2026-08-18.md](AUTOMATED_FINDINGS_2026-08-18.md).

Downstream analysis on 2026-08-19 identified the real app-callable SurfaceFlinger global-GC and completion transactions, proved that global `0x62` reaches SDM as the full physical panel and reaches `CMD_TCON_GC_FULL_MODE`, and localized remaining loss below the public SDK. See [DOWNSTREAM_PIPELINE_FINDINGS_2026-08-19.md](DOWNSTREAM_PIPELINE_FINDINGS_2026-08-19.md).

## Prerequisites and build

- Android Studio with Android SDK Platform 35 and a JDK 17-compatible runtime.
- Developer options and USB debugging enabled on the Palma, or an APK transfer method.
- A BOOX Palma running Android 8.0/API 26 or newer. This app targets API 35 and has `minSdk 26`.

Build and install the default, dependency-free APK:

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n dev.palma.screenscrub/.QuickScrubAlias
```

Use `dev.palma.screenscrub/.SettingsAlias` to select the production profile. The normal launch immediately runs that profile. For interactive diagnostics, launch `dev.palma.screenscrub/.DiagnosticsAlias` as described in [DEBUG_CAPTURE.md](DEBUG_CAPTURE.md).

If `adb` is unavailable, install `app-debug.apk` from the Palma's file manager and allow the install source when prompted. The Gradle wrapper downloads Gradle on its first use.

## Exact Palma procedure

Device context for the first run:

- Device: first-generation BOOX Palma.
- Firmware reported: `2026-06-04_00-21_4.2-rel_0603_095d17272b`.
- OnyxEpd: `TCONB-0xd3989a09-en`; VCOM reported: `2.73 V`.
- Do **not** change VCOM. Keep refresh mode, font, and content fixed within each comparison. Record ambient temperature and the current front-light warmth/brightness, but do not change lighting while testing a single row.

1. Reproduce the persistent ghost in a known blank region of a normal app. Photograph the device before opening this app.
2. Trigger BOOX's normal system Full Refresh while that region is blank/unchanged. Photograph the result.
3. For the practical workaround, launch the normal app and let it return after its 16-band scrub. Verify the prior app's target blank region immediately.
4. For interactive diagnostics, launch `DiagnosticsAlias`. Run **GC repaint everything** and **GC explicit app rect** separately; record the visual result and the log marker.
5. Run **Sweep 4/8/16 bands** while filming. It varies 4, 8, and 16 bands independently across 125, 150, 180, 200, 220, and 250 ms per phase.
6. Run the controller and current-device wait tests separately. Treat their duration as an API/scheduling observation, not proof that the E Ink waveform finished.
7. Repeat a fixed-content sequence under a different clearly recorded thermal/front-light condition (for example, cool indoor versus warm/sunlit). Do not infer temperature from brightness alone.

## Outcome and implication matrix

| Result | Supports | Does not establish |
| --- | --- | --- |
| System Full Refresh leaves a blank target region, but scrolling black text over that physical region clears it | BOOX is selecting dirty/current-content regions or otherwise not driving unchanged pixels | A panel defect or VCOM fault |
| `Normal invalidate` only updates its small marker and the distant ghost remains | Ordinary Android invalidation follows a small dirty area | That GC is ineffective |
| A BOOX GC button clears the complete target, including blank pixels | App-issued BOOX GC can drive a larger/full physical region on this firmware | That BOOX's system Full Refresh is equivalent |
| Both BOOX GC buttons report `invoked`, but unchanged target pixels remain ghosted | These public GC paths can complete without providing an effective panel-wide cleanup on this firmware | That either call necessarily produces a physical full-panel GC |
| BOOX GC button reports unavailable | The optional proprietary SDK was not packaged/compatible | That black/white framebuffer changes cannot work |
| Checker inversion or black → white clears the target while a white-only screen does not | Actual framebuffer differences mark/drive blank physical pixels effectively | The exact BOOX dirty-region implementation |
| Same sequence differs by documented ambient temperature/front-light state | Waveform effectiveness is temperature-sensitive independently of the chosen test content | That optical masking caused the ghosting |

## External-camera video and BOOX feedback logs

Use a second phone/camera at 60 fps or higher on a stable stand. Keep autofocus/exposure locked after focusing on the Palma; avoid auto-HDR, screen recording, and reflections. Frame the full panel plus a paper label showing the current test, firmware, ambient temperature estimate, and front-light setting. Record one continuous clip covering: the pre-existing ghost, system Full Refresh, a scroll of black text through the target pixels, a normal invalidation, checker inversion, and Screen Scrub. Do not edit the clip; retain the original file and a timestamped copy.

Immediately after the same reproduction, use **Settings → Feedback** on the Palma. Include the firmware string, `TCONB-0xd3989a09-en`, VCOM `2.73 V` as observed (not changed), the exact screen-refresh mode, front-light state, ambient conditions, and the external video. Submit the report through BOOX Feedback so its diagnostic logs are tied to the event; do not attempt to collect or modify protected system logs.

See [BOOX_BUG_REPORT.md](BOOX_BUG_REPORT.md) for a ready-to-submit report.

## Timing debug

The installed BOOX-SDK debug APK now includes a separate interactive timing screen. It reflects the relevant public BOOX APIs at runtime, calls `EpdController.repaintEveryThing(UpdateMode.GC)`, submits an explicit full app-window GC rectangle, and compares fixed-delay strip runs with worker-thread `waitForUpdateFinished()` calls. [DEBUG_CAPTURE.md](DEBUG_CAPTURE.md) gives the exact launch, logcat, and camera procedure.

## 2026-08-16 diagnostic increment

The debug APK now also provides independently launchable Diagnostics, Quick Scrub, Quick Overlay Scrub, Quick 16-Band Diagnostic, and Quick Accessibility White-Pixel Refresh entries. The menu persists the selected Quick Scrub configuration, records structured per-run events and history, and can copy a full report. See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for safety boundaries and deferred work, and [ADB_LIVE_EXPERIMENTS.md](ADB_LIVE_EXPERIMENTS.md) for the live Palma procedure.

## 2026-08-18 refresh API increment

Diagnostics now separately tests the reflected public `refreshScreen(GC)`, explicit full-view `refreshScreenRegion(GC)`, controller/device `applyGCOnce()`, and GC-interval methods. Each test holds a solid-white app framebuffer idle for a configurable three seconds after the final call. The sweep matrix compares 2, 4, 8, 16, and 32 bands using independently configurable dwell values. [ONYX_API_INVENTORY_NOTES.md](ONYX_API_INVENTORY_NOTES.md) records exact signatures, local demo evidence, and the policy-changing APIs that remain disabled.

The Diagnostics screen also includes **Log firmware mappings (read-only)**. It records firmware-added `View`
refresh/invalidation signatures, `ViewUpdateHelper` waveform/update/wait constants, the SDK's cached hidden-method
targets, every `UpdateMode` ordinal and translated native integer, classloader origins, and current default modes.
It does not issue a refresh, alter update policy, or change any hardware setting.

## Unattended automation

The debug build includes a non-launcher automation alias for repeatable ADB testing. It is disabled in release builds, requires the explicit `confirmSafe=true` extra, and accepts only the app's reviewed diagnostic allowlist. It does not enable the inventory-only scope-policy methods.

Run a focused or complete matrix from the project root:

```powershell
python .\tools\palma_epd_test.py --matrix api --output ..\unattended-api
python .\tools\palma_epd_test.py --matrix all --output ..\unattended-all
```

The runner records device state, app markers, raw and filtered logcat, parsed JSON, and Markdown analysis. Its reports deliberately state that a logged request is not visual proof of a physical panel update. [AUTOMATED_FINDINGS_2026-08-18.md](AUTOMATED_FINDINGS_2026-08-18.md) consolidates the unattended runs and remaining device-camera questions. [DEEP_PIPELINE_FINDINGS_2026-08-18.md](DEEP_PIPELINE_FINDINGS_2026-08-18.md) adds the 220-case mode/area/queue matrix, firmware coalescing analysis, and fast-versus-slow `atrace` comparison.

## Unattended camera suite

The debug app and `tools/palma_camera_suite.py` now support a synchronized external-camera workflow. Every run:

1. shows a large visible run-number/ID slate;
2. applies the same checkerboard → inverse checkerboard → white precondition;
3. executes one reviewed diagnostic target;
4. holds a label-free solid-white framebuffer for five seconds;
5. saves raw/filtered logcat plus parsed SurfaceFlinger/SDM/app records;
6. adds the visible ID and artifact paths to `manifest.json` and `manifest.md`.

Choose one profile:

```powershell
python .\tools\palma_camera_suite.py --profile comprehensive-camera
python .\tools\palma_camera_suite.py --profile smoke-camera
python .\tools\palma_camera_suite.py --profile core-camera
python .\tools\palma_camera_suite.py --profile full-camera
```

`comprehensive-camera` is the recommended/default 33-run recording. Run 12 issues true global GC through SurfaceFlinger `0xff0023` with mode `0x62`, allows 50 ms for HWC/SDM submission, then calls the real `0xff0017` wait; run 13 begins the wait-after-each-transition scrub series. It continues through aggressive timing/band tests, anti-merge controls, overlay equivalents, and a final known-good scrub. `smoke-camera` remains a short end-to-end check, `core-camera` preserves the earlier matrix, and `full-camera` adds repeated timing reliability and queue/geometry tests. The final screen says **COMPLETE** for 15 seconds.

The 2026-08-19 recording is complete. Camera evidence shows that run 12's software-correct full-panel global GC remains physically ineffective, while `0xff0017` returns before it is safe to assume physical completion. The 16-band × 220 ms progressive scrub remains the proven workaround. See [CAMERA_VALIDATION_FINDINGS_2026-08-19.md](CAMERA_VALIDATION_FINDINGS_2026-08-19.md) and the joined artifacts under `outputs/camera-comprehensive-camera-20260819-185116/camera-review/`.

For the morning recording, frame the entire physical Palma, lock focus/exposure, start recording, then run the chosen command (or tell Codex **recording**). Do not touch the device until the **COMPLETE** slate appears. See [CAMERA_SUITE.md](CAMERA_SUITE.md) for profile details, optional-feature behavior, evidence limits, and output layout.

## Included debug APKs

- `releases/PalmaScreenScrub-v0.5.0-debug.apk` is the production-selector build with the optional BOOX SDK packaged for preserved diagnostics.
- `releases/PalmaScreenScrub-debug.apk` is the dependency-free fallback build.
- `releases/PalmaScreenScrub-boox-sdk-debug.apk` packages the optional BOOX SDK so its two GC buttons can attempt the documented API calls. The app manifest removes the SDK's unrelated Wi-Fi, Bluetooth, and `DUMP` declarations. Both variants retain only the user-granted overlay permission needed by the optional full-display overlay test.
- `releases/archive/2026-08-19-pre-static-ordering-v0.3.0/` preserves the exact prior v0.3.0 camera build and checksums.

## Scope and safety

- The app is portrait-only to keep the diagnostics repeatable on the Palma.
- It contains no VCOM, front-light, temperature-control, root, firmware, SELinux, protected-device-node, or hardware-setting changes.
- Version 0.3.1 includes two firmware-specific SurfaceFlinger Binder diagnostics accepted from the ordinary app process: global GC transaction `0xff0023` with `0x62`, and global wait transaction `0xff0017`. Run 12 explicitly separates them with a 50 ms submission settle because static analysis shows the global transaction schedules a frame but does not wait. Direct Binder is used when available; a bounded `/system/bin/service` subprocess is only a compatibility fallback. Neither path grants privileges or writes protected settings.
- The automatic scrub leaves Android's status and navigation bars visible so returning to the prior app does not resize its layout. Those reserved system-bar areas are outside this app's scrub surface.
- A BOOX API success only means the method call completed without a Java exception. The external camera and visual result remain the validity evidence.
