#!/usr/bin/env python3
"""Run bounded, unattended Palma Screen Scrub experiments over ADB."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable


PACKAGE = "dev.palma.screenscrub"
COMPONENT = f"{PACKAGE}/.AutomationAlias"
ACTION = f"{PACKAGE}.action.AUTOMATION"
FILTER_RE = re.compile(
    r"PalmaHostMarker|PalmaScreenScrub|SurfaceFlinger.*(?:refresh screen|waveform_mode)|"
    r"SDM.*update_to_display|EInkHelper|FATAL EXCEPTION|ANR in|VMRuntime\.setHiddenApiExemptions|"
    r"(?:epdc|epd|eink|tcon|waveform|collision|merge|drop|skip|queue|fence|busy|complete|finished)",
    re.IGNORECASE,
)
SF_RE = re.compile(
    r"SurfaceFlinger: refresh screen \(([^)]*)\) waveform_mode\s+(\d+) flags\s+(0x[0-9a-fA-F]+) marker\s+(\d+)"
)
SF_GEOMETRY_RE = re.compile(r"^\s*(-?\d+),\s*(-?\d+)\s+-\s+(-?\d+),\s*(-?\d+)\s*$")
SDM_RE = re.compile(
    r"SDM\s*: update_to_display.*marker\[(\d+)\].*waveform_mode\s*=\s*(\d+),\s*"
    r"update_mode\s*=\s*(\d+),\s*Rect\[([^]]+)\],\s*flags\s*=\s*([0-9a-fA-F]+)"
)
TIMESTAMP_RE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")
SUBMIT_BEGIN_RE = re.compile(
    r"SUBMIT_BEGIN .*seq=(\d+) label=([^ ]+) rect=(\d+),(\d+),(\d+),(\d+) mode=(0x[0-9a-fA-F]+)"
)
SUBMIT_RETURN_RE = re.compile(r"SUBMIT_RETURN .*seq=(\d+) elapsedMs=(\d+)")
REAL_WAIT_RETURN_RE = re.compile(
    r"(?:REAL_WAIT|OVERLAY_REAL_WAIT) WAIT_RETURN code=0xff0017 "
    r"band=(\d+)/(\d+) phase=(black|white) durationMs=(\d+) result=(.*)"
)
GLOBAL_WAIT_RETURN_RE = re.compile(
    r"GLOBAL_GC_WAIT WAIT_RETURN code=0xff0017 durationMs=(\d+) result=(.*)"
)


@dataclasses.dataclass(frozen=True)
class RunSpec:
    experiment: str
    label: str
    bands: int = 16
    black_ms: int = 50
    white_ms: int = 50
    safety_ms: int = 0
    quiet_ms: int = 1_000
    repeats: int = 1
    duplicates: int = 1
    bottom_to_top: bool = False
    overlay_presentation: str = "t0"
    native_mode: int | None = None
    native_left: int | None = None
    native_top: int | None = None
    native_width: int | None = None
    native_height: int | None = None
    native_sequence: str | None = None
    sequence_delay_ms: int = 30
    sequence_count: int = 12
    region_divisor: int = 1
    alternate_native_mode: int | None = None
    camera_mode: bool = False
    camera_index: int = 0
    camera_total: int = 0
    camera_profile: str = ""
    camera_detail: str = ""
    camera_title: str = ""
    camera_slate_ms: int = 2_500
    camera_precondition_ms: int = 600
    camera_baseline_ms: int = 800

    def timeout_seconds(self) -> float:
        scrub_ms = self.bands * 2 * (self.black_ms + self.white_ms + self.safety_ms)
        if self.experiment == "native-region-sequence":
            scrub_ms = self.sequence_count * self.sequence_delay_ms
        elif self.experiment in {"real-wait-scrub", "overlay-real-wait-scrub"}:
            scrub_ms += self.bands * 2 * 500
        elif self.experiment not in {"scrub", "wait-controller", "wait-device", "overlay-scrub", "overlay-seed-hold"}:
            scrub_ms = self.black_ms + self.white_ms
        camera_ms = 0
        if self.camera_mode:
            if self.experiment == "camera-slate":
                camera_ms = self.camera_slate_ms
            else:
                camera_ms = self.camera_slate_ms + self.camera_precondition_ms * 2 + self.camera_baseline_ms
        return max(12.0, (scrub_ms * self.repeats + self.quiet_ms + camera_ms + 8_000) / 1_000)


class Adb:
    def __init__(self, executable: Path, serial: str):
        self.executable = executable
        self.serial = serial

    def run(self, *args: str, check: bool = True, timeout: float = 30.0) -> str:
        command = [str(self.executable), "-s", self.serial, *map(str, args)]
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            errors="replace",
            timeout=timeout,
        )
        if check and completed.returncode:
            raise RuntimeError(
                f"ADB failed ({completed.returncode}): {' '.join(command)}\n"
                f"stdout: {completed.stdout}\nstderr: {completed.stderr}"
            )
        return completed.stdout + completed.stderr


def resolve_adb(explicit: str | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidates.append(Path(android_home) / "platform-tools" / "adb.exe")
    path_adb = shutil.which("adb")
    if path_adb:
        candidates.append(Path(path_adb))
    workspace = Path(__file__).resolve().parents[3]
    candidates.append(workspace / "work" / "android-sdk" / "platform-tools" / "adb.exe")
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise FileNotFoundError("adb was not found; pass --adb or set ANDROID_HOME")


def connected_serials(adb_path: Path) -> list[str]:
    completed = subprocess.run(
        [str(adb_path), "devices"], capture_output=True, text=True, errors="replace", check=True
    )
    return [
        line.split()[0]
        for line in completed.stdout.splitlines()[1:]
        if line.strip() and line.split()[1:2] == ["device"]
    ]


def remote_shell_quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def matrix(selection: str) -> list[RunSpec]:
    inventory = [
        RunSpec("api-inventory", "api-inventory", quiet_ms=0),
        RunSpec("firmware-mapping", "firmware-mapping", quiet_ms=0),
    ]
    api = [
        RunSpec("normal-invalidate-control", "normal-invalidate"),
        RunSpec("controller-invalidate", "sdk-controller-invalidate-gc"),
        RunSpec("device-manager", "sdk-device-manager-gc"),
        RunSpec("gc-repaint-everything", "sdk-repaint-everything-gc"),
        RunSpec("gc-explicit-app-rect", "sdk-explicit-app-rect-gc"),
        RunSpec("refresh-screen-gc", "sdk-refresh-screen-gc"),
        RunSpec("refresh-screen-region-gc", "sdk-refresh-region-gc"),
        RunSpec("native-refresh-mode-5", "native-auto-5"),
        RunSpec("native-refresh-region-mode-5", "native-region-auto-5"),
        RunSpec("native-refresh-gc-0x62", "native-gc-0x62"),
        RunSpec("native-refresh-region-gc-0x62", "native-region-gc-0x62"),
        RunSpec("native-repaint-entire-panel-gc-0x62", "native-repaint-cache-probe"),
        RunSpec("controller-gc-once-only", "controller-gc-once-only"),
        RunSpec("sdm-gc-once-only", "sdm-gc-once-only"),
        RunSpec("controller-gc-once-invalidate", "controller-gc-once-invalidate"),
        RunSpec("sdm-gc-once-invalidate", "sdm-gc-once-invalidate"),
        RunSpec("gc-interval-false", "gc-interval-false"),
        RunSpec("gc-interval-true", "gc-interval-true"),
        RunSpec("gc-interval-without-regal", "gc-interval-without-regal"),
        RunSpec("gc-interval-with-regal", "gc-interval-with-regal"),
    ]
    modes: list[RunSpec] = []
    for native_mode in (0x02, 0x22, 0x42, 0x62):
        modes.append(
            RunSpec(
                "native-refresh-custom",
                f"native-view-0x{native_mode:02x}",
                native_mode=native_mode,
            )
        )
        modes.append(
            RunSpec(
                "native-refresh-region-custom",
                f"native-region-0x{native_mode:02x}",
                native_mode=native_mode,
            )
        )
    sweeps = [
        RunSpec("whole-black-white", "whole-black-white", black_ms=100, white_ms=100),
        RunSpec("checker-inverse-white", "checker-inverse-white", black_ms=100, white_ms=100),
        RunSpec("wait-controller", "wait-controller-16", bands=16, black_ms=0, white_ms=0),
        RunSpec("wait-device", "wait-device-16", bands=16, black_ms=0, white_ms=0),
    ]
    for bands in (2, 4, 8, 16, 32):
        sweeps.append(RunSpec("scrub", f"scrub-fixed-rate-{bands}", bands=bands, black_ms=75, white_ms=75))
    for bands in (2, 4, 8, 16, 32):
        dwell = max(15, round(2_000 / (2 * bands)))
        sweeps.append(
            RunSpec("scrub", f"scrub-fixed-total-{bands}", bands=bands, black_ms=dwell, white_ms=dwell)
        )
    queue = []
    for dwell in (15, 20, 30, 40, 50, 75, 100):
        queue.append(
            RunSpec("scrub", f"queue-16bands-{dwell}ms", bands=16, black_ms=dwell, white_ms=dwell)
        )
    for bands in (4, 8, 16, 32):
        queue.append(
            RunSpec("scrub", f"queue-{bands}bands-30ms", bands=bands, black_ms=30, white_ms=30)
        )
    queue.extend(
        [
            RunSpec("scrub", "queue-16bands-31ms-duplicate2", bands=16, black_ms=31, white_ms=31, duplicates=2),
            RunSpec("scrub", "queue-32bands-31ms-duplicate2", bands=32, black_ms=31, white_ms=31, duplicates=2),
            RunSpec("scrub", "queue-16bands-31ms-bottom-up", bands=16, black_ms=31, white_ms=31, bottom_to_top=True),
            RunSpec("scrub", "queue-32bands-31ms-bottom-up", bands=32, black_ms=31, white_ms=31, bottom_to_top=True),
        ]
    )
    for safety_ms in (0, 10, 30, 50, 75, 100):
        queue.append(
            RunSpec(
                "wait-controller",
                f"queue-wait-controller-safety-{safety_ms}ms",
                bands=16,
                black_ms=0,
                white_ms=0,
                safety_ms=safety_ms,
            )
        )
    for safety_ms in (0, 30, 75):
        queue.append(
            RunSpec(
                "wait-device",
                f"queue-wait-device-safety-{safety_ms}ms",
                bands=16,
                black_ms=0,
                white_ms=0,
                safety_ms=safety_ms,
            )
        )
    regions = []
    geometry_cases = (
        ("origin-100x100", 0, 0, 100, 100),
        ("left100-100x100", 100, 0, 100, 100),
        ("top100-100x100", 0, 100, 100, 100),
        ("offset100-100-100x100", 100, 100, 100, 100),
        ("origin-200x100", 0, 0, 200, 100),
        ("origin-100x200", 0, 0, 100, 200),
        ("top-left-quarter", 0, 0, 412, 749),
        ("left-half", 0, 0, 412, 1498),
        ("bottom-half", 0, 749, 824, 749),
        ("center", 206, 374, 412, 750),
    )
    for native_mode in (0x02, 0x42):
        for label, left, top, width, height in geometry_cases:
            regions.append(
                RunSpec(
                    "native-refresh-region-custom",
                    f"region-0x{native_mode:02x}-{label}",
                    native_mode=native_mode,
                    native_left=left,
                    native_top=top,
                    native_width=width,
                    native_height=height,
                )
            )
    area = []
    for native_mode in (0x02, 0x42):
        for divisor in (1, 2, 4, 8, 16, 32):
            for delay_ms in (0, 5, 10, 20, 30, 40, 50, 75, 100, 150, 200):
                area.append(
                    RunSpec(
                        "native-region-sequence",
                        f"area-0x{native_mode:02x}-1of{divisor}-{delay_ms}ms",
                        native_mode=native_mode,
                        native_sequence="same",
                        sequence_delay_ms=delay_ms,
                        sequence_count=12,
                        region_divisor=divisor,
                        quiet_ms=500,
                    )
                )
    sequences = []
    for sequence_name in ("abc", "aba", "overlap", "large-small", "small-large", "same"):
        for delay_ms in (0, 5, 10, 20, 30, 50, 75, 100, 150, 200):
            sequences.append(
                RunSpec(
                    "native-region-sequence",
                    f"sequence-{sequence_name}-{delay_ms}ms",
                    native_mode=0x02,
                    native_sequence=sequence_name,
                    sequence_delay_ms=delay_ms,
                    sequence_count=18,
                    region_divisor=4,
                    quiet_ms=500,
                )
            )
    groups = {
        "inventory": inventory,
        "api": api,
        "modes": modes,
        "sweeps": sweeps,
        "queue": queue,
        "regions": regions,
        "area": area,
        "sequences": sequences,
        "focused": modes + regions + area + sequences,
    }
    if selection == "all":
        return inventory + api + modes + sweeps + queue + regions + area + sequences
    return groups[selection]


def capture_state(adb: Adb, output_dir: Path, name: str) -> None:
    commands = {
        "getprop": ("shell", "getprop"),
        "package": ("shell", "dumpsys", "package", PACKAGE),
        "display": ("shell", "dumpsys", "display"),
        "window": ("shell", "dumpsys", "window"),
        "thermalservice": ("shell", "dumpsys", "thermalservice"),
        "thermal-zones": (
            "shell",
            "for z in /sys/class/thermal/thermal_zone*; do "
            "[ -r \"$z/type\" ] || continue; "
            "printf '%s type=' \"$z\"; cat \"$z/type\"; "
            "printf ' temp='; cat \"$z/temp\" 2>/dev/null || true; done",
        ),
        "services": ("shell", "dumpsys", "-l"),
        "commands": ("shell", "cmd", "-l"),
        "surfaceflinger": ("shell", "dumpsys", "SurfaceFlinger"),
        "atrace-categories": ("shell", "atrace", "--list_categories"),
        "logcat-buffers": ("logcat", "-b", "all", "-g"),
        "dmesg-access": ("shell", "dmesg"),
    }
    for suffix, command in commands.items():
        try:
            text = adb.run(*command, check=False, timeout=30)
        except Exception as error:  # diagnostic capture must not abort the matrix
            text = f"capture failed: {error}\n"
        (output_dir / f"{name}-{suffix}.txt").write_text(text, encoding="utf-8")


def filtered_lines(raw: str) -> list[str]:
    return [line for line in raw.splitlines() if FILTER_RE.search(line)]


def slice_run(raw: str, host_run_id: str) -> str:
    lines = raw.splitlines()
    begin_marker = f"AUTOMATION_HOST_BEGIN_{host_run_id}"
    end_marker = f"AUTOMATION_HOST_END_{host_run_id}"
    starts = [index for index, line in enumerate(lines) if begin_marker in line]
    if not starts:
        return raw
    start = starts[-1]
    end = next((index for index in range(start, len(lines)) if end_marker in lines[index]), len(lines) - 1)
    return "\n".join(lines[start : end + 1]) + "\n"


def parse_run(spec: RunSpec, host_run_id: str, raw: str, duration_s: float, timed_out: bool) -> dict:
    filtered = filtered_lines(slice_run(raw, host_run_id))
    run_start = next((index for index, line in enumerate(filtered) if "RUN_BEGIN" in line), 0)
    run_end = next(
        (index for index in range(run_start, len(filtered)) if "RUN_END" in filtered[index]),
        len(filtered) - 1,
    )
    run_window = filtered[run_start : run_end + 1]
    sf_events = []
    for line in run_window:
        match = SF_RE.search(line)
        if not match:
            continue
        geometry_match = SF_GEOMETRY_RE.match(match.group(1))
        sf_events.append(
            {
                "rect": match.group(1),
                "rawGeometry": [int(value) for value in geometry_match.groups()] if geometry_match else None,
                "waveform": int(match.group(2)),
                "flags": match.group(3),
                "marker": int(match.group(4)),
            }
        )
    sdm_events = [
        {
            "marker": int(match.group(1)),
            "waveform": int(match.group(2)),
            "updateMode": int(match.group(3)),
            "rect": match.group(4),
            "flags": match.group(5),
        }
        for line in run_window
        if (match := SDM_RE.search(line))
    ]
    request_indexes = [
        index
        for index, line in enumerate(run_window)
        if "PalmaScreenScrub" in line and ("STRIP " in line or "WAIT_STRIP" in line)
    ]
    sdm_indexes = [index for index, line in enumerate(run_window) if SDM_RE.search(line)]
    request_sdm_latencies: list[int] = []
    for request_number, request_index in enumerate(request_indexes):
        next_request = request_indexes[request_number + 1] if request_number + 1 < len(request_indexes) else len(run_window)
        matching_sdm = next((index for index in sdm_indexes if request_index < index < next_request), None)
        if matching_sdm is None:
            continue
        request_time = TIMESTAMP_RE.search(run_window[request_index])
        sdm_time = TIMESTAMP_RE.search(run_window[matching_sdm])
        if request_time and sdm_time:
            request_dt = dt.datetime.strptime(request_time.group(1), "%m-%d %H:%M:%S.%f")
            sdm_dt = dt.datetime.strptime(sdm_time.group(1), "%m-%d %H:%M:%S.%f")
            request_sdm_latencies.append(round((sdm_dt - request_dt).total_seconds() * 1_000))
    submit_indexes = [index for index, line in enumerate(run_window) if SUBMIT_BEGIN_RE.search(line)]
    submit_return_by_sequence = {
        int(match.group(1)): int(match.group(2))
        for line in run_window
        if (match := SUBMIT_RETURN_RE.search(line))
    }
    submissions = []
    sf_indexes = [index for index, line in enumerate(run_window) if SF_RE.search(line)]
    for submit_number, submit_index in enumerate(submit_indexes):
        begin_match = SUBMIT_BEGIN_RE.search(run_window[submit_index])
        if not begin_match:
            continue
        next_submit = submit_indexes[submit_number + 1] if submit_number + 1 < len(submit_indexes) else len(run_window)
        sf_index = next((index for index in sf_indexes if submit_index < index < next_submit), None)
        sdm_index = next((index for index in sdm_indexes if submit_index < index < next_submit), None)
        sf_event = None
        sf_latency = None
        if sf_index is not None:
            sf_match = SF_RE.search(run_window[sf_index])
            if sf_match:
                sf_event = {
                    "rect": sf_match.group(1),
                    "rawGeometry": (
                        [int(value) for value in geometry_match.groups()]
                        if (geometry_match := SF_GEOMETRY_RE.match(sf_match.group(1)))
                        else None
                    ),
                    "waveform": int(sf_match.group(2)),
                    "flags": sf_match.group(3),
                    "marker": int(sf_match.group(4)),
                }
            submit_time = TIMESTAMP_RE.search(run_window[submit_index])
            sf_time = TIMESTAMP_RE.search(run_window[sf_index])
            if submit_time and sf_time:
                submit_dt = dt.datetime.strptime(submit_time.group(1), "%m-%d %H:%M:%S.%f")
                sf_dt = dt.datetime.strptime(sf_time.group(1), "%m-%d %H:%M:%S.%f")
                sf_latency = round((sf_dt - submit_dt).total_seconds() * 1_000)
        sequence = int(begin_match.group(1))
        submissions.append(
            {
                "sequence": sequence,
                "label": begin_match.group(2),
                "requestedRect": [int(begin_match.group(index)) for index in range(3, 7)],
                "mode": begin_match.group(7),
                "returnLatencyMs": submit_return_by_sequence.get(sequence),
                "surfaceFlinger": sf_event,
                "surfaceFlingerLatencyMs": sf_latency,
                "sdmBeforeNextSubmit": sdm_index is not None,
            }
        )
    sf_markers = [event["marker"] for event in sf_events]
    marker_gaps = [current - previous for previous, current in zip(sf_markers, sf_markers[1:])]
    marker_echoes = {}
    for marker in sf_markers:
        marker_pattern = re.compile(rf"marker\[?{marker}\]?", re.IGNORECASE)
        marker_echoes[str(marker)] = [
            line for line in run_window if "SurfaceFlinger: refresh screen" not in line and marker_pattern.search(line)
        ]
    real_waits = [
        {
            "band": int(match.group(1)),
            "bandCount": int(match.group(2)),
            "phase": match.group(3),
            "durationMs": int(match.group(4)),
            "result": match.group(5),
        }
        for line in run_window
        if (match := REAL_WAIT_RETURN_RE.search(line))
    ]
    global_waits = [
        {
            "durationMs": int(match.group(1)),
            "result": match.group(2),
        }
        for line in run_window
        if (match := GLOBAL_WAIT_RETURN_RE.search(line))
    ]
    global_wait_begin_index = next(
        (index for index, line in enumerate(run_window) if "GLOBAL_GC_WAIT WAIT_BEGIN" in line),
        None,
    )
    call_begin_index = next(
        (index for index, line in enumerate(run_window) if "CALL_BEGIN" in line),
        None,
    )
    global_sdm_before_wait = (
        global_wait_begin_index is not None
        and call_begin_index is not None
        and any(call_begin_index < index < global_wait_begin_index for index in sdm_indexes)
    )
    return {
        "visualVerification": "NO VISUAL VERIFICATION PERFORMED",
        "hostRunId": host_run_id,
        "label": spec.label,
        "spec": dataclasses.asdict(spec),
        "hostDurationSeconds": round(duration_s, 3),
        "timedOut": timed_out,
        "runBegin": next((line for line in filtered if "RUN_BEGIN" in line), None),
        "callBegin": next((line for line in filtered if "CALL_BEGIN" in line), None),
        "callReturn": next((line for line in filtered if "CALL_RETURN" in line), None),
        "runEnd": next((line for line in filtered if "RUN_END" in line), None),
        "automationEnd": next((line for line in filtered if f"AUTOMATION_END hostRunId={host_run_id}" in line), None),
        "automationRejected": next((line for line in filtered if "AUTOMATION_REJECTED" in line), None),
        "surfaceFlinger": sf_events,
        "waveformWrongCount": sum("waveform_mode  wrong" in line for line in run_window),
        "sdmEventCount": sum("SDM" in line and "update_to_display" in line for line in filtered),
        "sdmDuringRun": sdm_events,
        "sdmDuringRunCount": len(sdm_events),
        "requestCount": len(request_indexes),
        "requestSdmMatchedCount": len(request_sdm_latencies),
        "requestSdmLatenciesMs": request_sdm_latencies,
        "realWaitCount": len(real_waits),
        "realWaitDurationsMs": [item["durationMs"] for item in real_waits],
        "realWaitUnavailableCount": sum("unavailable" in item["result"].lower() for item in real_waits),
        "realWaits": real_waits,
        "globalWaitCount": len(global_waits),
        "globalWaitDurationsMs": [item["durationMs"] for item in global_waits],
        "globalWaitUnavailableCount": sum("unavailable" in item["result"].lower() for item in global_waits),
        "globalWaits": global_waits,
        "globalSdmObservedBeforeWaitBegin": global_sdm_before_wait,
        "submissions": submissions,
        "submissionCount": len(submissions),
        "submissionSfMatchedCount": sum(item["surfaceFlinger"] is not None for item in submissions),
        "submissionSdmBeforeNextCount": sum(item["sdmBeforeNextSubmit"] for item in submissions),
        "surfaceFlingerMarkerGaps": marker_gaps,
        "surfaceFlingerMarkerReuse": len(sf_markers) - len(set(sf_markers)),
        "surfaceFlingerMarkerEchoes": marker_echoes,
        "apparentMissingSurfaceFlinger": max(0, len(submissions) - len(sf_events)),
        "fatalOrAnr": any("FATAL EXCEPTION" in line or "ANR in" in line for line in filtered),
        "filteredLineCount": len(filtered),
    }


def run_one(adb: Adb, spec: RunSpec, index: int, output_dir: Path) -> dict:
    host_run_id = f"r{index:03d}-{spec.label}"[:80]
    adb.run("logcat", "-b", "all", "-c", check=False)
    adb.run("shell", "am", "force-stop", PACKAGE, check=False)
    adb.run("shell", "log", "-t", "PalmaHostMarker", f"AUTOMATION_HOST_BEGIN_{host_run_id}")
    command = [
        "shell", "am", "start", "-W", "-n", COMPONENT, "-a", ACTION,
        "--es", "experiment", spec.experiment,
        "--es", "hostRunId", host_run_id,
        "--ez", "confirmSafe", "true",
        "--ei", "bands", str(spec.bands),
        "--el", "blackMs", str(spec.black_ms),
        "--el", "whiteMs", str(spec.white_ms),
        "--el", "safetyMs", str(spec.safety_ms),
        "--el", "quietMs", str(spec.quiet_ms),
        "--ei", "repeats", str(spec.repeats),
        "--ei", "duplicates", str(spec.duplicates),
        "--ez", "bottomToTop", str(spec.bottom_to_top).lower(),
        "--es", "overlayPresentation", spec.overlay_presentation,
    ]
    if spec.native_mode is not None:
        command.extend(("--ei", "nativeMode", str(spec.native_mode)))
    if spec.alternate_native_mode is not None:
        command.extend(("--ei", "alternateNativeMode", str(spec.alternate_native_mode)))
    for extra, value in (
        ("nativeLeft", spec.native_left),
        ("nativeTop", spec.native_top),
        ("nativeWidth", spec.native_width),
        ("nativeHeight", spec.native_height),
        ("sequenceCount", spec.sequence_count if spec.native_sequence is not None else None),
        ("regionDivisor", spec.region_divisor if spec.native_sequence is not None else None),
    ):
        if value is not None:
            command.extend(("--ei", extra, str(value)))
    if spec.native_sequence is not None:
        command.extend(("--es", "nativeSequence", spec.native_sequence))
        command.extend(("--el", "sequenceDelayMs", str(spec.sequence_delay_ms)))
    if spec.camera_mode:
        command.extend(("--ez", "cameraMode", "true"))
        command.extend(("--ei", "cameraIndex", str(spec.camera_index)))
        command.extend(("--ei", "cameraTotal", str(spec.camera_total)))
        command.extend(("--es", "cameraProfile", remote_shell_quote(spec.camera_profile)))
        command.extend(("--es", "cameraDetail", remote_shell_quote(spec.camera_detail or spec.label)))
        command.extend(("--el", "cameraSlateMs", str(spec.camera_slate_ms)))
        command.extend(("--el", "cameraPreconditionMs", str(spec.camera_precondition_ms)))
        command.extend(("--el", "cameraBaselineMs", str(spec.camera_baseline_ms)))
        if spec.camera_title:
            command.extend(("--es", "cameraTitle", remote_shell_quote(spec.camera_title)))
    started = time.monotonic()
    launch_output = adb.run(*command, check=False, timeout=30)
    deadline = started + spec.timeout_seconds()
    raw = ""
    timed_out = True
    while time.monotonic() < deadline:
        raw = adb.run("logcat", "-b", "all", "-d", "-v", "threadtime", check=False, timeout=30)
        if f"AUTOMATION_END hostRunId={host_run_id}" in raw:
            timed_out = False
            break
        time.sleep(0.25)
    adb.run("shell", "log", "-t", "PalmaHostMarker", f"AUTOMATION_HOST_END_{host_run_id}", check=False)
    raw = adb.run("logcat", "-b", "all", "-d", "-v", "threadtime", check=False, timeout=30)
    run_slice = slice_run(raw, host_run_id)
    duration = time.monotonic() - started
    stem = f"{index:03d}-{spec.label}"
    (output_dir / f"{stem}-launch.txt").write_text(launch_output, encoding="utf-8")
    (output_dir / f"{stem}-raw-logcat.txt").write_text(raw, encoding="utf-8")
    (output_dir / f"{stem}-filtered.txt").write_text("\n".join(filtered_lines(run_slice)) + "\n", encoding="utf-8")
    record = parse_run(spec, host_run_id, raw, duration, timed_out)
    (output_dir / f"{stem}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def markdown_report(records: Iterable[dict], metadata: dict) -> str:
    rows = []
    for record in records:
        sf = "; ".join(
            f"wf={event['waveform']} flags={event['flags']} rect={event['rect']} marker={event['marker']}"
            for event in record["surfaceFlinger"]
        ) or "none"
        call = (record["callReturn"] or "none").split("CALL_RETURN", 1)[-1].strip()
        rows.append(
            f"| {record['label']} | {record['timedOut']} | {record['waveformWrongCount']} | "
            f"{record['requestCount']} | {record['requestSdmMatchedCount']} | "
            f"{record.get('submissionCount', 0)} | {record.get('submissionSfMatchedCount', 0)} | "
            f"{record.get('apparentMissingSurfaceFlinger', 0)} | {record['sdmDuringRunCount']} | "
            f"{record.get('surfaceFlingerMarkerReuse', 0)} | {sf} | {call.replace('|', '/')} |"
        )
    return "\n".join(
        [
            "# Palma unattended EPD matrix",
            "",
            "> No visual verification performed. These results describe software submission and logging only.",
            "> **NO VISUAL VERIFICATION PERFORMED.**",
            "",
            f"- Generated: {metadata['generatedAt']}",
            f"- Device serial: `{metadata['serial']}`",
            f"- Matrix: `{metadata['matrix']}`",
            f"- App APK SHA-256: `{metadata.get('apkSha256', 'unknown')}`",
            "",
            "| Run | Timed out | Wrong waveform | Draw requests | Draw→SDM | Native submits | Submit→SF | Missing SF | SDM during run | Marker reuse | SurfaceFlinger refresh | App call return |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |",
            *rows,
            "",
            "## Interpretation boundary",
            "",
            "- A SurfaceFlinger event confirms a submitted software request and its logged rectangle/mode.",
            "- It does not prove every physical E Ink tile was driven or that ghosting cleared.",
            "- SDM event counts are contextual only because the accessible SDM stream includes unrelated redraws.",
            "- Apparent missing/merged/superseded counts describe temporal software-log correlation only.",
        ]
    ) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", help="Path to adb.exe")
    parser.add_argument("--serial", help="ADB serial; required if more than one device is connected")
    parser.add_argument(
        "--matrix",
        choices=("inventory", "api", "modes", "sweeps", "queue", "regions", "area", "sequences", "focused", "all"),
        default="all",
    )
    parser.add_argument("--output", type=Path, help="Output directory")
    parser.add_argument("--resume", action="store_true", help="Resume an existing output directory")
    parser.add_argument("--case-repeats", type=int, default=1, help="Repeat each matrix case with unique labels")
    parser.add_argument("--limit", type=int, help="Run only the first N remaining cases")
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

    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    default_output = Path(__file__).resolve().parents[1].parent / f"unattended-matrix-{timestamp}"
    output_dir = (args.output or default_output).resolve()
    output_dir.mkdir(parents=True, exist_ok=args.resume)
    apk_path = Path(__file__).resolve().parents[1] / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_hash = "unknown"
    if apk_path.is_file():
        import hashlib

        apk_hash = hashlib.sha256(apk_path.read_bytes()).hexdigest().upper()
    metadata = {
        "generatedAt": dt.datetime.now().astimezone().isoformat(),
        "serial": serial,
        "matrix": args.matrix,
        "adb": str(adb_path),
        "apkSha256": apk_hash,
    }
    metadata_path = output_dir / "metadata.json"
    if not args.resume or not metadata_path.exists():
        metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        capture_state(adb, output_dir, "before")

    runs_path = output_dir / "runs.json"
    records = json.loads(runs_path.read_text(encoding="utf-8")) if args.resume and runs_path.exists() else []
    existing_labels = {record["label"] for record in records}
    base_specs = matrix(args.matrix)
    specs = []
    for repeat_index in range(1, max(1, args.case_repeats) + 1):
        for spec in base_specs:
            label = spec.label if args.case_repeats == 1 else f"{spec.label}-rep{repeat_index:02d}"
            specs.append(dataclasses.replace(spec, label=label))
    specs = [spec for spec in specs if spec.label not in existing_labels]
    if args.limit is not None:
        specs = specs[: max(0, args.limit)]
    start_index = len(records) + 1
    total_remaining = len(specs)
    for offset, spec in enumerate(specs):
        index = start_index + offset
        print(f"[{offset + 1}/{total_remaining}] {spec.label}", flush=True)
        record = run_one(adb, spec, index, output_dir)
        records.append(record)
        runs_path.write_text(json.dumps(records, indent=2), encoding="utf-8")
        status = "TIMEOUT" if record["timedOut"] else "ok"
        print(f"  {status}; SF={len(record['surfaceFlinger'])}; wrong={record['waveformWrongCount']}", flush=True)

    capture_state(adb, output_dir, "after")
    (output_dir / "summary.md").write_text(markdown_report(records, metadata), encoding="utf-8")
    failures = [record for record in records if record["timedOut"] or record["fatalOrAnr"]]
    print(f"Completed {len(records)} runs; failures={len(failures)}; output={output_dir}")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
