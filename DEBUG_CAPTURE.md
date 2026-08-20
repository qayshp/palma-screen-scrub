# Palma timing-debug capture

Launch the diagnostic screen instead of the normal auto-scrub:

```powershell
$adb = 'C:\Users\qaysh\Documents\Codex\2026-08-11\palma-screen-scrub\work\android-sdk\platform-tools\adb.exe'
& $adb -s 6B378EF8 shell am start -S -n dev.palma.screenscrub/.DiagnosticsAlias
```

Before a filmed test, begin an ADB log capture in another terminal:

```powershell
& $adb -s 6B378EF8 logcat -c
& $adb -s 6B378EF8 logcat -v threadtime PalmaScreenScrub:I SDM:I EInkHelper:I '*:S' |
  Tee-Object palma-timing.log
```

Record the Palma at 60 fps or faster, with the full display visible. Keep front-light, ambient conditions, rotation, and VCOM unchanged for a single comparison series. The debug label identifies the active band count, dwell, band, and phase.

Tests:

- **Log API inventory** lists public matching BOOX API methods and the concrete `Device.currentDevice()` class in logcat.
- **GC repaint everything** calls `EpdController.repaintEveryThing(UpdateMode.GC)` after rendering white.
- **GC explicit app rect** calls the public rectangle overload with `0,0,testView.width,testView.height`; correlate its `EXPLICIT_RECT` marker with `SDM` log lines.
- **Sweep 4/8/16 bands** runs each count at 125, 150, 180, 200, 220, and 250 ms per black or white phase. It is about one minute.
- **16 bands + controller/device wait** performs a strip change, waits for the app `onDraw`, then invokes the selected wait API on a worker thread. It stops safely if one wait exceeds 2.5 seconds.

The log records `REQUEST`, `DRAW`, `WAIT_BEGIN`, and `WAIT_RETURN` timestamps. They establish app scheduling and API wait duration, but cannot by themselves prove that a physical E Ink waveform has completed; use the camera frames for that comparison.
