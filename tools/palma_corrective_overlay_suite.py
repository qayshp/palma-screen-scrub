#!/usr/bin/env python3
"""Prepare or run the ten-case corrective overlay camera suite."""

from __future__ import annotations

import argparse
import csv
import dataclasses
import datetime as dt
import hashlib
import json
import re
import subprocess
import threading
import time
from pathlib import Path

from palma_camera_suite import next_phase_overlay_cases
from palma_epd_test import Adb, RunSpec, connected_serials, resolve_adb, run_one
from palma_next_phase_suite import PACKAGE, foreground_package, now, overlay_record, show_slate


PRESET = "PALMA_CORRECTIVE_OVERLAY_CAMERA_SUITE"
TOTAL_RUNS = 10
EVALUATION_HOLD_MS = 4_000
RUN_SLATE_MS = 2_500
FINAL_SLATE_MS = 8_000
COUNTDOWN_SECONDS = 5
EINK_WISE_RESOURCE_IDS = (
    "com.android.systemui:id/e_ink_app_title",
    "com.android.systemui:id/e_ink_radio_text_view",
)
LOG_TIMESTAMP_RE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")


RUN_DEFINITIONS = (
    {
        "source": "fast-01-control-16x220",
        "name": "16 x 220 ms normal forward",
        "slate": "16 × 220 MS | NORMAL FORWARD | CONTROL | KNOWN-GOOD OPENING",
        "role": "CONTROL",
        "promotionEligible": "REVIEW_REQUIRED",
    },
    {
        "source": "fast-02-failure-16x31",
        "name": "16 x 31 ms normal forward",
        "slate": "16 × 31 MS | NORMAL FORWARD | CONTROL | KNOWN-FAST-FAILURE",
        "role": "CONTROL",
        "promotionEligible": False,
    },
    {
        "source": "fast-dup-16x100-r1",
        "name": "16 x 100 ms duplicate — validation repeat 1",
        "slate": "16 × 100 MS | DUPLICATE | CANDIDATE | REPEAT 1 / 2",
        "role": "CANDIDATE",
        "promotionEligible": "REVIEW_REQUIRED",
    },
    {
        "source": "fast-dup-16x100-r2",
        "name": "16 x 100 ms duplicate — validation repeat 2",
        "slate": "16 × 100 MS | DUPLICATE | CANDIDATE | REPEAT 2 / 2",
        "role": "CANDIDATE",
        "promotionEligible": "REVIEW_REQUIRED",
    },
    {
        "source": "fast-reverse-16x140-r1",
        "name": "16 x 140 ms bottom-to-top reverse",
        "slate": "16 × 140 MS | BOTTOM-TO-TOP REVERSE | CANDIDATE",
        "role": "CANDIDATE",
        "promotionEligible": "REVIEW_REQUIRED",
    },
    {
        "source": "fast-dup-12x100-r1",
        "name": "12 x 100 ms duplicate",
        "slate": "12 × 100 MS | DUPLICATE | CANDIDATE",
        "role": "CANDIDATE",
        "promotionEligible": "REVIEW_REQUIRED",
    },
    {
        "source": "fast-dup-8x100-r1",
        "name": "expected residual — vertical gray banding / apparent progressive loss of clearing",
        "slate": "8 × 100 MS DUPLICATE | KNOWN PRESCREEN RESIDUAL | VERTICAL GRAY BANDING | DIAGNOSTIC ONLY",
        "role": "KNOWN RESIDUAL",
        "promotionEligible": False,
    },
    {
        "source": "transparent-t1-16x220-r1",
        "name": "T1 progressive transparency — 16 x 220 ms",
        "slate": "16 × 220 MS | T1 PROGRESSIVE TRANSPARENCY | CANDIDATE | FULL WHITE DWELL",
        "role": "CANDIDATE",
        "promotionEligible": "EXPERIMENTAL_REVIEW_REQUIRED",
    },
    {
        "source": "transparent-t2-16x220-r1",
        "name": "T2 pipelined progressive transparency — 16 x 220 ms equivalent minimum white dwell",
        "slate": "16 × 220 MS | T2 PIPELINED TRANSPARENCY | CANDIDATE | DWELL GUARANTEED",
        "role": "CANDIDATE",
        "promotionEligible": "EXPERIMENTAL_REVIEW_REQUIRED",
    },
    {
        "source": "fast-25-final-control-16x220",
        "name": "16 x 220 ms normal forward — closing control",
        "slate": "16 × 220 MS | NORMAL FORWARD | CONTROL | KNOWN-GOOD CLOSING",
        "role": "CONTROL",
        "promotionEligible": "REVIEW_REQUIRED",
    },
)


