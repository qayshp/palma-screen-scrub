# Automated Palma EPD findings — 2026-08-18

This report separates software evidence from the external-camera evidence needed to establish what the physical E Ink panel did. No VCOM, front-light, firmware, root, system policy, or hardware setting was changed.

## Scope and validation

- Device: first-generation BOOX Palma, serial `6B378EF8`.
- Firmware: `2026-06-04_00-21_4.2-rel_0603_095d17272b`; OnyxEpd `TCONB-0xd3989a09-en`.
- Build: BOOX SDK 1.3.5 debug build assembled and installed successfully.
- Automated entrypoint: an exported, non-launcher debug alias accepts only a reviewed allowlist of tests, requires `confirmSafe=true`, and refuses release builds.
- Runs: 346 accumulated valid unattended app runs, including a new 220-case native-mode, geometry, area/delay, and synthetic-queue matrix.
- Result: zero app crashes, ANRs, automation timeouts, or incomplete run markers. One earlier region matrix was superseded after correcting its requested coordinates and is excluded from the count.
- Boundary: logcat proves Java calls and accessible SurfaceFlinger/SDM submissions; it cannot prove that the TCON completed a waveform or that physical ghosting cleared.

## What is established

### Public SDK 1.3.5 is mismatched to this firmware

The SDK expects waveform and update constants on `android.onyx.ViewUpdateHelper`. Those members exist in the firmware bytecode, but Android marks them hidden/blocklisted. Runtime reflection from this ordinary target-SDK-35 app sees no declared fields and returns `NoSuchFieldException` for the names SDK 1.3.5 expects.

Consequences observed on the Palma:

- SDK 1.3.5 initializes all of its cached native waveform/update integers to `0`.
- Its private `UpdateMode` translator maps every enum value, including `GC`, to native integer `0`.
- `EpdController.refreshScreen(view, UpdateMode.GC)`, its region form, and all tested GC-interval wrappers submit waveform `0`; SurfaceFlinger logs `waveform_mode wrong`.
- `EpdController.invalidate(view, UpdateMode.GC)`, `EpdDeviceManager.applyGCUpdate(view)`, explicit SDK invalidation, and `applyGCOnce()` return normally but emit no distinct explicit SurfaceFlinger refresh event in these tests.
- The SDK's `repaintEveryThing(UpdateMode.GC)` wrapper returns normally but emits no refresh event. Static bytecode shows that it depends on hidden `ViewUpdateHelper.repaintEverything(int)`, whose method handle is unavailable to this app.

Therefore, `invoked` from the SDK buttons means only that the Java wrapper returned without throwing. It does not mean that a valid GC request reached the display pipeline.

### The firmware has a working lower-level view refresh route

Read-only firmware disassembly recovered these native values:

```text
EINK_WAVEFORM_MODE_GC16 = 0x02
EINK_WAVEFORM_MODE_AUTO = 0x05
EINK_UPDATE_MODE_FULL = 0x20
EINK_WAIT_MODE_WAIT = 0x40
UI_GC_MODE = 0x62
```

Guarded direct calls to hidden `View.refreshScreen(int)` were deterministic across three mode runs:

| Native value | SurfaceFlinger waveform | Flags |
| --- | ---: | ---: |
| `0x02` | `2` | `0x0` |
| `0x22` | `2` | `0x0` |
| `0x42` | `2` | `0x1000` |
| `0x62` | `2` | `0x1000` |
| `0x05` | `255` (`AUTO`) | `0x0` |

The `0x40` wait bit visibly adds flag `0x1000`. The `0x20` full bit produces no additional difference in the accessible SurfaceFlinger line: `0x02` and `0x22` match, while `0x42` and `0x62` match. A difference may still exist below this logging layer, but none was observed automatically.

The whole-view call logs rectangle `(51,0)-(1597,824)`, not physical display `(0,0)-(1648,824)`. It is therefore a bounded app-view update, not evidence of global physical-panel coverage.

### A true no-view global repaint operation exists but is inaccessible

Firmware bytecode contains `ViewUpdateHelper.repaintEverything(int)`. Unlike the view methods, it sends only a mode to the display service and no rectangle. That makes it the strongest discovered candidate for a privileged global refresh route.

Android's hidden-API enforcement blocks it from this ordinary app, and SDK 1.3.5 cannot cache or invoke it. A BOOX system/privileged component may be able to use it; that is an inference from the access boundary, not an observed system-button trace.

### The two public wait tests are the same underlying path

Static SDK analysis shows `EpdController.waitForUpdateFinished()` delegates to `Device.currentDevice().waitForUpdateFinished()`. They are not independent fences.

In two 16-band runs per label, the first call blocked about 97–119 ms, while nearly all later calls returned in roughly 1–9 ms. App-request-to-SDM latency was often approximately 20–50 ms. The wait can therefore return before the corresponding draw is visible in the accessible SDM stream and must not be treated as a physical E Ink completion fence on this firmware.

### Fast sequences show asynchronous queue lag/coalescing

Ordinary black/white strip transitions generated SDM updates using waveform `4`, update mode `0`, full SDM rectangle `[0 0 1648 824]`, and flags `0x2000`.

At slower cadence, almost every app request had an SDM line before the next request. Compressed tests did not:

- 32 bands in about two seconds: `59/64` or `61/64` requests correlated before the next request in repeated runs.
- Duplicate submissions did not repair the timing: `59/64` for 16 bands and `111/128` for 32 bands.
- Bottom-up direction did not repair it: `30/32` and `60/64`.
- A 30 ms or greater fixed safety pause after each wait produced `32/32` correlations in those individual runs; 0–10 ms did not consistently do so.

