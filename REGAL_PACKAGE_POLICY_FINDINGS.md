# Regal package-policy findings

## Confirmed behavior

| App | Package | App class | Live BOOX modes | Preflight selection |
| --- | --- | --- | --- | --- |
| Microsoft Launcher | `com.microsoft.launcher` | User app | Regal, HD, Speed | Regal |
| Android Settings | `com.android.settings` | System app | HD, Speed | HD |
| Palma Screen Scrub | `dev.palma.screenscrub` | User app | Regal, HD, Speed | Live hierarchy captured |
| Chrome | `com.android.chrome` | User app | Regal, HD, Speed | Live hierarchy captured |

The panel also exposes separate color-style rows (`Standard`, `Deep`, `Custom`) and color-mode rows (`Original`, `Universal`, `Custom`). Those are not refresh-speed choices and are excluded from the Regal/HD/Speed list.

## Framework decision points

Focused SystemUI and framework disassembly identifies the exact visible-list gate:

1. `BaseLoadThemeDataAction` loads the active package/component theme and its `EACRefreshConfig`.
2. It computes `isSupportRegal` as:
   - `refreshConfig.isSupportRegal()`, **or**
   - the package is **not** classified by `ActivityManagerHelper.isOnyxOrSystemApp(pkg)`.
3. `EACViewConfigs.getRefreshConfigIndexes()` uses the full refresh-mode list when that result is true; otherwise it uses `SysUIConfig.getRefreshConfigForSystemApp()`, which omits Regal on this firmware.

The separate `allowUseRegalModePkgSet` machinery exists, but it is not called by this visible-list construction path. Its public helper returns false for ONYX/system packages and otherwise tests the exception set. It may serve another Regal policy path.

This directly explains the observed package difference: ordinary user apps such as Microsoft Launcher, Palma Screen Scrub, and Chrome receive Regal by default; Android Settings is classified as ONYX/system and its active refresh profile does not opt back into Regal.

## Storage/service evidence

- Binder service: `oec_service`
- Binder interface: `android.onyx.optimization.IOECService`
- Device-extra key: `allowUseRegalModePkgSet`
- App configuration: `EACDeviceConfig.appConfigMap`
- Per-app/component structures: `EACAppConfig`, `EACRefreshConfig`
- Validation action: `ValidateAppConfigAction`

No ordinary Android SettingsProvider value changed during the bounded Speed/Regal comparisons. The relevant state is therefore in BOOX's private optimization framework/profile layer, not a normal `settings get` key.

## Confidence and unanswered details

- **High confidence:** the visible mode list is controlled by `refreshConfig.supportRegal || !isOnyxOrSystemApp` in the installed SystemUI.
- **High confidence:** Settings and Launcher expose different live mode lists.
- **High confidence:** Settings reaches the reduced system-app list; its live hierarchy exactly matches that branch.
- **Unknown:** whether BOOX cloud optimization supplied the current Launcher allow-list/profile or it came from firmware defaults.
- **Unknown:** whether ordinary app UID access to the read-only hidden query survives Android 11 hidden-API enforcement; the new runtime inventory records this without changing policy.

Runtime result: `isServiceReady=true`; `allowUseRegalMode()` returned `false` for all three queried packages; `getAllowUseRegalModePkgSet()` was hidden as `NoSuchMethodException`. This is consistent with the helper being separate from the SystemUI list formula and confirms it must not be used as the app's “Regal visible” test.

No profile was added, removed, or modified during this analysis.