def source_specs() -> list[tuple[dict, RunSpec]]:
    available = {spec.label: spec for spec in next_phase_overlay_cases()}
    missing = [definition["source"] for definition in RUN_DEFINITIONS if definition["source"] not in available]
    if missing:
        raise RuntimeError(f"FULL_CAMERA no longer contains corrective source cases: {missing}")
    return [(definition, available[definition["source"]]) for definition in RUN_DEFINITIONS]


def configured_spec(index: int, definition: dict, source: RunSpec) -> RunSpec:
    return dataclasses.replace(
        source,
        quiet_ms=EVALUATION_HOLD_MS,
        camera_mode=True,
        camera_index=index,
        camera_total=TOTAL_RUNS,
        camera_profile=PRESET,
        camera_title=f"RUN {index} / {TOTAL_RUNS}",
        camera_detail=definition["slate"],
        camera_slate_ms=500,
        camera_precondition_ms=600,
        camera_baseline_ms=800,
    )


def overlay_sweep_seconds(spec: RunSpec) -> float:
    if spec.overlay_presentation == "t2":
        return (spec.bands + 1) * spec.black_ms * spec.duplicates / 1_000
    return spec.bands * (spec.black_ms + spec.white_ms) * spec.duplicates / 1_000


def run_seconds(spec: RunSpec) -> float:
    prelude = (spec.camera_slate_ms + 2 * spec.camera_precondition_ms + spec.camera_baseline_ms) / 1_000
    return RUN_SLATE_MS / 1_000 + prelude + overlay_sweep_seconds(spec) + EVALUATION_HOLD_MS / 1_000


def estimated_duration_seconds() -> float:
    return COUNTDOWN_SECONDS + sum(run_seconds(configured_spec(index, definition, source)) for index, (definition, source) in enumerate(source_specs(), 1)) + FINAL_SLATE_MS / 1_000


def format_offset(seconds: float) -> str:
    milliseconds = round(seconds * 1_000)
    minutes, remainder = divmod(milliseconds, 60_000)
    whole_seconds, milliseconds = divmod(remainder, 1_000)
    return f"{minutes:02d}:{whole_seconds:02d}.{milliseconds:03d}"


def check_eink_wise(adb: Adb, raw_dir: Path, stem: str) -> str:
    remote = "/sdcard/palma-corrective-eink-wise.xml"
    dump_result = adb.run("shell", "uiautomator", "dump", remote, check=False, timeout=15)
    xml = adb.run("exec-out", "cat", remote, check=False, timeout=15)
    raw_dir.mkdir(parents=True, exist_ok=True)
    (raw_dir / f"{stem}-dump.txt").write_text(dump_result, encoding="utf-8")
    (raw_dir / f"{stem}.xml").write_text(xml, encoding="utf-8")
    if "<hierarchy" not in xml:
        return "UNKNOWN"
    return "PRESENT" if any(resource_id in xml for resource_id in EINK_WISE_RESOURCE_IDS) else "ABSENT"


def ensure_eink_wise_absent(adb: Adb, raw_dir: Path, stem: str) -> str:
    state = check_eink_wise(adb, raw_dir, f"{stem}-initial")
    if state == "PRESENT":
        adb.run("shell", "input", "keyevent", "BACK", check=False)
        time.sleep(2.0)
        state = check_eink_wise(adb, raw_dir, f"{stem}-after-dismiss")
    return state


def parse_log_timestamp(line: str) -> dt.datetime | None:
    match = LOG_TIMESTAMP_RE.match(line)
    return dt.datetime.strptime(match.group(1), "%m-%d %H:%M:%S.%f") if match else None


