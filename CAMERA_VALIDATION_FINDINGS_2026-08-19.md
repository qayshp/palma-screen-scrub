# Palma comprehensive camera validation — 2026-08-19

## Evidence set

- Video: `IMG_1732.mp4`, approximately 403.17 seconds.
- Suite: 33 fixed runs under one continuous camera, device-temperature, lighting, and firmware state.
- Host artifacts: `outputs/camera-comprehensive-camera-20260819-185116/`.
- Camera sidecars and joined records: `outputs/camera-comprehensive-camera-20260819-185116/camera-review/`.
- App version: `0.3.1`; installed APK SHA-256 `6489A6469B74CF242026019B5738C5F23AF5CB3B3E051E9491B20D7C4F13DFD8`.

The camera review is physical-panel evidence. SurfaceFlinger/SDM logs establish software submission but not pigment completion.

## Decisive result: global GC is physically ineffective

Run 12 issued SurfaceFlinger transaction `0xff0023` with mode `0x62`, waited 50 ms, observed an SDM update before wait begin, then invoked `0xff0017`.

Software evidence:

- SDM waveform `2`;
- full update mode `1`;
- physical rectangle `0 0 1648 824`;
- flag `0x1000`;
- wait returned in 3 ms;
- no crash, ANR, timeout, or unavailable result.

Camera evidence:

- the seeded checker ghost remained across essentially the full display;
- no meaningful clearing occurred during the white hold;
- no visible late catch-up occurred.

This rules out View bounds, Android dirty rectangles, and SurfaceFlinger rectangle selection as sufficient explanations for the global-refresh failure. The remaining candidates are below the confirmed SDM submission or concern waveform effectiveness: protected vendor HWC behavior, driver/TCON filtering, collision or latest-buffer handling, waveform execution, and temperature/LUT selection.

## Decisive result: ioctl `0x7010` is not a physical fence

Run 13 used 16 bands with 30 ms post-draw submission settle and called `0xff0017` after every black and white transition. All 32 waits returned in 1–4 ms. The camera showed most of the app region clearing, but about three stable horizontal missed bands remained.

Runs 14–18 reduced settle time or increased band count. Every wait remained available and returned in 1–7 ms, while the physical residual progressively worsened. Run 18's 64-band zero-settle scrub left many thin missed bands across much of the screen.

The wait affects sequencing enough to change the failure pattern, but its return cannot be treated as physical waveform or pigment completion.

## Controls and secondary findings

- Runs 2 and 33, the opening and final 16-band × 220 ms controls, both cleared the app-owned region. This confirms stable recording conditions and preserves the current default.
- Run 3, 16 bands × 31 ms, left multiple missed bands.
- Runs 4–11, covering normal invalidate, public SDK GC, and native View modes through `0x62`, produced no meaningful clearing.
- Runs 19–20, whole-frame black→white and checker→inverse→white, produced no meaningful clearing. Large framebuffer change alone is insufficient; spatial progression and pacing matter.
- Run 23, duplicate 16-band × 100 ms, appeared clean. Run 24, bottom-to-top 16-band × 140 ms, also appeared clean. Each needs repeated camera validation before becoming a default.
- Runs 25–28 showed no meaningful clearing. Alternating modes to defeat same-mode merging does not solve unchanged-region ghosting.
- Run 29 confirmed overlay 16-band × 220 ms clears its covered region. Runs 30–31 failed at fast/zero-settle timing similarly to Activity-owned runs.
- Run 32 did not execute the accessibility algorithm: automation rejected it because the optional accessibility service was disabled. The visible residual is therefore not evidence about that algorithm's effectiveness.
- Successful app and overlay scrubs left residual in the bottom system/navigation strip outside their coverage. Run 12 differed because its logged update was genuinely global, yet still failed physically.

## Current working model

1. The global request is formed correctly and reaches SDM with full physical geometry and GC/full semantics.
2. The physical panel nevertheless retains unchanged ghost state, localizing that defect below SurfaceFlinger rectangle selection or to ineffective waveform execution.
3. Intermediate HWC-originated framebuffer states require real elapsed time after submission. The exposed ioctl-backed wait returns before that requirement is satisfied.
4. Fast updates are likely collided, coalesced, superseded, filtered, or sampled from a newer framebuffer below SDM rather than merely queued for later replay; failing holds showed no late procession of missed updates.
5. Temperature-dependent waveform/LUT behavior remains a separate plausible contributor, but was not varied in this recording.

## Practical conclusion

Keep the 16-band × 220 ms progressive scrub as the production-safe workaround. Do not replace it with global GC or ioctl-wait sequencing. Repeat the 100 ms duplicate and 140 ms bottom-to-top candidates only as diagnostics; one clean camera observation is insufficient to establish reliability.
