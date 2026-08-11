# Build validation

Validated on 2026-08-11 with a temporary Temurin 17.0.20 JDK, Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.10.2, Android Gradle Plugin 8.7.3, and Kotlin 2.0.21.

| Build | Command | Result | SHA-256 |
| --- | --- | --- | --- |
| Dependency-free fallback | `gradlew.bat --offline --no-daemon --console=plain assembleDebug` | Passed | `A5EB8D99E0CE7CB21F160D8E46A859BF967E524F0F463F287821C73FE0AE7F88` |
| Optional BOOX SDK | `gradlew.bat --offline --no-daemon --console=plain assembleDebug -PincludeBooxSdk=true` | Passed | `4DDD62583C09BA003FA9AB86862BCD5795AB059504ED4FAB574580CEACA52D34` |

`aapt dump badging` reports package `dev.palma.screenscrub`, `minSdkVersion` 26, and `targetSdkVersion` 35 for both. `aapt dump permissions` reports no declared permissions for either APK.

No Palma was attached to this build environment. The on-device observations in the README remain required to validate BOOX waveform behavior, physical-panel coverage, system Full Refresh behavior, and the two proprietary SDK calls on the stated firmware.