def elapsed_between(raw: str, start_text: str, end_text: str) -> float | None:
    start_line = next((line for line in raw.splitlines() if start_text in line), None)
    end_line = next((line for line in raw.splitlines() if end_text in line), None)
    if not start_line or not end_line:
        return None
    start = parse_log_timestamp(start_line)
    end = parse_log_timestamp(end_line)
    return round((end - start).total_seconds(), 3) if start and end else None


def monitor_prescrub(
    adb: Adb,
    raw_dir: Path,
    host_run_id: str,
    result: dict,
    finished: threading.Event,
) -> None:
    needle = f"PRECONDITION_BEGIN hostRunId={host_run_id}"
    deadline = time.monotonic() + 30
    while not finished.is_set() and time.monotonic() < deadline:
        raw = adb.run("logcat", "-b", "all", "-d", "-v", "threadtime", check=False)
        if needle in raw:
            result["state"] = check_eink_wise(adb, raw_dir, f"{host_run_id}-prescrub")
            result["checkedAt"] = now()
            if result["state"] != "ABSENT":
                adb.run("shell", "am", "force-stop", PACKAGE, check=False)
            return
        time.sleep(0.1)
    result["state"] = "UNKNOWN"
    result["checkedAt"] = now()


def actual_quiet_window(raw: str, suite_start: dt.datetime, run: int, name: str, role: str, validity: str) -> dict:
    begin_line = next(line for line in raw.splitlines() if "QUIET_HOLD_BEGIN" in line)
    end_line = next(line for line in raw.splitlines() if "QUIET_HOLD_END" in line)
    begin = parse_log_timestamp(begin_line)
    end = parse_log_timestamp(end_line)
    if begin is None or end is None:
        raise RuntimeError(f"missing evaluation hold timestamps for run {run}")
    suite_naive = suite_start.replace(tzinfo=None, year=begin.year)
    return {
        "run": run,
        "testName": name,
        "startSeconds": (begin - suite_naive).total_seconds(),
        "endSeconds": (end - suite_naive).total_seconds(),
        "expectedRole": role,
        "validity": validity,
        "basis": "captured device log timestamps",
    }


def planned_records() -> list[dict]:
    records = []
    for index, (definition, source) in enumerate(source_specs(), 1):
        spec = configured_spec(index, definition, source)
        records.append(
            {
                "run": index,
                "testName": definition["name"],
                "parameters": dataclasses.asdict(spec),
                "hostStart": None,
                "deviceStart": None,
                "hostEnd": None,
                "deviceEnd": None,
                "foregroundPackage": None,
                "eink_wise_preseed": "UNKNOWN",
                "eink_wise_prescrub": "UNKNOWN",
                "run_validity": "OTHER_INVALID",
                "overlayMode": spec.overlay_presentation.upper(),
                "bandCount": spec.bands,
                "direction": "bottom-to-top" if spec.bottom_to_top else "top-to-bottom",
                "duplicateCount": spec.duplicates,
                "phaseTiming": {"blackMs": spec.black_ms, "whiteMs": spec.white_ms},
                "actualElapsedScrubTime": None,
                "surfaceFlinger": [],
                "sdm": [],
                "tconEvidence": [],
                "softwareErrors": [],
                "expectedRole": definition["role"],
                "promotionEligible": definition["promotionEligible"],
                "physical_result": "PENDING_CAMERA",
            }
        )
    return records


def planned_windows() -> list[dict]:
    windows = []
    elapsed = float(COUNTDOWN_SECONDS)
    for index, (definition, source) in enumerate(source_specs(), 1):
        spec = configured_spec(index, definition, source)
        prelude = RUN_SLATE_MS / 1_000 + (spec.camera_slate_ms + 2 * spec.camera_precondition_ms + spec.camera_baseline_ms) / 1_000
        start = elapsed + prelude + overlay_sweep_seconds(spec)
        windows.append(
            {
                "run": index,
                "testName": definition["name"],
                "startSeconds": start,
                "endSeconds": start + EVALUATION_HOLD_MS / 1_000,
                "expectedRole": definition["role"],
                "validity": "PENDING_EXECUTION",
                "basis": "estimated",
            }
        )
        elapsed += run_seconds(spec)
    return windows


