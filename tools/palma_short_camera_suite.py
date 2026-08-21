#!/usr/bin/env python3
"""Prepare or run the bounded SHORT_CAMERA preset without changing FULL_CAMERA."""

from __future__ import annotations

import argparse
import csv
import dataclasses
import datetime as dt
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

from palma_camera_suite import next_phase_overlay_cases
from palma_epd_test import Adb, RunSpec, connected_serials, resolve_adb, run_one
from palma_next_phase_suite import (
    LAUNCHER,
    PACKAGE,
    SETTINGS,
    BooxUi,
    capture_temperature,
    foreground_package,
    launch_automation,
    now,
    overlay_record,
    parse_display_events,
    show_slate,
    wait_for_log,
)


PRESET = "SHORT_CAMERA"
PHYSICAL_TRIALS = (
    (1, 1, "Speed"),
    (1, 2, "Regal"),
    (1, 3, "HD"),
    (2, 2, "Regal"),
)
CONTROL_OPEN = (
    "fast-01-control-16x220",
    "fast-02-failure-16x31",
)
CONTROL_CLOSE = "fast-25-final-control-16x220"
RESIDUAL_8X100_LABEL = "prescreen residual — vertical gray banding / apparent progressive loss of clearing"
CANDIDATE_VARIANTS = {
    "duplicate-16x100": (
        "fast-dup-16x100-r1",
        "fast-dup-16x100-r2",
        "fast-dup-16x100-r3",
    ),
    "reverse-16x140": (
        "fast-reverse-16x140-r1",
        "fast-reverse-16x140-r2",
    ),
    "duplicate-12x100": ("fast-dup-12x100-r1",),
    "duplicate-8x100": ("fast-dup-8x100-r1",),
    "progressive-t1-16x220": ("transparent-t1-16x220-r1",),
    "pipelined-t2-16x220": ("transparent-t2-16x220-r1",),
}
INTRO_SECONDS = 10.0
OUTRO_SECONDS = 10.0
PHYSICAL_HOLDS_SECONDS = (3.0, 3.0, 5.0)
PHYSICAL_TRIAL_SECONDS = 26.7
PHYSICAL_FIRST_TAP_SECONDS = 10.0
SHORT_SEED_WHITE_HOLD_MS = 22_000
LOG_TIMESTAMP_RE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")


def selected_labels(prescreen_state: dict | None) -> list[str]:
    labels = list(CONTROL_OPEN)
    if prescreen_state is None:
        for variants in CANDIDATE_VARIANTS.values():
            labels.extend(variants)
    else:
        classifications = {item["id"]: item["classification"] for item in prescreen_state["candidates"]}
        pending = [candidate_id for candidate_id in CANDIDATE_VARIANTS if classifications.get(candidate_id) == "PENDING"]
        if pending:
            raise RuntimeError(f"VISUAL_PRESCREEN classifications remain pending: {pending}")
        for candidate_id, variants in CANDIDATE_VARIANTS.items():
            classification = classifications.get(candidate_id)
            if classification == "CLEAN":
                labels.extend(variants)
            elif classification == "UNCERTAIN":
                labels.append(variants[0])
            elif classification == "RESIDUAL" and candidate_id == "duplicate-8x100":
                labels.append(variants[0])
            elif classification != "RESIDUAL":
                raise RuntimeError(f"invalid VISUAL_PRESCREEN classification for {candidate_id}: {classification}")
    labels.append(CONTROL_CLOSE)
    return labels


def overlay_specs(prescreen_state: dict | None = None) -> list[RunSpec]:
    available = {spec.label: spec for spec in next_phase_overlay_cases()}
    labels = selected_labels(prescreen_state)
    missing = [label for label in labels if label not in available]
    if missing:
        raise RuntimeError(f"FULL_CAMERA no longer contains required SHORT_CAMERA cases: {missing}")
    return [available[label] for label in labels]


def is_residual_8x100(spec: RunSpec, prescreen_state: dict | None) -> bool:
    if spec.label != "fast-dup-8x100-r1" or prescreen_state is None:
        return False
    classification = next(
        item["classification"] for item in prescreen_state["candidates"] if item["id"] == "duplicate-8x100"
    )
    return classification == "RESIDUAL"


