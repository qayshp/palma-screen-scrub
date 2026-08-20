# Palma global-refresh ordering findings — 2026-08-19

This bounded static pass answers the sequencing questions needed for the camera suite. It does not modify VCOM, front light, firmware, SELinux policy, protected device nodes, TCON settings, or kernel state.

## Decisive findings

1. `0xff0023` is asynchronous from the Binder caller's perspective. Its dispatcher reads the mode, stores a one-shot update, calls `SurfaceFlinger::refresh()`, and returns. It does not call `waitAllUpateComplete()`.
2. `0xff0017` directly calls `FBDev::waitAllUpateComplete()`, which performs `ioctl(epdc_fd, 0x7010, 0)`. There is no update marker, userspace retry, userspace timeout, or secondary fence.
3. An immediate `0xff0023` → `0xff0017` pair can race: the refresh request may have returned before the scheduled frame reaches HWC and `/dev/ebc`.
4. `0x62` is constructed by firmware as waveform `0x02` plus full-update bit `0x20` plus wait-request bit `0x40`. `0x20` becomes update mode `1`; `0x40` becomes downstream flag `0x1000`.
5. The `0x40` bit is transported, not ignored, but it does not make `0xff0023` itself synchronous. The observed global Binder call returned in 1–2 ms.
6. The global route creates a full primary-display update before `HWComposer::commitEpdc`. The accessible SurfaceFlinger/HWC code shows no later tile comparison before that custom HWC command. Live SDM logs confirm the full physical rectangle. Any unchanged-tile suppression must therefore be below the accessible SurfaceFlinger code, if it occurs at all.
7. A full framework search found no internal BOOX caller that pairs `repaintEverything(...)` with `waitForUpdateFinished()`. The APIs exist as separate operations.

## Ordered device check

Version `0.3.1` adds an automation-only ordered global test while retaining the pure global action in the diagnostics menu:

```text
solid-white draw
  → 180 ms render settle
  → SurfaceFlinger 0xff0023, mode 0x62
  → 50 ms submission settle
  → worker-thread SurfaceFlinger 0xff0017
  → quiet white hold
```

The smoke result on serial `6B378EF8` was:

- global Binder return: 2 ms;
- SDM global event before wait: waveform `2`, update mode `1`, `Rect[0 0 1648 824]`, flags `0x1000`;
- wait return: 6 ms;
- no crash, ANR, timeout, or unavailable result.

This confirms the new ordering avoids the obvious pre-submission race. It does not establish that 6 ms represents completed physical panel drive.

## What remains unknowable from accessible binaries

- The exact kernel predicate for ioctl `0x7010`.
- Whether protected QTI HWC code or the kernel compares framebuffer tiles and suppresses unchanged content after receiving the full rectangle.
- Whether the wait flag means collision wait, queue admission, queue drain, waveform completion, or another condition.
- Whether temperature/LUT selection occurs in protected vendor code, the driver, TCON firmware, or waveform data.

## Camera result

- Run 12 did not meaningfully clear the seeded checker ghost despite its logged full-panel SDM rectangle and the wait beginning after SDM submission. The unresolved failure is therefore below SurfaceFlinger rectangle selection or is waveform/temperature effectiveness rather than dirty-region selection.
- Run 13 cleared most of the app region but left about three stable horizontal bands. More aggressive real-wait variants progressively worsened.
- Ioctl `0x7010` is not a sufficient physical pigment-completion fence for HWC-originated framebuffer transitions on this firmware.
- The opening and final 16-band × 220 ms controls both cleared the app-owned region, confirming stable conditions and preserving that scrub as the proven workaround.
- Full joined results are in `outputs/camera-comprehensive-camera-20260819-185116/camera-review/MERGED_CAMERA_AND_LOG_EVIDENCE.md`.

## Evidence artifacts

- Static decompilation: `work/ghidra-output/libsurfaceflinger.so-epd-decompile.txt`
- Framework bytecode: `work/palma-framework/smali2/android/onyx/ViewUpdateHelper.smali`
- Ordered smoke JSON: `outputs/validation-global-ordering-v031/001-global-ordering-smoke.json`
- Ordered filtered log: `outputs/validation-global-ordering-v031/001-global-ordering-smoke-filtered.txt`
