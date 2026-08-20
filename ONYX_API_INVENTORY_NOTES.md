# ONYX API inventory notes

Verified locally against the cached public `com.onyx.android.sdk:onyxsdk-device:1.3.5` AAR and runtime reflection on the connected first-generation Palma.

The official Maven metadata was also checked on 2026-08-18. Patch releases `1.3.5.1` and `1.3.5.2` exist, but
public-signature and bytecode comparison found no refresh, repaint, or wait-path changes. `1.3.5.2` still exposes
the same `EpdController.waitForUpdateFinished()`, `refreshScreen`, `refreshScreenRegion`, `repaintEveryThing`, and
`EpdDeviceManager.applyGCUpdate` routes. Upgrading the packaged AAR alone therefore does not provide a new fence or
full-panel coverage primitive.

## New one-shot refresh experiments

All methods below are public. The app invokes them through public reflection so the dependency-free fallback APK remains buildable; none is compiled as a direct Kotlin call.

```text
EpdController.refreshScreen(View, UpdateMode) : void
EpdController.refreshScreenRegion(View, int, int, int, int, UpdateMode) : void
EpdController.applyGCOnce() : void
SDMDevice.applyGCOnce() : void
EpdDeviceManager.refreshScreenWithGCInterval(View, boolean) : void
EpdDeviceManager.refreshScreenWithGCIntervalWithoutRegal(View) : void
EpdDeviceManager.refreshScreenWithGCIntervalWithRegal(View) : void
```

The current diagnostic exposes both `false` and `true` for the boolean GC-interval method. Every call has its own run ID, exact signature, host elapsed time, app/root/physical dimensions, requested rectangle, update mode, system insets, and a configurable final-white quiet hold.

## Policy APIs recorded but disabled

These methods remain inventory-only because their names and official-demo usage indicate update-policy configuration rather than a one-shot refresh:

```text
EpdController.applyAppScopeUpdate(String, boolean, boolean, UpdateMode, int) : boolean
EpdController.applyTransientUpdate(UpdateMode) : boolean
SDMDevice.applyAppScopeUpdate(String, boolean, boolean) : boolean
SDMDevice.applyAppScopeUpdate(String, boolean, boolean, UpdateMode, int) : boolean
SDMDevice.applySysScopeUpdate(UpdateMode, UpdateScheme, int) : boolean
SDMDevice.applyTransientUpdate(UpdateMode) : boolean
```

`UpdateScheme` contains `None`, `SNAPSHOT`, `QUEUE`, and `QUEUE_AND_MERGE`. `UpdateMode` includes `GC`, `GCC`, `DEEP_GC`, `GC4`, `REGAL`, and other fast/animation modes. This app does not invoke these scope methods or select alternative waveform modes.

## Local public-demo evidence

- `OnyxAndroidDemo` clears app-scope state before applying `applyAppScopeUpdate(...)` for X, A2, normal, and DU modes. This supports treating it as mutable app policy.
- `OnyxPenDemo` uses `applyTransientUpdate(UpdateMode.ANIMATION_X)` when entering application fast mode and `clearTransientUpdate(true)` when leaving it.
- The demo uses `repaintEveryThing(UpdateMode.GC)` for its Screen Refresh action.
- No local demo usage was found for `refreshScreen(...)`, `refreshScreenRegion(...)`, `applyGCOnce()`, `applySysScopeUpdate(...)`, or the newly named `refreshScreenWithGCInterval...` family.

No VCOM, front-light, firmware, waveform calibration, or hardware configuration is changed by the inventory or implemented experiments.

## Firmware mapping inventory result — 2026-08-18

The installed BOOX-enabled build now has a read-only firmware-mapping inventory. On the connected Palma it found:

- The SDK classes (`EpdController`, `SDMDevice`, and `UpdateMode`) load from this app's `base.apk`.
- The firmware classes `android.view.View` and `android.onyx.ViewUpdateHelper` load from the boot classloader.
- `ViewUpdateHelper` exists in `framework.jar!classes2.dex` and bytecode inspection confirms that its constants and
  methods exist, but Android marks them hidden/blocklisted. Runtime reflection from this ordinary target-SDK-35 app
  sees no declared members, and every constant name expected by SDK 1.3.5, including `EINK_WAVEFORM_MODE_GC`,
  produces `NoSuchFieldException`.
- SDK 1.3.5 still resolves the hidden firmware targets `View.refreshScreen(int)` and
  `View.refreshScreen(int, int, int, int, int)` correctly.