def overlay_sweep_seconds(spec: RunSpec) -> float:
    if spec.overlay_presentation == "t2":
        return (spec.bands + 1) * spec.black_ms * spec.duplicates / 1_000
    return spec.bands * (spec.black_ms + spec.white_ms) * spec.duplicates / 1_000


def overlay_run_seconds(spec: RunSpec) -> float:
    prelude = (spec.camera_slate_ms + 2 * spec.camera_precondition_ms + spec.camera_baseline_ms) / 1_000
    return prelude + overlay_sweep_seconds(spec) + spec.quiet_ms / 1_000


def estimated_duration_seconds(specs: list[RunSpec]) -> float:
    return (
        INTRO_SECONDS
        + len(PHYSICAL_TRIALS) * PHYSICAL_TRIAL_SECONDS
        + sum(overlay_run_seconds(spec) for spec in specs)
        + OUTRO_SECONDS
    )


def format_offset(seconds: float) -> str:
    milliseconds = round(seconds * 1_000)
    minutes, remainder = divmod(milliseconds, 60_000)
    whole_seconds, milliseconds = divmod(remainder, 1_000)
    return f"{minutes:02d}:{whole_seconds:02d}.{milliseconds:03d}"


def planned_records(specs: list[RunSpec], prescreen_state: dict | None = None) -> list[dict]:
    records: list[dict] = []
    index = 1
    for block, trial, mode in PHYSICAL_TRIALS:
        records.append(
            {
                "run": index,
                "section": "A_BOOX_PHYSICAL_REFRESH",
                "testName": f"regal-block{block}-{trial}-{mode.lower()}",
                "parameters": {
                    "mode": mode,
                    "trialBlock": block,
                    "refreshPasses": 3,
                    "evaluationHoldsSeconds": list(PHYSICAL_HOLDS_SECONDS),
                },
                "booxSelectedMode": mode,
                "overlayMode": "T0 seed hold",
                "bandCount": None,
                "direction": None,
                "duplicateCount": None,
                "phaseTiming": f"checker 600 ms; inverse 600 ms; white hold {SHORT_SEED_WHITE_HOLD_MS} ms",
                "physicalResult": "PENDING_CAMERA",
            }
        )
        index += 1
    for spec in specs:
        residual_diagnostic = is_residual_8x100(spec, prescreen_state)
        section = "C_PROGRESSIVE_TRANSPARENCY" if spec.overlay_presentation in {"t1", "t2"} else "B_FAST_OVERLAY"
        records.append(
            {
                "run": index,
                "section": section,
                "testName": RESIDUAL_8X100_LABEL if residual_diagnostic else spec.label,
                "parameters": {
                    **dataclasses.asdict(spec),
                    "prescreenClassification": "RESIDUAL" if residual_diagnostic else None,
                    "promotionEligible": False if residual_diagnostic else None,
                },
                "booxSelectedMode": "unchanged",
                "overlayMode": spec.overlay_presentation.upper(),
                "bandCount": spec.bands,
                "direction": "bottom-to-top" if spec.bottom_to_top else "top-to-bottom",
                "duplicateCount": spec.duplicates,
                "phaseTiming": {"blackMs": spec.black_ms, "whiteMs": spec.white_ms},
                "promotionEligible": False if residual_diagnostic else None,
                "physicalResult": "PENDING_CAMERA",
            }
        )
        index += 1
    return records


def planned_windows(specs: list[RunSpec], prescreen_state: dict | None = None) -> list[dict]:
    windows: list[dict] = []
    elapsed = INTRO_SECONDS
    for index, (_, _, mode) in enumerate(PHYSICAL_TRIALS, 1):
        first_tap = elapsed + PHYSICAL_FIRST_TAP_SECONDS
        checkpoint_start = first_tap
        for refresh_pass, hold in enumerate(PHYSICAL_HOLDS_SECONDS, 1):
            start = checkpoint_start
            windows.append(
                {
                    "run": index,
                    "test": f"{mode} physical Full Refresh",
                    "checkpoint": f"after pass {refresh_pass}/3",
                    "startSeconds": start,
                    "endSeconds": start + hold,
                    "basis": "estimated",
                }
            )
            checkpoint_start += hold
        elapsed += PHYSICAL_TRIAL_SECONDS
    for index, spec in enumerate(specs, len(PHYSICAL_TRIALS) + 1):
        test_name = RESIDUAL_8X100_LABEL if is_residual_8x100(spec, prescreen_state) else spec.label
        prelude = (spec.camera_slate_ms + 2 * spec.camera_precondition_ms + spec.camera_baseline_ms) / 1_000
        quiet_start = elapsed + prelude + overlay_sweep_seconds(spec)
        windows.append(
            {
                "run": index,
                "test": test_name,
                "checkpoint": "quiet evaluation window",
                "startSeconds": quiet_start,
                "endSeconds": quiet_start + spec.quiet_ms / 1_000,
                "basis": "estimated",
            }
        )
        elapsed += overlay_run_seconds(spec)
    return windows


