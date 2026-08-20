# Palma downstream E Ink pipeline findings — 2026-08-19

This report localizes the current first-generation Palma behavior below the public BOOX Java SDK. It is diagnostic reverse engineering, not a recommendation to change VCOM, firmware, SELinux policy, protected device nodes, or TCON settings. No such setting was changed.

## Executive result

Two different update routes exist on this firmware:

1. Normal app drawing is composed by SurfaceFlinger and passed through BOOX's custom HWC command to SDM, then the EPDC/TCON path.
2. The hidden region-refresh Binder transaction `0xff0001` is handled by SurfaceFlinger and calls `FBDev::refreshScreen`, which submits ioctl `0x700c` to `/dev/ebc` directly. It bypasses the HWC/SDM command path.

The hidden global repaint transaction `0xff0023` is different again: it installs a one-shot special update mode and schedules a SurfaceFlinger frame. With mode `0x62`, the resulting HWC/SDM update is a physical full-panel rectangle and is correlated with the TCON's GC-full command.

The strongest current localization is therefore:

- Fast scrub frames are accepted through SurfaceFlinger and can reach SDM at display cadence, but there is no completion fence in the HWC `commitEpdc` command. Updates may still be superseded, collided, or reduced to the latest framebuffer state below that point.
- Global `0x62` reaches SDM as `Rect[0 0 1648 824]`, waveform `2`, full update, flag `0x1000`, and reaches the TCON command layer as `CMD_TCON_GC_FULL_MODE`. If unchanged white areas still retain physical ghosting, the omission is not an Android dirty rectangle above SDM for this route. Filtering or ineffective driving must be at or below the vendor/driver/TCON boundary, or in waveform/temperature behavior.
- A global wait primitive exists: SurfaceFlinger transaction `0xff0017` calls `FBDev::waitAllUpateComplete()`, which issues ioctl `0x7010`. It is not marker-specific, and the accessible userspace binary does not reveal the kernel's completion predicate.
- Transaction `0xff0023` does not internally call that wait. It stores the one-shot mode and schedules a SurfaceFlinger refresh, so an immediate separate wait can race ahead of HWC submission.

## Evidence levels

### Confirmed by static analysis and live logs

| Item | Result |
| --- | --- |
| Global repaint transaction | `0xff0023` (`16711715`) reads one mode integer, stores a one-shot update, and signals a SurfaceFlinger refresh. |
| Global completion transaction | `0xff0017` (`16711703`) calls `FBDev::waitAllUpateComplete()` → `ioctl(epdc_fd, 0x7010, 0)`. |
| Ordinary region transaction | `0xff0001` reads a rectangle and mode, posts work, transforms the rectangle, and calls `FBDev::refreshScreen()` → ioctl `0x700c`. |
| App-UID access | Both `0xff0023` and `0xff0017` returned successful Binder replies when called as UID `u0a133`, the installed app's ordinary UID. No root or system UID was used. |
| Global physical rectangle | App-UID global `0x62` produced SDM `Rect[0 0 1648 824]`, waveform `2`, update mode `1`, flags `0x1000`. |
| TCON global mode | The correlated marker produced `CMD_TCON_GC_FULL_MODE`, command `0x1`, data `0x7`. |
| HWC transport | `Hwc2::impl::Composer::CommandWriter::commitEpdc` emits a custom command header that appears to encode command ID `0x802`, followed by a count and five integers per `hwc_epdc_llist`. It exposes no completion fence. |
| Same-mode region merge | `EpdcWrapper::mergeByMode` unions all rectangles with the same mode into their bounding rectangle; it does not require overlap. This merge occurs in the app surface path, layer path, and again during SurfaceFlinger collection. |
| Selector behavior | `HWNormalSchema` adds candidates in this order: filter, one-shot `next`, layer-change, bypass, fixed. `UpdateEntryDetector::output` keeps replacing ordinary modes and only stops early for selected special modes, including `0x62`. A later normal layer-change update can therefore replace an ordinary explicit mode such as `2`; global `0x62` survives arbitration. |
| SDK failure | SDK 1.3.5's reflected hidden methods are absent or blocked on this firmware. Its wait call can return immediately without reaching ioctl `0x7010`, and its `repaintEveryThing(GC)` can report invocation without reaching the real global transaction. |

### Live zero-delay region experiment

The existing diagnostic build submitted 12 `View.refreshScreen(region, 0x2)` requests over three app-space regions with zero configured inter-request delay:

