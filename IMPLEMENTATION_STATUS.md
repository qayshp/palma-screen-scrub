# 2026-08-19 camera-validation result

- The continuous 33-run external-camera recording completed without a crash, ANR, or timeout. Run 32 was software-rejected because the optional accessibility service was not enabled; it is not evidence that the accessibility algorithm executed and failed.
- Run 12 is decisive: the true global `0xff0023/0x62` request reached SDM as a full-panel waveform-2/full update before the `0xff0017` wait began, yet the camera showed no meaningful checker-ghost clearing. The wait returned in 3 ms.
- Runs 13–18 show progressively worse missed bands as commit settle decreases or band count increases. Across the real-wait suite, ioctl-backed waits returned in 1–7 ms and did not provide physical completion.
- Runs 2 and 33 confirm the 16-band × 220 ms scrub remains reliable for the app-owned region. Run 29 confirms the same timing works through the overlay route.
- Runs 23 and 24 appeared clean at duplicate 100 ms and bottom-to-top 140 ms respectively, but each has only one camera sample and is not promoted to the default.
- Camera sidecars and merged software evidence are preserved under `outputs/camera-comprehensive-camera-20260819-185116/camera-review/`.

# 2026-08-19 static-ordering increment

- Version `0.3.1` keeps the 33-run suite and the known-good production scrub unchanged.
- Run 12 now performs global GC `0xff0023/0x62`, waits 50 ms for the scheduled SurfaceFlinger frame to reach HWC/SDM, then invokes global wait `0xff0017` on a worker thread.
- The pure global-GC action remains available in the diagnostics menu, so the ordered test does not remove the original control.
- Device smoke validation observed the full-panel waveform-2/full-update SDM event before the wait began; the wait returned in 6 ms with no crash, ANR, timeout, or unavailable result.
- The parser records global-wait duration, availability, and whether any SDM event was observed between the global call and wait begin.
- Static analysis proves that `0xff0023` schedules a refresh but does not wait, while `0xff0017` directly calls `ioctl(epdc_fd, 0x7010, 0)`. It does not prove what the kernel considers complete.
- No BOOX framework caller was found that automatically pairs `repaintEverything(...)` with `waitForUpdateFinished()`.

# 2026-08-19 real Binder camera-suite increment

- Version `0.3.0` first added direct SurfaceFlinger transactions from the ordinary app process.
- `true-global-gc` sends transaction `0xff0023` with mode `0x62`; device smoke validation produced SDM waveform 2/full update over `0,0,1648,824`.
- `real-wait-scrub` calls transaction `0xff0017` on a worker thread after every black and white View draw plus configurable post-draw settle.
- `overlay-real-wait-scrub` applies the same sequencing to `TYPE_APPLICATION_OVERLAY` and removes the overlay unattended.
- `comprehensive-camera` is a fixed 33-run profile. Global GC is run 12, the first real-wait scrub is run 13, and the final run is the known-good 16-band × 220 ms scrub.
- Real-wait durations and availability are parsed into each run's JSON artifact. The suite continues after every result and never branches on unobservable physical success.
- Prior v0.2.0 APKs and hashes are archived in `releases/archive/2026-08-19-pre-real-binder-suite-v0.2.0/`.
- App smoke tests prove Binder submission and returned waits, not physical completion. External video remains decisive because `0xff0017` returned in approximately 2–21 ms.

# 2026-08-16 diagnostic increment

Implemented on top of the known-good strip renderer:

- Six launcher entries: the default package launch, explicit Diagnostics, Quick Scrub, Quick Overlay Scrub, Quick 16-Band Diagnostic, and Quick Accessibility White-Pixel Refresh. The default package/BOOX quick-action target is configurable and initially Quick Scrub.
- Persistent Quick Scrub settings: bands, black/white dwell, safety delay, final settle, repeat count, duplicate submissions, and direction.
- Known-good configured sweep, fixed 16-band sweep, whole black-to-white, checker-to-inverse-to-white, timing sweep, two public ONYX waits, and the three public GC routes.
- Normal Android `TYPE_APPLICATION_OVERLAY` known-good sweep with Android's standard overlay-permission continuation.
- Optional Android 11 AccessibilityService screenshot experiment: screenshot stays in memory, samples logically white 32-pixel tiles, overlays them black, then removes the overlay after dwell/settle. It fails gracefully if the service, screenshot API, or secure content prevents capture.
- Structured app-owned event log, persisted run summaries, newest-run annotations, in-app live log, history, and clipboard full report.

Safety and limitation notes:

- No VCOM, light, root, firmware, or privileged APIs are used.
- The Experimental APIs preference is default-off. This build inventories reflected runtime API metadata, but deliberately does not invoke undocumented/private methods.
- Newly discovered public refresh methods are separately testable behind that preference: `refreshScreen(GC)`, explicit full-view `refreshScreenRegion(GC)`, controller/device `applyGCOnce()`, and all three GC-interval variants (including both boolean values). Scope-policy methods remain non-invocable.
- GC/refresh experiments log exact signatures, call begin/return timing, requested and actual dimensions, system insets, and a configurable solid-white quiet hold (default 3000 ms).
- The band-count matrix now tests 2, 4, 8, 16, and 32 bands at independently configurable black/white dwell values.
- Android 11 ordinary apps cannot be assumed to read global system logcat. The live log is app-owned; use ADB for BOOX/SDM logs.
- Camera-visible results remain required to establish panel behavior. The Android draw callback and both tested public ONYX wait APIs previously returned far earlier than a physical E Ink update could complete.
- A debug-only, non-launcher ADB automation alias now runs the existing safe tests from explicit intents. It requires `confirmSafe=true`, refuses non-debuggable builds, and leaves all scope-policy APIs disabled.
- The unattended runner completed 126 valid runs with no crash, ANR, timeout, or incomplete marker. It captures raw/filtered logcat, device state, parsed JSON, and Markdown analysis.
- Firmware bytecode confirms that SDK 1.3.5's expected constants and global repaint method exist but are hidden/blocklisted. The SDK maps `GC` to invalid native mode `0`; guarded direct view mode `0x02/0x42/0x62` submissions reach SurfaceFlinger as waveform `2`.
- Custom native regions produce anomalous transformed SurfaceFlinger rectangles, and the public wait path is not a reliable physical fence. Neither is used to replace the known-good default scrub.
- The accessibility mask is deliberately coarse and tile-based; it does not export screenshots and does not alter the underlying app. It needs a device-side enablement test because Accessibility screenshot support can be disabled or limited by firmware/security policy.

Still intentionally deferred:

- Per-test custom checker cell size and accessibility threshold/tile inputs.
- Overlay wait-strategy variants and an experimentally proven fastest-overlay configuration.
- Invocation UI for private/reflected ONYX methods. Any addition needs a separately reviewed safety allowlist.
- History comparison, per-run deletion, and JSON-file export. The internal history JSON and full clipboard report are already available for diagnosis.