- Because its expected constants are absent, the SDK's private translator maps every `UpdateMode`, including `GC`,
  to native integer `0`.
- The firmware's read-only getters report view default `5`, global default `5`, and first-draw mode `98`.

The inventory itself issues no refresh and changes no update policy or hardware setting. Its captured output is in
`../firmware-mapping-inventory-2026-08-18-final.txt`.

## Native mapping experiment — 2026-08-18

Offline disassembly of the connected Palma's own `/system/framework/framework.jar` recovered the members hidden
from ordinary runtime reflection:

```text
EINK_WAVEFORM_MODE_GC16 = 0x2
EINK_WAVEFORM_MODE_AUTO = 0x5
EINK_UPDATE_MODE_FULL = 0x20
EINK_WAIT_MODE_WAIT = 0x40
UI_GC_MODE = 0x62
```

The corrected guarded tests remain behind the existing experimental opt-in:

```text
View.refreshScreen(0x62)
View.refreshScreen(0, 0, width, height, 0x62)
```

Both calls were accepted. SurfaceFlinger logged waveform `2`, flags `0x1000`, and no `waveform_mode wrong` error.
By contrast, the SDK 1.3.5 `UpdateMode.GC` wrapper passed `0` and was rejected. Direct mode `5` was accepted as AUTO
and logged as waveform `255`, so it is not GC.

The accepted calls returned in 2–5 ms despite `0x62` containing the firmware wait bit. They therefore do not prove
physical completion. The logged rectangle covers the app window but excludes the 51-pixel status and 99-pixel
navigation insets. External-camera testing remains required to judge ghost removal and physical tile coverage.

Firmware bytecode also exposes `ViewUpdateHelper.repaintEverything(int)`, a more promising no-view full-display
operation. SDK 1.3.5 tries to cache it, but its hidden-API exemption call fails with
`NoSuchMethodException: dalvik.system.VMRuntime.setHiddenApiExemptions`, leaving the method handle unavailable. A
clean SDK `EpdController.repaintEveryThing(UpdateMode.GC)` call then returned normally without any corresponding
SurfaceFlinger refresh event. On this Palma, that public wrapper is therefore a silent no-op rather than a verified
full refresh.

## Unattended matrix result — 2026-08-18

The debug-only ADB automation completed 126 valid app runs without a crash, ANR, timeout, or missing end marker.
Repeated mode tests confirmed that `0x40` adds SurfaceFlinger flag `0x1000`, while `0x20` produces no additional
difference at the accessible log layer. Public SDK GC refresh routes repeatedly submitted invalid waveform `0`;
direct `View.refreshScreen(0x02/0x42/0x62)` repeatedly submitted waveform `2`.

Fast 32-band runs sometimes lacked an SDM entry before the next app request, while individual wait calls generally
returned in only 1–9 ms after the first call. A 30 ms or greater safety pause improved request-to-SDM correlation in
the tested runs, but the timing was non-monotonic and is not a physical completion guarantee. Custom native-region
requests also produced surprising transformed SurfaceFlinger rectangles and are not recommended as a workaround.

See [AUTOMATED_FINDINGS_2026-08-18.md](AUTOMATED_FINDINGS_2026-08-18.md) for exact evidence, interpretation limits,
and likely resolution paths.

## Deep pipeline addendum — 2026-08-18

The 220-case focused matrix confirmed that firmware FULL bit `0x20` disappears at the accessible SurfaceFlinger
output (`0x02 == 0x22`, `0x42 == 0x62`), while WAIT bit `0x40` adds flag `0x1000` without blocking the caller to
physical completion. Every synthetic rectangle submission received an immediate SurfaceFlinger marker, including
zero-delay queues.

Static analysis then located a later coalescing step: Onyx `EpdcWrapper::mergeByMode` merges all queued entries with
the same mode into a bounding region without checking overlap. This occurs below the public API and after the
immediate refresh log. Separate `atrace` captures showed that refresh-only queues did not trigger `transferEpdc`
until activity completion, whereas real black/white framebuffer changes generated one app `transferEpdc` per draw
at both 31 ms and 219 ms cadence. SDM received those real updates at both speeds, so physically skipped fast bands
must occur below SDM. See [DEEP_PIPELINE_FINDINGS_2026-08-18.md](DEEP_PIPELINE_FINDINGS_2026-08-18.md).
