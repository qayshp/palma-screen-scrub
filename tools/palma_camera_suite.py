#!/usr/bin/env python3
"""Run synchronized, unattended external-camera validation on a BOOX Palma."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from palma_epd_test import Adb, RunSpec, capture_state, connected_serials, resolve_adb, run_one


PACKAGE = "dev.palma.screenscrub"


def camera_case(spec: RunSpec, detail: str, quiet_ms: int = 5_000, title: str = "") -> RunSpec:
    return dataclasses.replace(
        spec,
        quiet_ms=quiet_ms,
        camera_mode=True,
        camera_detail=detail,
        camera_title=title,
    )


def smoke_cases() -> list[RunSpec]:
    return [
        camera_case(RunSpec("white-only", "negative-white-only"), "NEGATIVE CONTROL: unchanged white"),
        camera_case(RunSpec("gc-repaint-everything", "sdk-repaint-gc"), "SDK repaintEveryThing(GC)"),
        camera_case(
            RunSpec("native-refresh-custom", "native-view-0x02", native_mode=0x02),
            "native app-view GC16 mode 0x02",
        ),
        camera_case(
            RunSpec(
                "native-region-sequence",
                "anti-merge-02-22",
                native_mode=0x02,
                alternate_native_mode=0x22,
                native_sequence="abc",
                sequence_delay_ms=0,
                sequence_count=18,
                region_divisor=4,
            ),
            "alternate 0x02 / 0x22 anti-merge",
        ),
        camera_case(RunSpec("checker-inverse-white", "checker-inverse-white", black_ms=400), "checker then inverse then white"),
        camera_case(RunSpec("scrub", "scrub-16x140", bands=16, black_ms=140, white_ms=140), "16 bands at 140 ms per phase"),
        camera_case(RunSpec("scrub", "positive-known-good-16x220", bands=16, black_ms=220, white_ms=220), "POSITIVE CONTROL: known-good 7 second scrub"),
    ]


def core_cases() -> list[RunSpec]:
    cases = [
        camera_case(RunSpec("white-only", "negative-white-only"), "NEGATIVE CONTROL: unchanged white"),
        camera_case(RunSpec("normal-invalidate-control", "normal-invalidate"), "normal Android invalidate"),
        camera_case(RunSpec("gc-repaint-everything", "sdk-repaint-gc"), "SDK repaintEveryThing(GC)"),
    ]
    for mode in (0x02, 0x22, 0x42, 0x62):
        cases.append(
            camera_case(
                RunSpec("native-refresh-custom", f"native-view-0x{mode:02x}", native_mode=mode),
                f"native app-view GC16 mode 0x{mode:02x}",
            )
        )
    for first, second in ((0x02, None), (0x02, 0x22), (0x42, None), (0x42, 0x62)):
        suffix = f"{first:02x}" if second is None else f"{first:02x}-{second:02x}"
        detail = f"same mode 0x{first:02x}" if second is None else f"alternate 0x{first:02x} / 0x{second:02x} anti-merge"
        cases.append(
            camera_case(
                RunSpec(
                    "native-region-sequence",
                    f"anti-merge-{suffix}",
                    native_mode=first,
                    alternate_native_mode=second,
                    native_sequence="abc",
                    sequence_delay_ms=0,
                    sequence_count=18,
                    region_divisor=4,
                ),
                detail,
            )
        )
    cases.extend(
        [
            camera_case(RunSpec("whole-black-white", "whole-black-white", black_ms=500, white_ms=500), "whole frame black then white"),
            camera_case(RunSpec("checker-inverse-white", "checker-inverse-white", black_ms=400), "checker then inverse then white"),
        ]
    )
    for dwell in (220, 180, 160, 140, 120, 100):
        cases.append(
            camera_case(
                RunSpec("scrub", f"timing-16x{dwell}", bands=16, black_ms=dwell, white_ms=dwell),
                f"timing matrix: 16 bands at {dwell} ms per phase",
            )
        )
    for bands, dwell in ((4, 250), (8, 125), (16, 63), (32, 31)):
        cases.append(
            camera_case(
                RunSpec("scrub", f"fixed-total-{bands}x{dwell}", bands=bands, black_ms=dwell, white_ms=dwell),
                f"band matrix: {bands} bands, about 2 seconds total",
            )
        )
    cases.extend(
        [
            camera_case(RunSpec("wait-controller", "wait-controller-plus-30", bands=16, black_ms=0, white_ms=0, safety_ms=30), "controller wait plus 30 ms"),
            camera_case(RunSpec("wait-device", "wait-device-plus-30", bands=16, black_ms=0, white_ms=0, safety_ms=30), "device wait plus 30 ms"),
            camera_case(RunSpec("scrub", "duplicate-16x100", bands=16, black_ms=100, white_ms=100, duplicates=2), "duplicate each 100 ms submission"),
            camera_case(RunSpec("scrub", "bottom-up-16x140", bands=16, black_ms=140, white_ms=140, bottom_to_top=True), "direction control: bottom to top"),
            camera_case(RunSpec("overlay-scrub", "overlay-16x220", bands=16, black_ms=220, white_ms=220), "full overlay known-good scrub"),
            camera_case(RunSpec("accessibility-white", "accessibility-white", black_ms=500, white_ms=500), "accessibility logical-white pixel mask"),
            camera_case(RunSpec("scrub", "positive-known-good-16x220", bands=16, black_ms=220, white_ms=220), "POSITIVE CONTROL: known-good 7 second scrub"),
        ]
    )
    return cases


def full_cases() -> list[RunSpec]:
    cases = core_cases()
    insertion: list[RunSpec] = []
    for mode_pair in ((0x02, None), (0x02, 0x22), (0x42, None), (0x42, 0x62)):
        first, second = mode_pair
        for delay in (0, 20, 50, 100, 200):
            suffix = f"{first:02x}" if second is None else f"{first:02x}-{second:02x}"
            insertion.append(
                camera_case(
                    RunSpec(
                        "native-region-sequence",
                        f"mode-{suffix}-delay-{delay}",
                        native_mode=first,
                        alternate_native_mode=second,
                        native_sequence="abc",
                        sequence_delay_ms=delay,
                        sequence_count=18,
                        region_divisor=4,
                    ),
                    f"queue test {suffix}, {delay} ms between requests",
                )
            )
    for sequence in ("aba", "overlap", "large-small", "small-large", "same"):
        insertion.append(
            camera_case(
                RunSpec(
                    "native-region-sequence",
                    f"sequence-{sequence}-0ms",
                    native_mode=0x02,
                    native_sequence=sequence,
                    sequence_delay_ms=0,
                    sequence_count=18,
                    region_divisor=4,
                ),
                f"queue geometry control: {sequence}",
            )
        )
    for dwell in (200, 190, 180, 170, 160, 150, 140, 130, 120, 110, 100, 90, 80):
        for repeat in range(1, 4):
            insertion.append(
                camera_case(
                    RunSpec("scrub", f"reliability-16x{dwell}-r{repeat}", bands=16, black_ms=dwell, white_ms=dwell),
                    f"physical reliability repeat {repeat}/3: 16 bands at {dwell} ms",
                )
            )
    return cases[:-1] + insertion + cases[-1:]


def comprehensive_cases() -> list[RunSpec]:
    title = "RUN {run}"
    cases = [
        camera_case(
            RunSpec("white-only", "no-clean-ghost-control"),
            "NO-CLEAN GHOST CONTROL | WHITE ONLY | NO REFRESH CALL",
            title=title,
        ),
        camera_case(
            RunSpec("scrub", "known-good-opening-16x220", bands=16, black_ms=220, white_ms=220),
            "KNOWN-GOOD CONTROL | 16 BANDS | 220 MS PER PHASE",
            title=title,
        ),
        camera_case(
            RunSpec("scrub", "deliberately-fast-16x31", bands=16, black_ms=31, white_ms=31),
            "DELIBERATELY FAST SCRUB | 16 BANDS | 31 MS PER PHASE",
            title=title,
        ),
        camera_case(
            RunSpec("normal-invalidate-control", "normal-invalidate"),
            "ANDROID CONTROL | NORMAL INVALIDATE",
            title=title,
        ),
        camera_case(
            RunSpec("controller-invalidate", "sdk-controller-invalidate-gc"),
            "SDK CONTROL | EpdController.invalidate | GC",
            title=title,
        ),
        camera_case(
            RunSpec("device-manager", "sdk-device-manager-gc"),
            "SDK CONTROL | EpdDeviceManager.applyGCUpdate",
            title=title,
        ),
        camera_case(
            RunSpec("gc-repaint-everything", "sdk-repaint-everything-gc"),
            "SDK CONTROL | repaintEveryThing(GC)",
            title=title,
        ),
        camera_case(
            RunSpec("gc-explicit-app-rect", "sdk-explicit-app-rect-gc"),
            "SDK CONTROL | EXPLICIT APP RECT | GC",
            title=title,
        ),
        camera_case(
            RunSpec("native-refresh-custom", "native-view-0x02", native_mode=0x02),
            "NATIVE VIEW CONTROL | MODE 0x02",
            title=title,
        ),
        camera_case(
            RunSpec("native-refresh-custom", "native-view-0x42", native_mode=0x42),
            "NATIVE VIEW CONTROL | MODE 0x42 WAIT BIT",
            title=title,
        ),
        camera_case(
            RunSpec("native-refresh-custom", "native-view-0x62", native_mode=0x62),
            "NATIVE VIEW CONTROL | MODE 0x62 FULL + WAIT",
            title=title,
        ),
        camera_case(
            RunSpec("true-global-gc-wait", "true-global-gc-wait", black_ms=50),
            "TRUE GLOBAL GC | SF 0xff0023 | MODE 0x62 | THEN SF WAIT 0xff0017 | 50 MS COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("real-wait-scrub", "real-wait-16-settle30", bands=16, black_ms=30, white_ms=30),
            "REAL COMPLETION WAIT SCRUB | SF WAIT 0xff0017 | 16 BANDS | 30 MS COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("real-wait-scrub", "real-wait-16-settle10", bands=16, black_ms=10, white_ms=10),
            "REAL WAIT TIMING | SF WAIT 0xff0017 | 16 BANDS | 10 MS COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("real-wait-scrub", "real-wait-16-settle0", bands=16, black_ms=0, white_ms=0),
            "REAL WAIT TIMING | SF WAIT 0xff0017 | 16 BANDS | ZERO COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("real-wait-scrub", "real-wait-32-settle10", bands=32, black_ms=10, white_ms=10),
            "REAL WAIT BAND TEST | SF WAIT 0xff0017 | 32 BANDS | 10 MS COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("real-wait-scrub", "real-wait-32-settle0", bands=32, black_ms=0, white_ms=0),
            "REAL WAIT BAND TEST | SF WAIT 0xff0017 | 32 BANDS | ZERO COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("real-wait-scrub", "real-wait-64-settle0", bands=64, black_ms=0, white_ms=0),
            "REAL WAIT AGGRESSIVE | SF WAIT 0xff0017 | 64 BANDS | ZERO COMMIT SETTLE",
            title=title,
        ),
        camera_case(
            RunSpec("whole-black-white", "whole-black-white", black_ms=500, white_ms=500),
            "WHOLE-FRAME CONTROL | BLACK THEN WHITE",
            title=title,
        ),
        camera_case(
            RunSpec("checker-inverse-white", "checker-inverse-white", black_ms=400),
            "CHECKER CONTROL | CHECKER + INVERSE + WHITE",
            title=title,
        ),
        camera_case(
            RunSpec("wait-controller", "public-wait-controller", bands=16, black_ms=0, white_ms=0, safety_ms=30),
            "PUBLIC WAIT CONTROL | EpdController | 16 BANDS",
            title=title,
        ),
        camera_case(
            RunSpec("wait-device", "public-wait-device", bands=16, black_ms=0, white_ms=0, safety_ms=30),
            "PUBLIC WAIT CONTROL | Device.currentDevice | 16 BANDS",
            title=title,
        ),
        camera_case(
            RunSpec("scrub", "duplicate-16x100", bands=16, black_ms=100, white_ms=100, duplicates=2),
            "DUPLICATE SUBMISSION CONTROL | 16 BANDS | 100 MS",
            title=title,
        ),
        camera_case(
            RunSpec("scrub", "bottom-up-16x140", bands=16, black_ms=140, white_ms=140, bottom_to_top=True),
            "DIRECTION CONTROL | BOTTOM TO TOP | 16 BANDS",
            title=title,
        ),
    ]
    for first, second, label in (
        (0x02, None, "same-0x02"),
        (0x02, 0x22, "alternate-0x02-0x22"),
        (0x42, None, "same-0x42"),
        (0x42, 0x62, "alternate-0x42-0x62"),
    ):
        detail = (
            f"ANTI-MERGE CONTROL | SAME MODE 0x{first:02X}"
            if second is None
            else f"ANTI-MERGE TEST | ALTERNATE 0x{first:02X} / 0x{second:02X}"
        )
        cases.append(
            camera_case(
                RunSpec(
                    "native-region-sequence",
                    f"anti-merge-{label}",
                    native_mode=first,
                    alternate_native_mode=second,
                    native_sequence="abc",
                    sequence_delay_ms=0,
                    sequence_count=18,
                    region_divisor=4,
                ),
                detail,
                title=title,
            )
        )
    cases.extend(
        [
            camera_case(
                RunSpec("overlay-scrub", "overlay-known-good-16x220", bands=16, black_ms=220, white_ms=220),
                "OVERLAY CONTROL | KNOWN-GOOD | 16 BANDS | 220 MS",
                title=title,
            ),
            camera_case(
                RunSpec("overlay-scrub", "overlay-fast-16x31", bands=16, black_ms=31, white_ms=31),
                "OVERLAY FAST SCRUB | 16 BANDS | 31 MS",
                title=title,
            ),
            camera_case(
                RunSpec("overlay-real-wait-scrub", "overlay-real-wait-16-settle0", bands=16, black_ms=0, white_ms=0),
                "OVERLAY REAL WAIT | SF WAIT 0xff0017 | 16 BANDS | ZERO COMMIT SETTLE",
                title=title,
            ),
            camera_case(
                RunSpec("accessibility-white", "accessibility-white", black_ms=500, white_ms=500),
                "ACCESSIBILITY CONTROL | LOGICAL WHITE PIXEL MASK",
                title=title,
            ),
            camera_case(
                RunSpec("scrub", "final-known-good-16x220", bands=16, black_ms=220, white_ms=220),
                "FINAL SANITY CHECK | KNOWN-GOOD | 16 BANDS | 220 MS",
                title=title,
            ),
        ]
    )
    return cases


def next_phase_overlay_cases() -> list[RunSpec]:
    title = "RUN {run} / {total}"
    cases: list[RunSpec] = []

    def overlay(
        label: str,
        bands: int,
        phase_ms: int,
        *,
        duplicates: int = 1,
        reverse: bool = False,
        presentation: str = "t0",
        detail: str,
    ) -> None:
        cases.append(
            camera_case(
                RunSpec(
                    "overlay-scrub",
                    label,
                    bands=bands,
                    black_ms=phase_ms,
                    white_ms=phase_ms,
                    duplicates=duplicates,
                    bottom_to_top=reverse,
                    overlay_presentation=presentation,
                ),
                detail,
                title=title,
            )
        )

    overlay("fast-01-control-16x220", 16, 220, detail="FAST MATRIX CONTROL | NORMAL | 16 BANDS | 220 MS")
    overlay("fast-02-failure-16x31", 16, 31, detail="KNOWN FAST FAILURE | NORMAL | 16 BANDS | 31 MS")
    for bands in (16, 12, 8):
        for repeat in range(1, 4):
            overlay(
                f"fast-dup-{bands}x100-r{repeat}",
                bands,
                100,
                duplicates=2,
                detail=f"OVERLAY DUPLICATE | {bands} BANDS | 100 MS | REPEAT {repeat}/3",
            )
    for phase_ms in (80, 60):
        for bands in (16, 12, 8):
            overlay(
                f"fast-dup-{bands}x{phase_ms}",
                bands,
                phase_ms,
                duplicates=2,
                detail=f"OVERLAY DUPLICATE | {bands} BANDS | {phase_ms} MS",
            )
    for repeat in range(1, 4):
        overlay(
            f"fast-reverse-16x140-r{repeat}",
            16,
            140,
            reverse=True,
            detail=f"OVERLAY REVERSE | 16 BANDS | 140 MS | REPEAT {repeat}/3",
        )
    for bands, phase_ms in ((12, 120), (8, 120), (12, 100), (8, 100)):
        overlay(
            f"fast-reverse-{bands}x{phase_ms}",
            bands,
            phase_ms,
            reverse=True,
            detail=f"OVERLAY REVERSE | {bands} BANDS | {phase_ms} MS",
        )
    overlay("fast-25-final-control-16x220", 16, 220, detail="FINAL FAST-MATRIX CONTROL | 16 BANDS | 220 MS")

    overlay("transparent-26-t0-16x220", 16, 220, presentation="t0", detail="T0 OPAQUE CONTROL | 16 BANDS | 220 MS")
    for repeat in (1, 2):
        overlay(
            f"transparent-t1-16x220-r{repeat}",
            16,
            220,
            presentation="t1",
            detail=f"T1 PROGRESSIVE TRANSPARENT | 16 BANDS | 220 MS | REPEAT {repeat}/2",
        )
    for repeat in (1, 2):
        overlay(
            f"transparent-t2-16x220-r{repeat}",
            16,
            220,
            presentation="t2",
            detail=f"T2 PIPELINED TRANSPARENT | 16 BANDS | 220 MS | REPEAT {repeat}/2",
        )
    for bands in (16, 12, 8):
        for presentation in ("t1", "t2"):
            overlay(
                f"transparent-{presentation}-{bands}x100-duplicate",
                bands,
                100,
                duplicates=2,
                presentation=presentation,
                detail=f"{presentation.upper()} + DUPLICATE | {bands} BANDS | 100 MS",
            )
    overlay("transparent-37-final-t0-16x220", 16, 220, presentation="t0", detail="FINAL KNOWN-GOOD T0 CONTROL | 16 BANDS | 220 MS")
    return cases


PROFILES = {
    "smoke-camera": smoke_cases,
    "core-camera": core_cases,
    "full-camera": full_cases,
    "comprehensive-camera": comprehensive_cases,
    "next-phase-overlay": next_phase_overlay_cases,
}


def manifest_markdown(metadata: dict, runs: list[dict]) -> str:
    rows = [
        "# Palma camera-suite manifest",
        "",
        f"- Profile: `{metadata['profile']}`",
        f"- Device: `{metadata['serial']}`",
        f"- Started: `{metadata['startedAt']}`",
        f"- APK SHA-256: `{metadata['apkSha256']}`",
        "- Each run displayed its visible ID before the standardized checker precondition.",
        "",
        "| Visible run | Test | Result | Duration | Raw log | Parsed record |",
        "| --- | --- | --- | ---: | --- | --- |",
    ]
    for run in runs:
        result = (
            "TIMEOUT"
            if run["timedOut"]
            else "CRASH/ANR"
            if run["fatalOrAnr"]
            else "WAIT UNAVAILABLE"
            if run.get("realWaitUnavailableCount", 0)
            else "SKIPPED/UNAVAILABLE"
            if run.get("automationRejected")
            else "completed"
        )
        rows.append(
            f"| `{run['visibleRunId']}` | {run['label']} | {result} | {run['hostDurationSeconds']} s | "
            f"`{run['artifacts']['rawLogcat']}` | `{run['artifacts']['json']}` |"
        )
    rows.extend(
        [
            "",
            "## Evidence boundary",
            "",
            "- The external video is the evidence for physical pigment/ghost behavior.",
            "- Logs establish app, SurfaceFlinger, and readable SDM activity only.",
            "- Match the slate ID in the video to `visibleRunId` here.",
        ]
    )
    return "\n".join(rows) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=tuple(PROFILES), default="comprehensive-camera")
    parser.add_argument("--adb", help="Path to adb.exe")
    parser.add_argument("--serial", help="ADB serial; required with multiple devices")
    parser.add_argument("--output", type=Path, help="Output directory")
    parser.add_argument("--resume", action="store_true", help="Resume the same output directory")
    parser.add_argument("--limit", type=int, help="Run only the first N remaining tests")
    args = parser.parse_args()

    adb_path = resolve_adb(args.adb)
    serials = connected_serials(adb_path)
    if args.serial:
        if args.serial not in serials:
            raise RuntimeError(f"requested serial {args.serial} is not connected: {serials}")
        serial = args.serial
    elif len(serials) == 1:
        serial = serials[0]
    else:
        raise RuntimeError(f"expected one connected ADB device, found {serials}")
    adb = Adb(adb_path, serial)

    package_info = adb.run("shell", "dumpsys", "package", PACKAGE, check=False)
    if "DEBUGGABLE" not in package_info:
        raise RuntimeError("the installed Palma Screen Scrub package is missing or is not a debug build")

    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    default_output = Path(__file__).resolve().parents[2] / f"camera-{args.profile}-{timestamp}"
    output_dir = (args.output or default_output).resolve()
    output_dir.mkdir(parents=True, exist_ok=args.resume)
    apk_path = Path(__file__).resolve().parents[1] / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_hash = hashlib.sha256(apk_path.read_bytes()).hexdigest().upper() if apk_path.is_file() else "unknown"
    package_path_output = adb.run("shell", "pm", "path", PACKAGE, check=False).strip()
    installed_path = package_path_output.removeprefix("package:")
    installed_hash_output = adb.run("shell", "sha256sum", installed_path, check=False).strip()
    installed_hash = installed_hash_output.split()[0].upper() if installed_hash_output else "unknown"
    if apk_hash != "unknown" and installed_hash != "unknown" and apk_hash != installed_hash:
        raise RuntimeError(
            "installed APK does not match app/build/outputs/apk/debug/app-debug.apk; "
            f"local={apk_hash} installed={installed_hash}"
        )
    metadata = {
        "schemaVersion": 1,
        "profile": args.profile,
        "serial": serial,
        "startedAt": dt.datetime.now().astimezone().isoformat(),
        "apkSha256": apk_hash,
        "installedApkSha256": installed_hash,
        "standardPrecondition": {"checkerMs": 600, "inverseCheckerMs": 600, "whiteBaselineMs": 800},
        "finalWhiteHoldMs": 5_000,
        "physicalEvidence": "EXTERNAL CAMERA REQUIRED",
    }
    metadata_path = output_dir / "metadata.json"
    if not args.resume or not metadata_path.exists():
        metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        capture_state(adb, output_dir, "before")

    manifest_path = output_dir / "manifest.json"
    records = json.loads(manifest_path.read_text(encoding="utf-8")) if args.resume and manifest_path.exists() else []
    records = [record for record in records if not record["timedOut"] and not record["fatalOrAnr"]]
    if args.resume and manifest_path.exists():
        manifest_path.write_text(json.dumps(records, indent=2), encoding="utf-8")
    completed_labels = {record["label"] for record in records}
    base_specs = PROFILES[args.profile]()
    total = len(base_specs)
    pending = [spec for spec in base_specs if spec.label not in completed_labels]
    if args.limit is not None:
        pending = pending[: max(0, args.limit)]

    suite_log = (output_dir / "suite-logcat.txt").open("a" if args.resume else "w", encoding="utf-8")
    log_process = subprocess.Popen(
        [str(adb_path), "-s", serial, "logcat", "-b", "all", "-v", "threadtime"],
        stdout=suite_log,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    )
    try:
        for spec in pending:
            original_index = next(index for index, item in enumerate(base_specs, 1) if item.label == spec.label)
            visible_id = f"{original_index:03d}/{total:03d}-{spec.label}"
            camera_spec = dataclasses.replace(
                spec,
                camera_index=original_index,
                camera_total=total,
                camera_profile=args.profile,
                camera_title=spec.camera_title.format(run=original_index, total=total),
            )
            print(f"[{original_index}/{total}] {spec.label}", flush=True)
            started_at = dt.datetime.now().astimezone().isoformat()
            record = run_one(adb, camera_spec, original_index, output_dir)
            stem = f"{original_index:03d}-{spec.label}"
            record.update(
                {
                    "visibleRunId": visible_id,
                    "startedAt": started_at,
                    "endedAt": dt.datetime.now().astimezone().isoformat(),
                    "artifacts": {
                        "rawLogcat": f"{stem}-raw-logcat.txt",
                        "filteredLogcat": f"{stem}-filtered.txt",
                        "json": f"{stem}.json",
                        "launch": f"{stem}-launch.txt",
                    },
                }
            )
            (output_dir / f"{stem}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
            records.append(record)
            records.sort(key=lambda item: item["visibleRunId"])
            manifest_path.write_text(json.dumps(records, indent=2), encoding="utf-8")
            (output_dir / "manifest.md").write_text(manifest_markdown(metadata, records), encoding="utf-8")

        if len(records) == total:
            complete = RunSpec(
                "camera-slate",
                "complete",
                quiet_ms=0,
                camera_mode=True,
                camera_index=total,
                camera_total=total,
                camera_profile=args.profile,
                camera_detail=f"{total} tests finished; recording may stop",
                camera_title="COMPLETE",
                camera_slate_ms=15_000,
            )
            print("Showing COMPLETE slate for 15 seconds", flush=True)
            run_one(adb, complete, total + 1, output_dir)
    finally:
        log_process.terminate()
        try:
            log_process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            log_process.kill()
        suite_log.close()
        capture_state(adb, output_dir, "after")

    failures = [record for record in records if record["timedOut"] or record["fatalOrAnr"]]
    print(f"COMPLETE: tests={len(records)}/{total}; failures={len(failures)}; output={output_dir}")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