def write_manifest(output_dir: Path, records: list[dict], metadata: dict) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    payload = {"metadata": metadata, "runs": records}
    (output_dir / "SHORT_CAMERA_MANIFEST.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    fields = [
        "run", "section", "testName", "booxSelectedMode", "overlayMode", "bandCount", "direction",
        "duplicateCount", "phaseTiming", "promotionEligible", "actualElapsedDuration", "physicalResult",
    ]
    with (output_dir / "SHORT_CAMERA_MANIFEST.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for record in records:
            writer.writerow(
                {
                    key: json.dumps(record.get(key)) if isinstance(record.get(key), (dict, list)) else record.get(key)
                    for key in fields
                }
            )
    rows = [
        "# SHORT_CAMERA manifest",
        "",
        f"- Parent preset: `FULL_CAMERA` (unchanged 43-run suite)",
        f"- Runs: `{len(records)}`",
        f"- Status: `{metadata['status']}`",
        f"- Estimated programmed duration: `{format_offset(metadata['estimatedDurationSeconds'])}`",
        "- Physical results remain `PENDING_CAMERA` until external-camera review.",
        "",
        "| Run | Section | Test | BOOX mode | Overlay | Parameters |",
        "| ---: | --- | --- | --- | --- | --- |",
    ]
    for record in records:
        rows.append(
            f"| {record['run']:02d} | {record['section']} | {record['testName']} | "
            f"{record.get('booxSelectedMode', '')} | {record.get('overlayMode', '')} | "
            f"`{json.dumps(record.get('parameters', {}), separators=(',', ':'))}` |"
        )
    (output_dir / "SHORT_CAMERA_MANIFEST.md").write_text("\n".join(rows) + "\n", encoding="utf-8")


def write_review_index(output_dir: Path, windows: list[dict], metadata: dict) -> None:
    rows = [
        "# CAMERA_REVIEW_INDEX",
        "",
        f"- Preset: `SHORT_CAMERA`",
        f"- Timeline basis: `{metadata['timelineBasis']}`",
        f"- Estimated programmed duration: `{format_offset(metadata['estimatedDurationSeconds'])}`",
        "- BOOX checkpoints begin at each refresh tap: 3 seconds after passes 1 and 2, then 5 seconds after pass 3.",
        "- Before execution these offsets are estimates; execution rewrites this index from captured host/log timestamps.",
        "",
        "| Run | Test | Checkpoint | Start | End | Duration | Basis |",
        "| ---: | --- | --- | ---: | ---: | ---: | --- |",
    ]
    for window in windows:
        duration = window["endSeconds"] - window["startSeconds"]
        rows.append(
            f"| {window['run']:02d} | {window['test']} | {window['checkpoint']} | "
            f"{format_offset(window['startSeconds'])} | {format_offset(window['endSeconds'])} | "
            f"{duration:.3f}s | {window['basis']} |"
        )
    (output_dir / "CAMERA_REVIEW_INDEX.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
    (output_dir / "CAMERA_REVIEW_INDEX.json").write_text(json.dumps({"metadata": metadata, "windows": windows}, indent=2), encoding="utf-8")


def parse_log_timestamp(line: str) -> dt.datetime | None:
    match = LOG_TIMESTAMP_RE.match(line)
    return dt.datetime.strptime(match.group(1), "%m-%d %H:%M:%S.%f") if match else None


def actual_overlay_window(raw_path: Path, spec: RunSpec, run_start_elapsed: float) -> dict:
    raw = raw_path.read_text(encoding="utf-8", errors="replace").splitlines()
    host_run_id = f"r{spec.camera_index:03d}-{spec.label}"[:80]
    host_begin_line = next(line for line in raw if f"AUTOMATION_HOST_BEGIN_{host_run_id}" in line)
    quiet_begin_line = next(line for line in raw if "QUIET_HOLD_BEGIN" in line)
    quiet_end_line = next(line for line in raw if "QUIET_HOLD_END" in line)
    host_begin = parse_log_timestamp(host_begin_line)
    quiet_begin = parse_log_timestamp(quiet_begin_line)
    quiet_end = parse_log_timestamp(quiet_end_line)
    if host_begin is None or quiet_begin is None or quiet_end is None:
        raise RuntimeError(f"missing timestamp in quiet window for {spec.label}")
    return {
        "run": spec.camera_index,
        "test": spec.label,
        "checkpoint": "quiet evaluation window",
        "startSeconds": run_start_elapsed + (quiet_begin - host_begin).total_seconds(),
        "endSeconds": run_start_elapsed + (quiet_end - host_begin).total_seconds(),
        "basis": "captured log timestamps",
    }


def run_short_physical_trial(
    adb: Adb,
    ui: BooxUi,
    output_dir: Path,
    index: int,
    total: int,
    block: int,
    trial: int,
    mode: str,
) -> dict:
    label = f"regal-block{block}-{trial}-{mode.lower()}"
    run_dir = output_dir / "camera" / "runs"
    show_slate(
        adb,
        index,
        total,
        f"RUN {index:02d} / {total:02d}",
        f"BOOX PHYSICAL TEST | MODE {mode.upper()} | 3 FULL REFRESHES | HOLDS 3S / 3S / 5S",
    )
    adb.run("logcat", "-b", "all", "-c", check=False)
    host_start = now()
    device_start = adb.run("shell", "date", "+%Y-%m-%dT%H:%M:%S%z").strip()
    temperature = capture_temperature(adb)
    adb.run("shell", "am", "start", "-W", "-n", LAUNCHER)
    time.sleep(1.0)
    host_run_id = f"r{index:03d}-{label}"
    launch_output = launch_automation(
        adb,
        "overlay-seed-hold",
        host_run_id,
        [
            "--ei", "bands", "16",
            "--el", "blackMs", "220",
            "--el", "whiteMs", "220",
            "--el", "quietMs", str(SHORT_SEED_WHITE_HOLD_MS),
            "--el", "cameraPreconditionMs", "600",
            "--es", "overlayPresentation", "t0",
        ],
    )
    wait_for_log(adb, "SEED_WHITE_HOLD_BEGIN", 12)
    resumed = foreground_package(adb)
    selection = ui.select_mode(mode, f"short-run-{index:02d}-{mode.lower()}")
    time.sleep(2.0)
    refreshes = []
    for refresh_pass, hold in enumerate(PHYSICAL_HOLDS_SECONDS, 1):
        refreshes.append(ui.full_refresh(mode, refresh_pass))
        time.sleep(hold)
    wait_for_log(adb, f"AUTOMATION_END hostRunId={host_run_id}", 12)
    raw = adb.run("logcat", "-b", "all", "-d", "-v", "threadtime", check=False)
    parsed = parse_display_events(raw)
    host_end = now()
    device_end = adb.run("shell", "date", "+%Y-%m-%dT%H:%M:%S%z").strip()
    stem = f"run_{index:03d}"
    (run_dir / f"{stem}-logcat.txt").write_text(raw, encoding="utf-8")
    (run_dir / f"{stem}-filtered.txt").write_text("\n".join(parsed.pop("filteredLines")) + "\n", encoding="utf-8")
    (run_dir / f"{stem}-launch.txt").write_text(launch_output, encoding="utf-8")
    record = {
        "run": index,
        "section": "A_BOOX_PHYSICAL_REFRESH",
        "testName": label,
        "parameters": {
            "mode": mode,
            "trialBlock": block,
            "refreshPasses": 3,
            "evaluationHoldsSeconds": list(PHYSICAL_HOLDS_SECONDS),
        },
        "hostStart": host_start,
        "deviceStart": device_start,
        "hostEnd": host_end,
        "deviceEnd": device_end,
        "foregroundPackage": resumed,
        "booxSelectedMode": selection["after"]["selected"],
        "modeSelection": selection,
        "refreshes": refreshes,
        "temperatureProxy": temperature,
        **parsed,
        "overlayMode": "T0 seed hold",
        "bandCount": None,
        "direction": None,
        "duplicateCount": None,
        "phaseTiming": f"checker 600 ms; inverse 600 ms; white hold {SHORT_SEED_WHITE_HOLD_MS} ms",
        "actualElapsedDuration": None,
        "errors": [],
        "physicalResult": "PENDING_CAMERA",
    }
    (run_dir / f"{stem}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def execute(args: argparse.Namespace, output_dir: Path, specs: list[RunSpec], prescreen_state: dict) -> int:
    adb_path = resolve_adb(args.adb)
    serials = connected_serials(adb_path)
    serial = args.serial or (serials[0] if len(serials) == 1 else "")
    if not serial or serial not in serials:
        raise RuntimeError(f"expected the connected Palma, found {serials}")
    adb = Adb(adb_path, serial)
    camera_dir = output_dir / "camera"
    runs_dir = camera_dir / "runs"
    raw_dir = output_dir / "software-analysis" / "raw"
    runs_dir.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)
    ui = BooxUi(adb, raw_dir)
    initial = {"foreground": "captured before run", "launcher": None, "settings": None}
    apk_path = Path(__file__).resolve().parents[1] / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    total_runs = len(PHYSICAL_TRIALS) + len(specs)
    metadata = {
        "preset": PRESET,
        "parentPreset": "FULL_CAMERA",
        "status": "RUNNING",
        "startedAt": now(),
        "serial": serial,
        "totalRuns": total_runs,
        "estimatedDurationSeconds": estimated_duration_seconds(specs),
        "timelineBasis": "captured host/log timestamps",
        "apkSha256": hashlib.sha256(apk_path.read_bytes()).hexdigest().upper(),
        "physicalEvidence": "EXTERNAL CAMERA REQUIRED",
        "visualPrescreen": {
            "cameraValidation": False,
            "productionDefaultPromotion": False,
            "classifications": {item["id"]: item["classification"] for item in prescreen_state["candidates"]},
        },
    }
    suite_start_wall = dt.datetime.now().astimezone()
    suite_start_monotonic = time.monotonic()
    records: list[dict] = []
    windows: list[dict] = []

    adb.run("shell", "am", "start", "-W", "-n", LAUNCHER)
    _, launcher_state, _ = ui.ensure_panel("short-preflight-launcher")
    initial["launcher"] = launcher_state["selected"]
    adb.run("shell", "input", "keyevent", "BACK", check=False)
    adb.run("shell", "am", "start", "-W", "-n", SETTINGS)
    _, settings_state, _ = ui.ensure_panel("short-preflight-settings")
    initial["settings"] = settings_state["selected"]
    adb.run("shell", "input", "keyevent", "BACK", check=False)
    metadata["initialState"] = initial

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
        show_slate(adb, 0, total_runs, "SHORT_CAMERA", "CAMERA SUITE STARTING | DO NOT TOUCH DEVICE", 5_000)
        launch_automation(adb, "camera-sync", "short-camera-sync", ["--el", "blackMs", "1000", "--el", "whiteMs", "1000"])
        wait_for_log(adb, "AUTOMATION_END hostRunId=short-camera-sync", 15)
        index = 1
        for block, trial, mode in PHYSICAL_TRIALS:
            record = run_short_physical_trial(adb, ui, output_dir, index, total_runs, block, trial, mode)
            records.append(record)
            for refresh in record["refreshes"]:
                tap = dt.datetime.fromisoformat(refresh["hostTap"])
                start = (tap - suite_start_wall).total_seconds()
                hold = PHYSICAL_HOLDS_SECONDS[refresh["pass"] - 1]
                windows.append(
                    {
                        "run": index,
                        "test": f"{mode} physical Full Refresh",
                        "checkpoint": f"after pass {refresh['pass']}/3",
                        "startSeconds": start,
                        "endSeconds": start + hold,
                        "basis": "captured host tap timestamp",
                    }
                )
            write_manifest(output_dir, records, metadata)
            write_review_index(output_dir, windows, metadata)
            index += 1

        for source_spec in specs:
            residual_diagnostic = is_residual_8x100(source_spec, prescreen_state)
            spec = dataclasses.replace(
                source_spec,
                camera_index=index,
                camera_total=total_runs,
                camera_profile=PRESET,
                camera_title=f"RUN {index:02d} / {total_runs:02d}",
                camera_detail=RESIDUAL_8X100_LABEL if residual_diagnostic else source_spec.camera_detail,
            )
            run_start_elapsed = time.monotonic() - suite_start_monotonic
            parsed = run_one(adb, spec, index, runs_dir)
            record = overlay_record(spec, parsed, index)
            record["section"] = "C_PROGRESSIVE_TRANSPARENCY" if spec.overlay_presentation in {"t1", "t2"} else "B_FAST_OVERLAY"
            if residual_diagnostic:
                record["testName"] = RESIDUAL_8X100_LABEL
                record["promotionEligible"] = False
                record["parameters"]["prescreenClassification"] = "RESIDUAL"
                record["parameters"]["promotionEligible"] = False
            records.append(record)
            raw_path = runs_dir / f"{index:03d}-{spec.label}-raw-logcat.txt"
            window = actual_overlay_window(raw_path, spec, run_start_elapsed)
            if residual_diagnostic:
                window["test"] = RESIDUAL_8X100_LABEL
            windows.append(window)
            write_manifest(output_dir, records, metadata)
            write_review_index(output_dir, windows, metadata)
            index += 1

        metadata["status"] = "COMPLETE_PENDING_CAMERA_REVIEW"
        show_slate(adb, total_runs + 1, total_runs, "SHORT_CAMERA COMPLETE", f"RUNS {len(records)} | PHYSICAL RESULTS PENDING", 10_000)
    finally:
        adb.run("shell", "am", "force-stop", PACKAGE, check=False)
        try:
            adb.run("shell", "am", "start", "-W", "-n", LAUNCHER)
            ui.select_mode(initial["launcher"] or "Regal", "short-restore-launcher")
            adb.run("shell", "input", "keyevent", "BACK", check=False)
            adb.run("shell", "am", "start", "-W", "-n", SETTINGS)
            ui.select_mode(initial["settings"] or "HD", "short-restore-settings")
            adb.run("shell", "input", "keyevent", "BACK", check=False)
        except Exception as error:
            restoration_errors.append(str(error))
        collector.terminate()
        try:
            collector.wait(timeout=5)
        except subprocess.TimeoutExpired:
            collector.kill()
        log_handle.close()
        metadata["endedAt"] = now()
        metadata["actualDurationSeconds"] = round(time.monotonic() - suite_start_monotonic, 3)
        metadata["restorationErrors"] = restoration_errors
        if restoration_errors:
            metadata["status"] = "COMPLETE_WITH_RESTORATION_ERRORS"
        write_manifest(output_dir, records, metadata)
        write_review_index(output_dir, windows, metadata)
    return 1 if restoration_errors else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true", help="Generate plans only; never contact the device")
    parser.add_argument("--confirmed-recording", action="store_true", help="Required acknowledgement before physical execution")
    parser.add_argument("--prescreen-state", type=Path, help="Completed VISUAL_PRESCREEN_STATE.json")
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    output_dir = args.output.resolve()
    prescreen_state = None
    if args.prescreen_state:
        prescreen_state = json.loads(args.prescreen_state.resolve().read_text(encoding="utf-8"))
    specs = overlay_specs(prescreen_state)
    total_runs = len(PHYSICAL_TRIALS) + len(specs)
    metadata = {
        "preset": PRESET,
        "parentPreset": "FULL_CAMERA",
        "status": "PLANNED_NOT_RUN" if prescreen_state else "PENDING_VISUAL_PRESCREEN",
        "generatedAt": now(),
        "totalRuns": total_runs,
        "estimatedDurationSeconds": estimated_duration_seconds(specs),
        "timelineBasis": "estimated pre-execution offsets",
        "executionGate": "CAMERA SUITE READY plus explicit user recording confirmation",
        "visualPrescreenApplied": prescreen_state is not None,
    }
    write_manifest(output_dir, planned_records(specs, prescreen_state), metadata)
    write_review_index(output_dir, planned_windows(specs, prescreen_state), metadata)
    if args.prepare_only:
        return 0
    if not args.confirmed_recording:
        raise RuntimeError("SHORT_CAMERA not started: wait for the user to confirm that recording has begun")
    if prescreen_state is None:
        raise RuntimeError("SHORT_CAMERA not started: completed VISUAL_PRESCREEN classifications are required")
    return execute(args, output_dir, specs, prescreen_state)


if __name__ == "__main__":
    raise SystemExit(main())