def write_outputs(output_dir: Path, records: list[dict], windows: list[dict], metadata: dict) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "CORRECTIVE_CAMERA_MANIFEST.json").write_text(json.dumps({"metadata": metadata, "runs": records}, indent=2), encoding="utf-8")
    fields = [
        "run", "testName", "expectedRole", "promotionEligible", "eink_wise_preseed", "eink_wise_prescrub",
        "run_validity", "overlayMode", "bandCount", "direction", "duplicateCount", "phaseTiming",
        "actualElapsedScrubTime", "physical_result",
    ]
    with (output_dir / "CORRECTIVE_CAMERA_MANIFEST.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for record in records:
            writer.writerow({key: json.dumps(record.get(key)) if isinstance(record.get(key), (dict, list)) else record.get(key) for key in fields})
    manifest_rows = [
        "# PALMA CORRECTIVE OVERLAY CAMERA SUITE",
        "",
        f"- Runs: `{len(records)}`",
        f"- Status: `{metadata['status']}`",
        f"- Estimated programmed duration: `{format_offset(metadata['estimatedDurationSeconds'])}`",
        "- This suite does not alter the production Quick Scrub default.",
        "",
        "| Run | Test | Role | E-Ink Wise preseed | E-Ink Wise prescrub | Validity | Promotion |",
        "| ---: | --- | --- | --- | --- | --- | --- |",
    ]
    for record in records:
        manifest_rows.append(
            f"| {record['run']} | {record['testName']} | {record['expectedRole']} | {record['eink_wise_preseed']} | "
            f"{record['eink_wise_prescrub']} | {record['run_validity']} | {record['promotionEligible']} |"
        )
    (output_dir / "CORRECTIVE_CAMERA_MANIFEST.md").write_text("\n".join(manifest_rows) + "\n", encoding="utf-8")

    review_rows = [
        "# CAMERA_REVIEW_INDEX",
        "",
        f"- Preset: `{PRESET}`",
        f"- Status: `{metadata['status']}`",
        f"- Estimated programmed duration: `{format_offset(metadata['estimatedDurationSeconds'])}`",
        "- Review only the ten final four-second evaluation windows.",
        "",
        "| Run | Test | Hold start | Hold end | Role | Validity | Basis |",
        "| ---: | --- | ---: | ---: | --- | --- | --- |",
    ]
    for window in windows:
        review_rows.append(
            f"| {window['run']} | {window['testName']} | {format_offset(window['startSeconds'])} | "
            f"{format_offset(window['endSeconds'])} | {window['expectedRole']} | {window['validity']} | {window['basis']} |"
        )
    (output_dir / "CAMERA_REVIEW_INDEX.md").write_text("\n".join(review_rows) + "\n", encoding="utf-8")
    (output_dir / "CAMERA_REVIEW_INDEX.json").write_text(json.dumps({"metadata": metadata, "windows": windows}, indent=2), encoding="utf-8")


def current_foreground_component(adb: Adb) -> str | None:
    activities = adb.run("shell", "dumpsys", "activity", "activities", check=False)
    match = re.search(r"mResumedActivity:.*? ([A-Za-z0-9._]+/[A-Za-z0-9._$]+)", activities)
    return match.group(1) if match else None


def execute(args: argparse.Namespace, output_dir: Path) -> int:
    adb_path = resolve_adb(args.adb)
    serials = connected_serials(adb_path)
    serial = args.serial or (serials[0] if len(serials) == 1 else "")
    if not serial or serial not in serials:
        raise RuntimeError(f"expected the connected Palma, found {serials}")
    adb = Adb(adb_path, serial)
    camera_dir = output_dir / "camera"
    runs_dir = camera_dir / "runs"
    raw_dir = output_dir / "raw"
    runs_dir.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)
    initial_component = current_foreground_component(adb)
    apk_path = Path(__file__).resolve().parents[1] / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    metadata = {
        "preset": PRESET,
        "status": "RUNNING",
        "startedAt": now(),
        "serial": serial,
        "totalRuns": TOTAL_RUNS,
        "estimatedDurationSeconds": estimated_duration_seconds(),
        "evaluationHoldSeconds": EVALUATION_HOLD_MS / 1_000,
        "appImplementationModified": False,
        "productionDefaultModified": False,
        "apkSha256": hashlib.sha256(apk_path.read_bytes()).hexdigest().upper(),
        "initialForegroundComponent": initial_component,
    }
    records: list[dict] = []
    windows: list[dict] = []
    suite_start = dt.datetime.now()
    log_handle = (camera_dir / "logcat.txt").open("w", encoding="utf-8")
    collector = subprocess.Popen(
        [str(adb_path), "-s", serial, "logcat", "-b", "all", "-v", "threadtime"],
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    )
    restoration_errors: list[str] = []
    try:
        for count in range(COUNTDOWN_SECONDS, 0, -1):
            show_slate(
                adb,
                100 + count,
                TOTAL_RUNS,
                "PALMA CORRECTIVE OVERLAY CAMERA SUITE",
                f"E-INK WISE MUST BE CLOSED | STARTING IN {count}",
                1_000,
            )
        for index, (definition, source) in enumerate(source_specs(), 1):
            show_slate(adb, index, TOTAL_RUNS, f"RUN {index} / {TOTAL_RUNS}", definition["slate"], RUN_SLATE_MS)
            preseed = ensure_eink_wise_absent(adb, raw_dir, f"run-{index:02d}-preseed")
            if preseed != "ABSENT":
                record = planned_records()[index - 1]
                record.update(
                    {
                        "hostStart": now(),
                        "hostEnd": now(),
                        "eink_wise_preseed": preseed,
                        "run_validity": "INVALID_EINK_WISE_VISIBLE" if preseed == "PRESENT" else "OTHER_INVALID",
                        "softwareErrors": ["run aborted before ghost seed because E-Ink Wise absence was not verified"],
                    }
                )
                records.append(record)
                write_outputs(output_dir, records, windows, metadata)
                continue

            spec = configured_spec(index, definition, source)
            host_start = now()
            device_start = adb.run("shell", "date", "+%Y-%m-%dT%H:%M:%S%z").strip()
            foreground = foreground_package(adb)
            monitor_result: dict = {"state": "UNKNOWN", "checkedAt": None}
            monitor_finished = threading.Event()
            host_run_id = f"r{index:03d}-{spec.label}"[:80]
            monitor = threading.Thread(
                target=monitor_prescrub,
                args=(adb, raw_dir, host_run_id, monitor_result, monitor_finished),
                daemon=True,
            )
            monitor.start()
            parsed = run_one(adb, spec, index, runs_dir)
            monitor_finished.set()
            monitor.join(timeout=3)
            prescrub = monitor_result["state"]
            validity = (
                "VALID"
                if preseed == "ABSENT" and prescrub == "ABSENT" and not parsed.get("timedOut")
                else "INVALID_EINK_WISE_VISIBLE"
                if "PRESENT" in {preseed, prescrub}
                else "OTHER_INVALID"
            )
            raw_path = runs_dir / f"{index:03d}-{spec.label}-raw-logcat.txt"
            raw = raw_path.read_text(encoding="utf-8", errors="replace")
            record = overlay_record(spec, parsed, index)
            record.update(
                {
                    "testName": definition["name"],
                    "hostStart": host_start,
                    "deviceStart": device_start,
                    "hostEnd": now(),
                    "deviceEnd": adb.run("shell", "date", "+%Y-%m-%dT%H:%M:%S%z").strip(),
                    "foregroundPackage": foreground,
                    "eink_wise_preseed": preseed,
                    "eink_wise_prescrub": prescrub,
                    "run_validity": validity,
                    "actualElapsedScrubTime": elapsed_between(raw, "PRECONDITION_END", "QUIET_HOLD_BEGIN"),
                    "surfaceFlinger": parsed.get("surfaceFlinger", []),
                    "sdm": parsed.get("sdmDuringRun", []),
                    "tconEvidence": [line for line in raw.splitlines() if "TCON" in line or "EBC" in line],
                    "softwareErrors": [value for value in (parsed.get("automationRejected"), "timed out" if parsed.get("timedOut") else None) if value],
                    "expectedRole": definition["role"],
                    "promotionEligible": definition["promotionEligible"],
                    "physical_result": "PENDING_CAMERA",
                }
            )
            records.append(record)
            if "QUIET_HOLD_BEGIN" in raw and "QUIET_HOLD_END" in raw:
                windows.append(actual_quiet_window(raw, suite_start, index, definition["name"], definition["role"], validity))
            if validity != "VALID":
                adb.run("shell", "am", "force-stop", PACKAGE, check=False)
                ensure_eink_wise_absent(adb, raw_dir, f"run-{index:02d}-invalid-restore")
            write_outputs(output_dir, records, windows, metadata)
        metadata["status"] = "COMPLETE_PENDING_CAMERA_REVIEW"
        show_slate(
            adb,
            TOTAL_RUNS + 1,
            TOTAL_RUNS,
            "PALMA CORRECTIVE OVERLAY SUITE COMPLETE",
            "10 RUNS | PHYSICAL RESULTS PENDING CAMERA REVIEW",
            FINAL_SLATE_MS,
        )
    finally:
        adb.run("shell", "am", "force-stop", PACKAGE, check=False)
        final_eink_wise = ensure_eink_wise_absent(adb, raw_dir, "final-eink-wise")
        if final_eink_wise != "ABSENT":
            restoration_errors.append(f"E-Ink Wise final state: {final_eink_wise}")
        if initial_component:
            restore = adb.run("shell", "am", "start", "-W", "-n", initial_component, check=False)
            if "Error" in restore:
                restoration_errors.append(restore.strip())
        else:
            adb.run("shell", "input", "keyevent", "HOME", check=False)
        collector.terminate()
        try:
            collector.wait(timeout=5)
        except subprocess.TimeoutExpired:
            collector.kill()
        log_handle.close()
        continuous = (camera_dir / "logcat.txt").read_text(encoding="utf-8", errors="replace")
        (camera_dir / "app.log").write_text("\n".join(line for line in continuous.splitlines() if "PalmaScreenScrub" in line) + "\n", encoding="utf-8")
        (camera_dir / "surfaceflinger.txt").write_text("\n".join(line for line in continuous.splitlines() if "SurfaceFlinger" in line) + "\n", encoding="utf-8")
        (camera_dir / "sdm.txt").write_text("\n".join(line for line in continuous.splitlines() if "SDM" in line or "transferEpdc" in line or "commitEpdc" in line) + "\n", encoding="utf-8")
        (camera_dir / "tcon.txt").write_text("\n".join(line for line in continuous.splitlines() if "TCON" in line or "EBC" in line) + "\n", encoding="utf-8")
        metadata["endedAt"] = now()
        metadata["restorationErrors"] = restoration_errors
        if restoration_errors:
            metadata["status"] = "COMPLETE_WITH_RESTORATION_ERRORS"
        write_outputs(output_dir, records, windows, metadata)
    return 1 if restoration_errors else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true", help="Generate plans only; never contact the device")
    parser.add_argument("--confirmed-recording", action="store_true", help="Required before physical execution")
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    output_dir = args.output.resolve()
    metadata = {
        "preset": PRESET,
        "status": "PLANNED_NOT_RUN",
        "generatedAt": now(),
        "totalRuns": TOTAL_RUNS,
        "estimatedDurationSeconds": estimated_duration_seconds(),
        "evaluationHoldSeconds": EVALUATION_HOLD_MS / 1_000,
        "executionGate": "explicit user recording confirmation required",
        "appImplementationModified": False,
        "productionDefaultModified": False,
    }
    write_outputs(output_dir, planned_records(), planned_windows(), metadata)
    if args.prepare_only:
        return 0
    if not args.confirmed_recording:
        raise RuntimeError("corrective suite not started: wait for explicit recording confirmation")
    return execute(args, output_dir)


if __name__ == "__main__":
    raise SystemExit(main())
