# Regal user-space path

## Conclusion

The safest reproducible route currently available is BOOX's own `BOOX EinkWise` SystemUI panel. The camera runner opens the real panel, selects `Regal`, verifies the selected accessibility node, uses the real circular-arrow Full Refresh control, and restores the preflight mode.

No direct TCON, EBC device-node, VCOM, firmware, hidden-API-policy, or low-level profile write is used.

## Discovered framework route

- BOOX registers an optimization Binder service named `oec_service`.
- `android.onyx.optimization.EInkHelper.allowUseRegalMode(Context, String)` is an exception allow-list query for ordinary packages. It returns `false` for packages treated as ONYX/system and otherwise tests a package allow-list.
- The allow-list key is `allowUseRegalModePkgSet`, read from `EACDeviceExtraConfig`, with a built-in `EACConfig` fallback.
- `IOECService` contains `addAllowUseRegalModePackage(List)` and `removeAllowUseRegalModePackage(List)` transactions.
- These are hidden framework `test-api` interfaces, not current public ONYX SDK 1.3.5 application APIs.

The app contains a read-only `regal-policy-inventory` experiment. It only attempts public reflection and records whether the current app UID can query the policy. It never invokes the add/remove methods.

The ordinary-app runtime probe confirmed `oec_service` is ready and returned `false` for Launcher, Settings, and Palma Screen Scrub. Android hidden-API filtering also made the non-public allow-set method appear absent. SystemUI static analysis proves this method is not the visible mode-list decision. The actual list uses the active component profile and ONYX/system classification described in `REGAL_PACKAGE_POLICY_FINDINGS.md`.

## Permissions and suitability

| Route | Permission | Current status | Suitability |
| --- | --- | --- | --- |
| BOOX SystemUI panel | User-visible UI; ADB UI automation for the unattended suite | Proven selector and restoration path | Diagnostic suite |
| Accessibility UI automation | User-enabled accessibility permission | Optional fallback concept; permission currently disabled | Experimental only |
| `oec_service` hidden Binder | Hidden framework/service access | Read-only runtime probe required; mutation intentionally not tested | Not production-safe |
| Direct TCON/EBC state | Privileged/native access | Intentionally not invoked | Prohibited |

## Restoration

The unattended suite captures both Microsoft Launcher and Settings selections before starting and restores those exact values in a `finally` block. On the 2026-08-20 preflight they were:

- Microsoft Launcher: `Regal`
- Settings: `HD`

Any restoration failure is recorded and causes a non-zero suite result.

## Risks and limits

- BOOX's mode state is package/component dependent; selecting Regal for Launcher does not establish Regal for every app.
- Software logs do not prove physical pigment movement or ghost removal.
- Hidden service method names and behavior are firmware-private and may change.
- The current production Quick Scrub remains `16 bands × 220 ms/phase` and does not require Regal or Accessibility.