The threshold was non-monotonic in some repeats, so these counts are evidence of asynchronous scheduling/queue lag, not a calibrated physical minimum. The user's external-camera observation that entire vertical bands can be skipped is the physical evidence supporting dropped, coalesced, or superseded updates.

### Custom region requests are not trustworthy on this path

Corrected direct region tests submitted the requested arguments but SurfaceFlinger logged surprising transformed rectangles, including zero-area-looking `(51,412)-(1597,412)` for two different left-side requests. Mode `0x42` added the wait flag but produced the same rectangles as `0x02`.

The automatic evidence cannot distinguish a rotation/coordinate translation bug from misleading compositor logging. Custom native regions should not be used for the practical workaround unless external-camera tests first confirm their physical coverage.

The expanded geometry probe made the anomaly more specific. For both `0x02` and `0x42`, changing left, top, or
width changed SurfaceFlinger's transformed fields, but doubling requested height from `100` to `200` did not. This
is either a firmware transform defect or a log representation that omits height; either interpretation makes this
route unsuitable for a trusted coverage scrub.

### Lower-pipeline tracing narrows the skipped-band boundary

Read-only native analysis found that Onyx `EpdcWrapper::mergeByMode` groups queued entries solely by equal mode and
merges their geometry into a bounding region without first testing overlap. SurfaceFlinger invokes this operation
below the immediate per-request log marker. Sequential marker IDs therefore prove receipt, not one physical update
per ID.

Five isolated `atrace` captures added a deeper runtime boundary:

- Refresh-only `ABC` queues produced every immediate SurfaceFlinger marker but no app `transferEpdc` or compositor
  `commitEpdc` during the requests or the following 500 ms quiet hold. The first app transfer occurred only at
  activity completion. This remained true at 200 ms spacing and with the firmware WAIT-bit mode `0x42`.
- A real 16-band scrub at 31 ms per phase produced all 32 app `transferEpdc` events, with a 33.5 ms median gap.
- The known-good 219 ms-per-phase scrub also produced all 32 app transfers, with a 231.8 ms median gap.
- SDM logged the real scrub traffic as full `1648×824`, waveform `4`, flags `0x2000` at both timings.

Thus visually skipped fast bands are not missing Android draws and are not absent from the deepest accessible SDM
submission log. They occur later: downstream queue superseding, hardware-composer/driver submission, TCON
scheduling, or physical waveform execution. No accessible trace marker was established as a real panel-completion
fence. Exact evidence is in [DEEP_PIPELINE_FINDINGS_2026-08-18.md](DEEP_PIPELINE_FINDINGS_2026-08-18.md).

### Thermal data is observable but not causal evidence

During the 24-run queue matrix, `display-usr` rose from 39.2 °C to 42.0 °C; several chassis/SoC zones also rose, while the battery stayed near 25 °C. Android thermal status remained `0` and reported `HAL Ready: false`.

No unattended run controlled panel temperature, ambient temperature, or front-light state. These sensors may not represent E Ink panel temperature. The historical warm/sun/front-light effect remains important physical evidence, but this automation neither confirms nor explains it.

## Working conclusion

The evidence supports two separate problems:

1. **Public API compatibility failure:** current demo dependency `onyxsdk-device:1.3.5` cannot read the hidden firmware constants on this Android build, maps `GC` to zero, and cannot access the global repaint helper.
2. **Confirmed asynchronous/coalescing mechanisms with a remaining lower boundary:** actual framebuffer transitions clear ghosting, refresh-only requests need not trigger immediate composition, same-mode rectangles can be merged below the public API, and fast real draws still reach SDM individually before visual bands are skipped.

The evidence does not yet locate the physical-coverage failure at a single layer. The remaining candidates are dirty-region selection before SurfaceFlinger, tile/framebuffer deduplication below it, queue superseding, or a TCON update policy that omits tiles whose logical pixels are unchanged.

## Most likely resolutions

1. **BOOX firmware/system fix:** make Full Refresh use a privileged true-global repaint, force every panel tile dirty even when framebuffer values are unchanged, and wait for a real completion marker before returning.
2. **BOOX SDK fix:** publish a Palma/firmware-compatible SDK that does not discover required native constants through blocked reflection and exposes a supported full-display refresh plus a reliable completion fence.
3. **Safe app workaround now:** retain the physically verified 16-band black→white scrub at about seven seconds. It creates genuine pixel changes and leaves enough spacing to avoid the observed skipped-band boundary.
4. **Potential faster workaround:** camera-test a changed-strip sequence with an empirical 30–50 ms safety pause after submission. The software logs make this promising, but it must not replace the known-good default until external video shows that every physical band clears consistently.
5. **Waveform-temperature fix:** BOOX should verify temperature compensation and waveform selection for this TCON/firmware. Do not compensate by changing VCOM.

## Still unknown and requiring physical or privileged evidence

- Which rectangle and mode the BOOX system Full Refresh button actually submits.
- Whether blank tiles are removed before SurfaceFlinger, inside BOOX display services, or below the logged SDM request.
- Whether direct native `0x02`, `0x42`, or `0x62` improves physical clearing when combined with changed strips.
- Whether `0x20` has an effect below the accessible logs.
- Whether any privileged driver/TCON marker denotes completed panel drive rather than request acceptance; none was found in unprivileged logcat/atrace.
- How panel temperature independently changes clearing effectiveness.

The decisive next step is synchronized external-camera testing: preserve a known ghost target, run the known-good seven-second scrub and candidate 30–50 ms safety variants, and retain filtered app/SurfaceFlinger/SDM logs from the same continuous recording.