- All 12 calls reached SurfaceFlinger transaction `0xff0001`.
- All 12 produced SurfaceFlinger `refresh screen (...) waveform_mode 2` logs and markers `2906` through `2917` in 61 ms.
- This direct `FBDev` route does not pass through SDM, so absence of SDM waveform-2 lines is expected and must not be interpreted as loss before the driver.
- The request method returned in 2–6 ms. Return from the Binder call is therefore submission completion, not evidence that the panel waveform finished.
- A later composed frame produced the usual HWC/SDM full-screen waveform-4 update. It is a separate route and should not be counted as one of the twelve direct region ioctls.

The transformed region logs also show that `FBDev::refreshScreen` clamps the origin but constructs the submitted extent from that origin to the panel's right/bottom edge. It does not preserve the requested width and height in the final ioctl structure. This is an important BOOX implementation detail and makes the public-looking region API less precise than its signature suggests.

### Live fast framebuffer scrub experiment

An 8-band black/white scrub at 20 ms per phase produced app draw events and normal HWC/SDM updates at approximately display cadence. SDM emitted successive full-screen waveform-4 markers rather than strip-sized physical rectangles. This means a visible skipped band can occur even when the high-level frame and an SDM update marker exist. A marker is evidence of submission to SDM, not proof that the corresponding intermediate black framebuffer was separately driven to completion on the panel.

### Strong inference, not yet proven

- The practical speed boundary is controlled by asynchronous framebuffer lifetime and/or the vendor/driver/TCON update queue, not solely by Android invalidation.
- Because HWC `commitEpdc` has no returned completion object, SurfaceFlinger can submit another buffer before the prior E Ink operation is physically complete.
- The TCON or driver may merge/collide pending updates, sample the latest framebuffer, or suppress unchanged tiles after receiving the full rectangle. The protected QTI vendor libraries and kernel implementation are required to distinguish these cases.
- The second full-screen waveform-4 update often observed immediately after global GC likely represents a normal current-content composition update, but its exact trigger has not been proven.

## `repaintEverything(0x62)` call chain

```text
ordinary app UID
  → Binder SurfaceFlinger transaction 0xff0023
  → EpdcManager one-shot mode = 0x62, count = 1
  → SurfaceFlinger refresh
  → HWNormalSchema candidate selection
  → UpdateEntryDetector stops on special mode 0x62
  → full-screen hwc_epdc_llist
  → HWComposer::commitEpdc
  → HWC2 Composer custom command (appears to be 0x802)
  → Qualcomm SDM: waveform 2, full update, Rect 0,0,1648,824, flag 0x1000
  → kernel/TCON marker
  → CMD_TCON_GC_FULL_MODE
```

This is not a special direct global-refresh ioctl in SurfaceFlinger. It is a privileged-looking but app-callable Binder request that forces a special one-shot mode through the HWC path.

## Completion path

```text
ordinary app UID
  → Binder SurfaceFlinger transaction 0xff0017
  → FBDev::waitAllUpateComplete()
  → ioctl(/dev/ebc, 0x7010, 0)
```

The misspelling `waitAllUpateComplete` is present in the firmware symbol. The call has no marker argument, retry loop, userspace timeout, or additional synchronization. Static analysis proves the ioctl call but cannot establish whether the kernel waits for panel drive completion, queue drain, collision handling, or another condition.

The app now measures this primitive with an in-process Binder client. Across smoke tests it returned in roughly 2–21 ms. In the ordered v0.3.1 test, a full-panel SDM waveform-2/full-update event was logged before `0xff0017` began, yet the ioctl path returned in 6 ms. That rules out the simplest pre-submission race for that run, but it still does not prove physical E Ink completion; external-camera evidence remains required.

## Flag and mode mapping

`FBDev::refreshScreen` maps the packed Java/native mode into the driver request:

- Low nibble selects waveform. `0x2` maps to waveform `2`; `0x5` maps to `0xff`; `0x6` maps to waveform `5`; `0xb`, `0xc`, and `0xd` pass through.
- Bit `0x40` maps to driver flag `0x1000`. Firmware constants name `0x40` as `EINK_WAIT_MODE_WAIT`, so `0x1000` carries a downstream wait request. It does not make the calling `0xff0023` Binder transaction synchronous: that transaction returned in 1–2 ms and contains no explicit completion call.
- Bit `0x100` maps to driver `0x2000` for waveform 1 or 4, otherwise `0x4000`.
- Waveform 4 without input bit `0x100` adds driver flag `0x8000`.
- Input bits `0x10000`, `0x20000`, and selected high-bit combinations map to same/high driver flags.

The symbolic meanings of `0x2000`, `0x4000`, `0x8000`, and the higher flags are not proven. They should not be guessed from behavior alone.

`UI_GC_MODE = 0x62` decomposes as waveform `0x2` + full-update bit `0x20` + wait bit `0x40`.

