# Palma live experiment commands

Run these from PowerShell. They use only normal ADB shell/logcat access; the app itself does not attempt to bypass Android log restrictions.

```powershell
$adb = 'C:\Users\qaysh\Documents\Codex\2026-08-11\palma-screen-scrub\work\android-sdk\platform-tools\adb.exe'
$serial = '6B378EF8'
& $adb devices -l
```

## Capture

```powershell
& $adb -s $serial logcat -c
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
& $adb -s $serial logcat -v threadtime PalmaScreenScrub:I SDM:I EInkHelper:I '*:S' |
  Tee-Object "palma-$stamp.log"
```

For a wider BOOX/EPD search while a run is active:

```powershell
& $adb -s $serial logcat -v threadtime | Select-String 'PalmaScreenScrub|SDM|EPD|EInk|TCON|waveform|update_to_display'
```

## Launchers

```powershell
# Palma Screen Scrub / Diagnostics
& $adb -s $serial shell am start -S -n dev.palma.screenscrub/.DiagnosticsAlias

# Default package / BOOX quick-action launch (saved default; Quick Scrub initially)
& $adb -s $serial shell am start -S -n dev.palma.screenscrub/.MainActivity

# Quick Scrub (saved configuration)
& $adb -s $serial shell am start -S -n dev.palma.screenscrub/.QuickScrubAlias

# Quick Overlay Scrub (opens the Android overlay-permission page if needed)
& $adb -s $serial shell am start -S -n dev.palma.screenscrub/.QuickOverlayAlias

# Quick 16-Band Diagnostic
& $adb -s $serial shell am start -S -n dev.palma.screenscrub/.Quick16Alias

# Quick Accessibility White-Pixel Refresh (opens Accessibility Settings if needed)
& $adb -s $serial shell am start -S -n dev.palma.screenscrub/.QuickAccessibilityAlias
```

## State inspection

```powershell
& $adb -s $serial shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER |
  Select-String dev.palma.screenscrub
& $adb -s $serial shell dumpsys package dev.palma.screenscrub
& $adb -s $serial shell dumpsys window windows | Select-String 'PalmaScreenScrub|Overlay|Accessibility'
& $adb -s $serial shell dumpsys accessibility
& $adb -s $serial shell dumpsys activity activities | Select-String mResumedActivity
```

Film the entire panel at 60 fps or faster. The app's `RUN_BEGIN`, `REQUEST`, `DRAW`, `WAIT_BEGIN`, and `WAIT_RETURN` events use run IDs and both wall-clock and monotonic times. Camera frames are still the physical-completion evidence; neither Android draw callbacks nor public ONYX wait returns prove that an E Ink waveform finished.
