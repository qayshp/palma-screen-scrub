# Unattended camera validation

This workflow correlates an uninterrupted external-camera video with app, SurfaceFlinger, and readable SDM logs. It makes no VCOM, front-light, firmware, root, hidden-policy, calibration, or hardware changes.

## Before recording

- Connect the first-generation Palma by USB and confirm USB debugging authorization.
- Leave display refresh mode and front-light settings unchanged for the whole recording.
- Frame the complete physical panel, including the navigation strip, with a stable external camera.
- Use 60 fps or higher when available. Lock exposure and focus after framing.
- Keep the phone and Palma still. Do not use Android screen recording as physical evidence.

The app must be the installed debug build. Build and install it with:

```powershell
$env:JAVA_HOME='C:\path\to\jdk17'
$env:ANDROID_HOME='C:\path\to\android-sdk'
.\gradlew.bat assembleDebug -PincludeBooxSdk=true
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The runner compares the installed base APK's SHA-256 with the local debug APK and stops before showing any slate if they differ.

## Recording command

Use `comprehensive-camera` for the next recording. It is also the default profile:

```powershell
python .\tools\palma_camera_suite.py --profile comprehensive-camera
```

Available profiles:

| Profile | Purpose |
| --- | --- |
| `comprehensive-camera` | Recommended 33-run recording: old controls, known-good/fast scrubs, true global GC, real completion-wait timing/band tests, anti-merge tests, overlays, accessibility, and a final known-good sanity scrub |
| `smoke-camera` | Short pipeline check with negative control, SDK/native GC, anti-merge, checker, fast scrub, and known-good scrub |
| `core-camera` | Earlier physical comparison across controls, native GC encodings, anti-merge pairs, timing/band matrices, waits, duplicate/direction, overlay/accessibility, and positive control |
| `full-camera` | Core profile plus queue delays, geometry ordering, and three repeats at dense 80–200 ms timing points |

The comprehensive run order is intentionally fixed:

1. no-clean white control;
2. opening known-good 16-band × 220 ms scrub;
3. deliberately fast 16-band × 31 ms scrub;
4. normal Android, SDK, and native View GC controls;
5. run 12: true global GC through SurfaceFlinger `0xff0023`, mode `0x62`, then a 50 ms submission settle and SurfaceFlinger wait `0xff0017`;
6. run 13: 16-band scrub using SurfaceFlinger wait `0xff0017` after every black and white draw, with 30 ms post-draw commit settle;
7. progressively more aggressive 16-, 32-, and 64-band real-wait runs down to zero commit settle;
8. whole-frame, checker, public-wait, duplicate, direction, and four anti-merge controls;
9. known-good, fast, and real-wait overlay equivalents plus the accessibility control;
10. run 33: final known-good 16-band × 220 ms sanity scrub.

The suite never stops early based on a global-GC or wait result. This preserves all comparisons under the same temperature, lighting, firmware, and camera setup.

Procedure:

1. Start the external camera recording.
2. Start the command, or tell Codex **recording** so it can start the command.
3. Do not tap, swipe, press navigation buttons, unplug USB, or change lighting.
4. Each test first shows a large `RUN n` title, the profile, test name, multi-line details, and a unique host ID.
5. Each test then creates the same checker-derived ghost precondition before applying its target action.
6. Inspect the label-free five-second white hold after each target in the video.
7. Stop recording only after the large `COMPLETE` slate appears.

If overlay permission or the accessibility service is not already enabled, that optional case records a clear rejected/unavailable result and continues. The runner never opens a permission screen or waits for a tap.

## Output

The default output is a timestamped sibling directory such as `outputs/camera-comprehensive-camera-YYYYMMDD-HHMMSS/`. It contains:

- `manifest.md` and `manifest.json`: visible run ID, target, result, duration, and exact artifact names;
- `suite-logcat.txt`: continuous all-buffer logcat for the session;
- `NNN-*-raw-logcat.txt`: complete per-run logcat;
- `NNN-*-filtered.txt`: app/EPD/SurfaceFlinger/SDM-focused lines;
- `NNN-*.json`: parsed calls, rectangles, waveform/flags, markers, latencies, real-wait durations/results, and failures;
- `before-*` and `after-*`: device, display, thermal, service, tracing, and access snapshots.

Use the slate's visible ID to match a moment in the video to a manifest row. Keep the original, unedited camera file next to the output directory and record its filename and SHA-256 in your handoff notes.

## Interpretation

- The camera determines whether physical ghosting cleared, weakened, remained, or an entire band was skipped.
- App/SF/SDM logs determine which software submissions were accepted and how they were represented.
- A Java return, SurfaceFlinger marker, SDM event, or `AUTOMATION_END` does not prove physical E Ink completion.
- Static analysis shows `0xff0023` only schedules a SurfaceFlinger refresh; it does not internally wait. Run 12 therefore waits 50 ms before invoking `0xff0017`, avoiding the obvious race where the wait could run before the HWC update is submitted.
- Static analysis shows `0xff0017` reaches `/dev/ebc` ioctl `0x7010`, but app smoke tests returned in roughly 2–21 ms. In the v0.3.1 ordered smoke test, the full-panel SDM event preceded the wait and the wait still returned in 6 ms. The video must determine whether it is a physical fence, queue-drained fence, or ineffective for HWC-originated updates.
- The known-good 16-band × 220 ms scrub remains the production default regardless of faster software-complete results.

Previous recording APKs are preserved under `releases/archive/`, including the exact v0.3.0 build archived before the static-ordering change.
