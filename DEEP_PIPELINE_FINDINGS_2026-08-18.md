# Deep Palma display-pipeline findings — 2026-08-18

This addendum records unattended runtime tracing and read-only static analysis of the connected first-generation
Palma. It does not claim visual verification. No VCOM, front-light, firmware, root, system policy, or hardware
setting was changed.

## Validation set

- `220/220` focused automation runs completed with no crash, ANR, timeout, wrong waveform, or missing immediate
  SurfaceFlinger request marker.
- The matrix covered native values `0x02`, `0x22`, `0x42`, and `0x62`; region divisors `1`, `2`, `4`, `8`, `16`,
  and `32`; delays from `0` through `200` ms; and `ABC`, `ABA`, overlap, large→small, small→large, and identical
  rectangle queues.
- Separate `atrace` captures compared refresh-only queues at `0` and `200` ms, the `0x42` WAIT-bit form, and real
  16-band framebuffer scrubs at `31` and `219` ms per black/white phase.
- Raw results are in `../unattended-focused-deep-20260818` and the five `../unattended-atrace*-20260818`
  directories.

## Request modes and flags

| Requested value | SurfaceFlinger waveform | SurfaceFlinger flags | Observed distinction |
| --- | ---: | ---: | --- |
| `0x02` | `2` | `0x0` | Base GC16 waveform request |
| `0x22` | `2` | `0x0` | No visible effect from firmware FULL bit `0x20` |
| `0x42` | `2` | `0x1000` | Firmware WAIT bit `0x40` becomes lower-layer flag `0x1000` |
| `0x62` | `2` | `0x1000` | Same accessible output as `0x42` |

All four calls normally returned in roughly `1–5` ms. Therefore flag `0x1000` does not make the Java/Binder call a
physical completion fence. The meaning of that lower-layer flag remains proprietary; it must not be confused with
the separate framework waveform constant whose value is also `0x1000`.

SDM's repeated `flags=2000` means hexadecimal `0x2000`. It accompanies waveform `4` full-screen fast composition
traffic. The value is consistent with `EPDC_FLAG_USE_DITHERING_Y1` in the NXP EPDC ABI lineage, but its exact BOOX
meaning is not established by this device evidence.

## Geometry anomaly

`View.refreshScreen(left, top, width, height, mode)` adds the View's on-screen origin, then the firmware helper writes
the four integers unchanged to the SurfaceFlinger transaction. The compositor logs transformed fields because the
physical display pipeline is rotated relative to the portrait app.

The controlled geometry matrix found that, for both `0x02` and `0x42`:

- changing left, top, or width changed the logged transformed fields;
- changing height from `100` to `200` did not change them;
- a requested full app rectangle still logged an app-window-bounded result rather than physical
  `(0,0)-(1648,824)`.

This is either a firmware transform defect or a log representation that omits the requested height. In either case,
the direct region API is not trustworthy as a physical-coverage workaround without camera evidence.

## Queue implementation below the public API

The Palma's `libsurfaceflinger.so` imports the Onyx `EpdcWrapper` list operations from `libgui.so`. Static ARM64
analysis of `EpdcWrapper::mergeByMode(input, output)` found:

1. each entry contains four geometry integers plus one mode integer;
2. entries are grouped solely by equal mode;
3. matching entries are merged using minima/maxima into a bounding region;
4. no overlap test occurs before the merge.

Two SurfaceFlinger call sites invoke `mergeByMode`. This proves that same-mode requests can be coalesced below the
public Java API and after the immediate `SurfaceFlinger: refresh screen ... marker N` line. Consequently, sequential
marker numbers establish request receipt, not one independently driven panel update per marker.

The framework's global `repaintEverything(mode)` route remains distinct: it sends only the mode through transaction
`0x00ff0023`, while region refresh uses transaction `0x00ff0001` and sends four geometry integers plus mode. Public
SDK patch releases through `1.3.5.2` do not add a new completion or full-coverage API.

## Trace comparison

### Refresh-only queues

An 18-request `ABC` queue at `0` ms spacing submitted all requests in about `80` ms. Every call had an immediate
SurfaceFlinger marker, but no app `transferEpdc` or SurfaceFlinger `commitEpdc` occurred during the request sequence
or the following 500 ms quiet hold. The first app `transferEpdc` began about `556` ms after the final request, at
activity completion. The same behavior occurred at `200` ms spacing and with native value `0x42`.

This explains why a refresh-only API button can say `invoked` yet have little visible effect: a refresh request does
not itself guarantee a new composed framebuffer or immediate panel commit.

### Real framebuffer-changing scrubs

| Scrub | App draw requests | App `transferEpdc` | Transfer gap median | SDM during active interval | SDM form |
| --- | ---: | ---: | ---: | ---: | --- |
| 16 bands × black/white at `31` ms | 32 | 32 | `33.5` ms | 35 including nearby composition traffic | waveform `4`, full `1648×824`, flags `0x2000` |
| 16 bands × black/white at `219` ms | 32 | 32 | `231.8` ms | 37 including nearby composition traffic | waveform `4`, full `1648×824`, flags `0x2000` |

Both timings therefore reach the deepest accessible unprivileged logs once per framebuffer change. The user's
external-camera observation that fast runs skip whole physical bands must occur after these points: in later
SurfaceFlinger/EPDC queue handling, hardware-composer/driver submission, TCON scheduling, or physical waveform
execution. `atrace` exposes `transferEpdc`, `commitEpdc`, and `clearEpdcList`, but no trustworthy per-update
panel-completion event.

## Current conclusion

The strongest combined model is:

1. unchanged white content can be omitted because physical ghosting is absent from framebuffer state;
2. refresh-only API calls are asynchronous and need not trigger immediate composition;
3. same-mode update rectangles can be merged below the API;
4. even real 31 ms framebuffer changes reach SDM individually, so visually skipped bands are dropped, superseded,
   or not completed below SDM rather than being missed Android draws;
5. the known-good approximately seven-second scrub works by creating real black→white changes and spacing them far
   enough apart for this device's lower pipeline.

The safe resolution remains a BOOX firmware fix: privileged true-global coverage, no unchanged-tile suppression for
Full Refresh, and a real driver/TCON completion fence. Until then, keep the physically verified 16-band, roughly
seven-second scrub as the default. Faster timings require synchronized external-camera validation and should not
replace it based only on software logs.
