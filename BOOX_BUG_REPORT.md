# BOOX Feedback draft: Full Refresh does not clear blank physical regions

**Device:** first-generation BOOX Palma  
**Firmware:** `2026-06-04_00-21_4.2-rel_0603_095d17272b`  
**OnyxEpd:** `TCONB-0xd3989a09-en`  
**Observed VCOM:** `2.73 V` (reported for identification only; never changed)

## Summary

The system's Full Refresh does not reliably clear persistent E Ink ghosting from physical screen regions that are blank or otherwise unchanged in the current framebuffer. If black text is scrolled through those exact physical pixels, the ghosting clears. This has persisted across multiple firmware versions.

## Reproducible procedure

1. Use a normal BOOX app until visible ghosting remains in a physical region that is now blank/white.
2. Trigger the BOOX system Full Refresh without changing content in that target region.
3. Observe and externally record that the ghost remains in the blank target region.
4. Scroll black text through the same physical pixels.
5. Observe and record that the ghost clears after actual black/white framebuffer changes.
6. Repeat after a reboot and, where available, with the same fixed content across refresh modes.

## Expected result

A user-invoked Full Refresh should drive the whole physical panel, including currently blank/unchanged regions, sufficiently to clear normal residual ghosting.

## Actual result

The Full Refresh fails to clear unchanged blank physical regions. A diagnostic full-display request was independently confirmed at Qualcomm SDM as waveform `2`, full update mode `1`, physical rectangle `0,0,1648,824`, flag `0x1000`, yet an external camera showed the seeded checker ghost remaining essentially unchanged. Real paced content motion across those pixels clears the ghosting.

## Additional diagnostic observations

- The behavior survived firmware updates; it is not isolated to one release.
- In Palma Screen Scrub, both public SDK calls `EpdController.invalidate(view, UpdateMode.GC)` and `EpdDeviceManager.applyGCUpdate(view)` completed without a Java exception but did not produce a significant clearing effect. A sequential app-issued black-to-white stripe sweep did clear the screen.
- On older firmware, the ghosting became nearly absent at 100% fully cool front light.
- The device also tends to ghost less when warm or in full sun.
- Optical masking has been ruled out, so temperature/waveform behavior appears to be a secondary factor independent of dirty-region selection.
- No VCOM or unsafe hardware setting was changed.
- A 33-run continuous external-camera recording used identical temperature, lighting, firmware, and camera conditions. Opening and final 16-band × 220 ms controls both cleared the app-owned region.
- The real global route used SurfaceFlinger transaction `0xff0023` with mode `0x62`; SDM logged a full-panel waveform-2/full update before the app invoked SurfaceFlinger wait transaction `0xff0017`.
- The corresponding `/dev/ebc` wait returned in 3 ms and the checker ghost remained. Per-transition waits returned in 1–7 ms and faster scrubs still skipped complete horizontal bands, so the exposed wait is not a physical panel-completion fence.
- Whole-frame black→white, checker→inverse→white, public SDK GC, native View GC, and anti-merge mode alternation all failed to clear the seeded unchanged ghost. Progressive paced strip updates succeeded.

## Requested investigation

Please inspect the pipeline below the confirmed full-panel SDM submission: protected HWC/vendor display handling, `/dev/ebc` queue/collision behavior, driver completion semantics, unchanged-tile filtering, TCON waveform execution, and temperature/LUT selection. Please provide a user-accessible full physical-panel refresh and a real completion mechanism that do not require paced contrasting content through every affected region.

## Attachments to include

- Original external-camera video showing: ghost → system Full Refresh (ghost remains) → black-text scroll through target pixels (ghost clears).
- Exact front-light brightness/warmth, display refresh mode, ambient temperature estimate, and timestamp.
- BOOX Feedback diagnostic logs captured immediately after the reproduction.
- `IMG_1732.mp4`, the 33-run camera sidecar, the host manifest, and filtered SurfaceFlinger/SDM logs. Run number is the join key.