## Temperature path

No EPD temperature or thermal selection code was found in the accessible SurfaceFlinger binary. Exposed Android thermal zones describe Qualcomm SoC, display, and battery sensors; none is clearly an EPD/TCON temperature input. Temperature buckets, LUT selection, or waveform compensation are therefore likely inside the inaccessible QTI vendor display libraries, kernel driver, TCON firmware, or encrypted waveform data.

The user's repeated observation that warmth/direct sun improves ghosting remains valuable physical evidence, but the responsible code path is not yet visible from an ordinary ADB shell.

## Access and safety boundary

- `/dev/ebc` is root-only and SELinux-labelled `ebc_device`; an ordinary app cannot open it directly.
- `/vendor/lib64/libsdmcore.so` and related QTI display libraries exist but cannot be read by the shell domain on this production build.
- `/proc/kallsyms` is denied to the shell, so kernel handler and queue symbols cannot be inventoried.
- The firmware Binder dispatcher explicitly accepts the discovered transaction codes, and live tests show the installed ordinary app UID can call the global repaint and wait transactions.
- Calling them from a normal Android app still requires a way to obtain and transact on the hidden SurfaceFlinger Binder. Reflection may be blocked by hidden-API enforcement; a JNI/libbinder client would be version-specific; spawning `/system/bin/service` is inspectable but not a production-quality API.

No protected file, sysfs setting, VCOM value, firmware component, SELinux policy, queue-size setting, or front-light setting was modified during this work.

## What this resolves

1. **Where can fast framebuffer updates be lost?** After SDM submission remains possible. The public HWC command provides no physical completion fence, and successive full-screen updates are submitted at display cadence.
2. **Does a real completion primitive exist?** Yes: global ioctl `0x7010`, reachable through SurfaceFlinger transaction `0xff0017` on this firmware.
3. **How does `repaintEverything(int)` work?** The real global path is a one-shot special mode through normal SurfaceFlinger composition and HWC, not a SurfaceFlinger direct ioctl.
4. **Does global GC submit the full physical panel?** Yes: SDM receives `0,0,1648,824`, and the TCON receives `CMD_TCON_GC_FULL_MODE`.
5. **Is full refresh filtered above SDM?** Not by rectangle selection for the tested global path. If blank physical areas remain ghosted, any filtering is lower or the waveform is ineffective there.
6. **Can an ordinary app reach hidden capabilities?** The app UID can invoke both real global GC and global completion transactions. A stable production-safe Android binding is not yet established.

## Most useful next physical test

After the morning camera suite, compare these under identical panel state:

1. Real Binder global `0x62`, 50 ms submission settle, then Binder wait `0xff0017` (automated run 12).
2. Current 16-band 220 ms black/white scrub.
3. The implemented scrub that calls global wait `0xff0017` after every black and white framebuffer commit (automated run 13 onward).

If (3) eliminates skipped bands at shorter delays, incomplete sequencing is confirmed. If (1) still leaves blank-region ghosts despite the proven full SDM rectangle and TCON GC-full command, BOOX should investigate lower-level unchanged-tile suppression and temperature/LUT selection.

## Bug-report-ready engineering summary

On Palma firmware `2026-06-04_00-21_4.2-rel_0603_095d17272b`, SurfaceFlinger transaction `0xff0023` with `UI_GC_MODE 0x62` survives `HWNormalSchema` arbitration, is committed through the custom HWC EPD command, reaches Qualcomm SDM as waveform 2/full update over physical rectangle `0,0,1648,824` with flag `0x1000`, and is correlated with `CMD_TCON_GC_FULL_MODE`. Nevertheless, physical ghosting in unchanged blank pixels survives, while real black→white framebuffer changes clear those pixels. This localizes the global-refresh defect below SurfaceFlinger's rectangle selection. Separately, ordinary fast scrub frames can reach SDM at display cadence without a surfaced completion fence, making lower-level coalescing/collision/latest-buffer sampling the leading explanation for entirely skipped bands. SurfaceFlinger also exposes global completion transaction `0xff0017`, which calls `/dev/ebc` ioctl `0x7010`, but the public SDK 1.3.5 wait wrapper does not reach it on this firmware because its hidden reflected method is unavailable.

## Artifacts

- Ghidra decompilation: `work/ghidra-output/libsurfaceflinger.so-epd-decompile.txt`
- App/HWC/global debug capture: `work/palma-native/debug-coalescing-2026-08-19.log`
- Direct region debug capture: `work/palma-native/debug-native-abc-zero-delay-2026-08-19.log`
- Ghidra target script: `work/ghidra-scripts/DecompileEpdTargets.java`
