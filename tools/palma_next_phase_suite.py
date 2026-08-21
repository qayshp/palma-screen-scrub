#!/usr/bin/env python3
"""Run the unattended Regal, fast-overlay, and transparency camera suite."""

from __future__ import annotations

import argparse
import csv
import dataclasses
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from palma_camera_suite import next_phase_overlay_cases
from palma_epd_test import Adb, RunSpec, connected_serials, resolve_adb, run_one


PACKAGE = "dev.palma.screenscrub"
COMPONENT = f"{PACKAGE}/.AutomationAlias"
ACTION = f"{PACKAGE}.action.AUTOMATION"
LAUNCHER = "com.microsoft.launcher/.Launcher"
SETTINGS = "com.android.settings/.Settings"
MODE_ORDERS = (("Speed", "Regal", "HD"), ("HD", "Regal", "Speed"))
DISPLAY_FILTER = re.compile(
    r"PalmaHostMarker|PalmaScreenScrub|SurfaceFlinger|SDM|transferEpdc|commitEpdc|clearEpdcList|"
    r"waveform|update_mode|flags|Rect\[|marker|CMD_TCON|EUS|debounce|scheme|A2 mode|display mode|EBC|TCON",
    re.IGNORECASE,
)
REFRESH_MODES = {"Regal", "HD", "Speed"}


def now() -> str:
    return dt.datetime.now().astimezone().isoformat()


def bounds_center(bounds: str) -> tuple[int, int]:
    values = [int(value) for value in re.findall(r"\d+", bounds)]
    if len(values) != 4:
        raise RuntimeError(f"invalid UI bounds: {bounds}")
    return (values[0] + values[2]) // 2, (values[1] + values[3]) // 2


class BooxUi:
    def __init__(self, adb: Adb, raw_dir: Path):
        self.adb = adb
        self.raw_dir = raw_dir
        size = adb.run("shell", "wm", "size")
        match = re.search(r"(\d+)x(\d+)", size)
        if not match:
            raise RuntimeError(f"cannot resolve display size: {size}")
        self.width, self.height = map(int, match.groups())
        self.panel_x = round(self.width * 548 / 824)
        self.refresh_x = round(self.width * 686 / 824)
        self.nav_y = round(self.height * 1600 / 1648)

    def dump(self, stem: str) -> tuple[ET.Element, Path]:
        remote = "/sdcard/palma-next-phase-ui.xml"
        path = self.raw_dir / f"{stem}.xml"
        self.adb.run("shell", "uiautomator", "dump", remote, check=False, timeout=15)
        self.adb.run("pull", remote, str(path), timeout=15)
        return ET.parse(path).getroot(), path

    @staticmethod
    def panel_state(root: ET.Element) -> dict:
        parent = {child: node for node in root.iter() for child in node}
        title = ""
        modes: list[str] = []
        selected = ""
        for node in root.iter("node"):
            resource_id = node.attrib.get("resource-id", "")
            if resource_id == "com.android.systemui:id/e_ink_app_title":
                title = node.attrib.get("text", "")
            if resource_id != "com.android.systemui:id/e_ink_radio_text_view":
                continue
            text = node.attrib.get("text", "")
            if text not in REFRESH_MODES:
                continue
            modes.append(text)
            ancestor = parent.get(node)
            while ancestor is not None:
                if ancestor.attrib.get("selected") == "true":
                    selected = text
                    break
                ancestor = parent.get(ancestor)
        return {"title": title, "modes": modes, "selected": selected}

    def ensure_panel(self, stem: str) -> tuple[ET.Element, dict, Path]:
        root, path = self.dump(f"{stem}-before-open")
        state = self.panel_state(root)
        if not state["title"]:
            self.adb.run("shell", "input", "tap", str(self.panel_x), str(self.nav_y))
            time.sleep(1.2)
            root, path = self.dump(f"{stem}-panel")
            state = self.panel_state(root)
        if not state["title"]:
            raise RuntimeError("BOOX E-Ink panel did not open at the previously verified navigation control")
        return root, state, path

    def select_mode(self, mode: str, stem: str) -> dict:
        root, before, before_path = self.ensure_panel(stem)
        if mode not in before["modes"]:
            raise RuntimeError(f"mode {mode!r} unavailable for {before['title']!r}: {before['modes']}")
        if before["selected"] != mode:
            parent = {child: node for node in root.iter() for child in node}
            target = next(
                node
                for node in root.iter("node")
                if node.attrib.get("resource-id") == "com.android.systemui:id/e_ink_radio_text_view"
                and node.attrib.get("text") == mode
            )
            clickable = parent.get(target)
            while clickable is not None and clickable.attrib.get("clickable") != "true":
                clickable = parent.get(clickable)
            if clickable is None:
                raise RuntimeError(f"no clickable ancestor found for BOOX mode {mode}")
            x, y = bounds_center(clickable.attrib["bounds"])
            self.adb.run("shell", "input", "tap", str(x), str(y))
            time.sleep(1.5)
        after_root, after_path = self.dump(f"{stem}-verified")
        after = self.panel_state(after_root)
        if after["selected"] != mode:
            raise RuntimeError(f"BOOX mode selection did not verify: requested={mode}, observed={after}")
        return {
            "before": before,
            "after": after,
            "beforeHierarchy": str(before_path),
            "afterHierarchy": str(after_path),
        }

    def full_refresh(self, mode: str, pass_index: int) -> dict:
        host = now()
        device = self.adb.run("shell", "date", "+%Y-%m-%dT%H:%M:%S%z").strip()
        marker = f"BOOX_FULL_REFRESH mode={mode} pass={pass_index}/3 host={host} device={device}"
        self.adb.run("shell", "log", "-t", "PalmaHostMarker", marker)
        self.adb.run("shell", "input", "tap", str(self.refresh_x), str(self.nav_y))
        return {"pass": pass_index, "hostTap": host, "deviceTap": device, "marker": marker}


def wait_for_log(adb: Adb, needle: str, timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        raw = adb.run("logcat", "-b", "all", "-d", "-v", "threadtime", check=False)
        if needle in raw:
            return raw
        time.sleep(0.25)
    raise TimeoutError(f"timed out waiting for log marker: {needle}")


def launch_automation(adb: Adb, experiment: str, host_run_id: str, extras: list[str]) -> str:
    command = [
        "shell", "am", "start", "-W", "-n", COMPONENT, "-a", ACTION,
        "--es", "experiment", experiment,
        "--es", "hostRunId", host_run_id,
        "--ez", "confirmSafe", "true",
        *extras,
    ]
    return adb.run(*command, check=False, timeout=30)


def show_slate(adb: Adb, index: int, total: int, title: str, detail: str, duration_ms: int = 2_500) -> None:
    host_run_id = f"slate-{index:03d}"
    launch_automation(
        adb,
        "camera-slate",
        host_run_id,
        [
            "--ez", "cameraMode", "true",
            "--ei", "cameraIndex", str(index),
            "--ei", "cameraTotal", str(total),
            "--es", "cameraProfile", "PALMA NEXT-PHASE CAMERA SUITE",
            "--es", "cameraTitle", title,
            "--es", "cameraDetail", detail,
            "--el", "cameraSlateMs", str(duration_ms),
        ],
    )
    wait_for_log(adb, f"AUTOMATION_END hostRunId={host_run_id}", duration_ms / 1000 + 8)


def capture_temperature(adb: Adb) -> dict:
    battery = adb.run("shell", "dumpsys", "battery", check=False)
    thermal = adb.run(
        "shell", "sh", "-c",
        "'for z in /sys/class/thermal/thermal_zone*; do cat $z/type 2>/dev/null; cat $z/temp 2>/dev/null; done'",
        check=False,
    )
    return {"battery": battery, "thermal": thermal}


def parse_display_events(raw: str) -> dict:
    filtered = [line for line in raw.splitlines() if DISPLAY_FILTER.search(line)]
    waveforms = sorted(set(re.findall(r"waveform_mode\s*[=:]?\s*(\d+)", raw)))
    update_modes = sorted(set(re.findall(r"update_mode\s*[=:]?\s*(\d+)", raw)))
    flags = sorted(set(re.findall(r"flags\s*[=:]?\s*(0x[0-9a-fA-F]+|[0-9a-fA-F]+)", raw)))
    rectangles = sorted(set(re.findall(r"Rect\[([^]]+)\]", raw)))
    markers = sorted(set(re.findall(r"marker\[?(\d+)\]?", raw)))
    tcon = sorted(set(re.findall(r"CMD_TCON_[A-Z0-9_]+", raw)))
    return {
        "waveform": waveforms,
        "updateMode": update_modes,
        "flags": flags,
        "rectangle": rectangles,
        "marker": markers,
        "tconCommand": tcon,
        "filteredLines": filtered,
    }


def foreground_package(adb: Adb) -> str:
    activities = adb.run("shell", "dumpsys", "activity", "activities", check=False)
    match = re.search(r"mResumedActivity:.*? ([A-Za-z0-9._]+)/(?:[A-Za-z0-9._$]+)", activities)
    return match.group(1) if match else "unknown"


def run_regal_trial(
    adb: Adb,
    ui: BooxUi,
    output_dir: Path,
    index: int,
    total: int,
    block: int,
    trial_in_block: int,
    mode: str,
) -> dict:
    label = f"regal-block{block}-{trial_in_block}-{mode.lower()}"
    run_dir = output_dir / "camera" / "runs"
    show_slate(
        adb,
        index,
        total,
        f"RUN {index:03d} / {total:03d}",
        f"REGAL PHYSICAL TEST | MODE {mode.upper()} | TRIAL {block}/2 | 3 FULL REFRESHES",
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
            "--el", "quietMs", "26000",
            "--el", "cameraPreconditionMs", "600",
            "--es", "overlayPresentation", "t0",
        ],
    )
    wait_for_log(adb, "SEED_WHITE_HOLD_BEGIN", 12)
    resumed = foreground_package(adb)
    selection = ui.select_mode(mode, f"run-{index:03d}-{mode.lower()}")
    time.sleep(2.0)
    refreshes = []
    for pass_index in range(1, 4):
        refreshes.append(ui.full_refresh(mode, pass_index))
        time.sleep(5.0)
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
        "section": "A_REGAL_HD_SPEED",
        "testName": label,
        "parameters": {"mode": mode, "trialBlock": block, "orderInBlock": trial_in_block, "refreshPasses": 3},
        "hostStart": host_start,
        "deviceStart": device_start,
        "hostEnd": host_end,
        "deviceEnd": device_end,
        "foregroundPackage": resumed,
        "booxSelectedMode": selection["after"]["selected"],
        "modeSelection": selection,
        "refreshes": refreshes,
        "temperatureProxy": temperature,
        "scheme": "see filtered log",
        "displayMode": "see filtered log",
        "a2": "see filtered log",
        "eus": "see filtered log",
        "et": "see filtered log",
        "debounce": "see filtered log",
        **parsed,
        "overlayMode": "T0 seed hold",
        "bandCount": None,
        "direction": None,
        "duplicateCount": None,
        "phaseTiming": "checker 600 ms; inverse 600 ms; white hold 26000 ms",
        "actualElapsedDuration": None,
        "errors": [],
        "physicalResult": "PENDING_CAMERA",
    }
    (run_dir / f"{stem}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def overlay_record(spec: RunSpec, parsed: dict, index: int) -> dict:
    return {
        "run": index,
        "section": "B_FAST_OVERLAY" if index <= 31 else "C_PROGRESSIVE_TRANSPARENCY",
        "testName": spec.label,
        "parameters": dataclasses.asdict(spec),
        "hostStart": parsed.get("runBegin"),
        "deviceStart": None,
        "hostEnd": parsed.get("runEnd"),
        "deviceEnd": None,
        "foregroundPackage": "captured in run log",
        "booxSelectedMode": "unchanged",
        "scheme": "see run log",
        "displayMode": "see run log",
        "a2": "see run log",
        "eus": "see run log",
        "et": "see run log",
        "debounce": "see run log",
        "waveform": [event["waveform"] for event in parsed.get("surfaceFlinger", [])],
        "updateMode": [event["updateMode"] for event in parsed.get("sdmDuringRun", [])],
        "flags": [event["flags"] for event in parsed.get("surfaceFlinger", [])],
        "rectangle": [event["rect"] for event in parsed.get("surfaceFlinger", [])],
        "marker": [event["marker"] for event in parsed.get("surfaceFlinger", [])],
        "tconCommand": [],
        "overlayMode": spec.overlay_presentation.upper(),
        "bandCount": spec.bands,
        "direction": "bottom-to-top" if spec.bottom_to_top else "top-to-bottom",
        "duplicateCount": spec.duplicates,
        "phaseTiming": {"blackMs": spec.black_ms, "whiteMs": spec.white_ms},
        "actualElapsedDuration": parsed.get("hostDurationSeconds"),
        "errors": [value for value in (parsed.get("automationRejected"),) if value],
        "physicalResult": "PENDING_CAMERA",
    }


def planned_records() -> list[dict]:
    records: list[dict] = []
    index = 1
    for block, order in enumerate(MODE_ORDERS, 1):
        for trial_in_block, mode in enumerate(order, 1):
            records.append(
                {
                    "run": index,
                    "section": "A_REGAL_HD_SPEED",
                    "testName": f"regal-block{block}-{trial_in_block}-{mode.lower()}",
                    "parameters": {"mode": mode, "trialBlock": block, "orderInBlock": trial_in_block, "refreshPasses": 3},
                    "booxSelectedMode": mode,
                    "overlayMode": "T0 seed hold",
                    "bandCount": None,
                    "direction": None,
                    "duplicateCount": None,
                    "phaseTiming": "checker 600 ms; inverse 600 ms; white hold 26000 ms",
                    "waveform": [],
                    "updateMode": [],
                    "flags": [],
                    "rectangle": [],
                    "marker": [],
                    "tconCommand": [],
                    "physicalResult": "PENDING_CAMERA",
                }
            )
            index += 1
    for spec in next_phase_overlay_cases():
        records.append(
            {
                "run": index,
                "section": "B_FAST_OVERLAY" if index <= 31 else "C_PROGRESSIVE_TRANSPARENCY",
                "testName": spec.label,
                "parameters": dataclasses.asdict(spec),
                "booxSelectedMode": "unchanged",
                "overlayMode": spec.overlay_presentation.upper(),
                "bandCount": spec.bands,
                "direction": "bottom-to-top" if spec.bottom_to_top else "top-to-bottom",
                "duplicateCount": spec.duplicates,
                "phaseTiming": {"blackMs": spec.black_ms, "whiteMs": spec.white_ms},
                "waveform": [],
                "updateMode": [],
                "flags": [],
                "rectangle": [],
                "marker": [],
                "tconCommand": [],
                "physicalResult": "PENDING_CAMERA",
            }
        )
        index += 1
    return records


def write_manifests(output_dir: Path, records: list[dict], metadata: dict) -> None:
    (output_dir / "camera_manifest.json").write_text(json.dumps({"metadata": metadata, "runs": records}, indent=2), encoding="utf-8")
    fields = ["run", "section", "testName", "overlayMode", "bandCount", "direction", "duplicateCount", "phaseTiming", "booxSelectedMode", "waveform", "updateMode", "flags", "rectangle", "marker", "tconCommand", "physicalResult"]
    with (output_dir / "camera_manifest.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for record in records:
            writer.writerow({key: json.dumps(record.get(key)) if isinstance(record.get(key), (list, dict)) else record.get(key) for key in fields})
    rows = [
        "# Palma next-phase camera manifest",
        "",
        "Physical results remain `PENDING_CAMERA` until the external video is reviewed.",
        "",
        "| Run | Section | Test | BOOX mode | Overlay | Parameters | Physical result |",
        "| ---: | --- | --- | --- | --- | --- | --- |",
    ]
    for record in records:
        rows.append(
            f"| {record['run']:03d} | {record['section']} | {record['testName']} | "
            f"{record.get('booxSelectedMode', '')} | {record.get('overlayMode', '')} | "
            f"`{json.dumps(record.get('parameters', {}), separators=(',', ':'))}` | {record['physicalResult']} |"
        )
    (output_dir / "camera_manifest.md").write_text("\n".join(rows) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--smoke", action="store_true", help="Validate selectors/slates only; never run the physical matrix")
    args = parser.parse_args()
    adb_path = resolve_adb(args.adb)
    serials = connected_serials(adb_path)
    serial = args.serial or (serials[0] if len(serials) == 1 else "")
    if not serial or serial not in serials:
        raise RuntimeError(f"expected the connected Palma, found {serials}")
    adb = Adb(adb_path, serial)
    output_dir = args.output.resolve()
    camera_dir = output_dir / "camera"
    runs_dir = camera_dir / "runs"
    raw_dir = output_dir / "software-analysis" / "raw"
    runs_dir.mkdir(parents=True, exist_ok=True)
    raw_dir.mkdir(parents=True, exist_ok=True)
    ui = BooxUi(adb, raw_dir)
    initial = {
        "foreground": foreground_package(adb),
        "launcher": None,
        "settings": None,
    }
    total = 43
    records: list[dict] = []
    metadata = {"startedAt": now(), "serial": serial, "totalRuns": total, "physicalEvidence": "EXTERNAL CAMERA REQUIRED"}
    apk_path = Path(__file__).resolve().parents[1] / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    metadata["apkSha256"] = hashlib.sha256(apk_path.read_bytes()).hexdigest().upper()

    adb.run("shell", "am", "start", "-W", "-n", LAUNCHER)
    _, launcher_state, _ = ui.ensure_panel("suite-preflight-launcher")
    initial["launcher"] = launcher_state["selected"]
    adb.run("shell", "input", "keyevent", "BACK", check=False)
    adb.run("shell", "am", "start", "-W", "-n", SETTINGS)
    _, settings_state, _ = ui.ensure_panel("suite-preflight-settings")
    initial["settings"] = settings_state["selected"]
    adb.run("shell", "input", "keyevent", "BACK", check=False)
    metadata["initialState"] = initial

    if args.smoke:
        show_slate(adb, 1, total, "SMOKE TEST", "SELECTORS AND SLATE ONLY", 800)
        metadata["smokeOnly"] = True
        metadata["status"] = "PLANNED_NOT_RUN"
        write_manifests(output_dir, planned_records(), metadata)
        return 0

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
        show_slate(adb, 0, total, "PALMA NEXT-PHASE", "CAMERA SUITE STARTING | DO NOT TOUCH DEVICE", 5_000)
        launch_automation(adb, "camera-sync", "camera-sync", ["--el", "blackMs", "1000", "--el", "whiteMs", "1000"])
        wait_for_log(adb, "AUTOMATION_END hostRunId=camera-sync", 15)
        index = 1
        for block, order in enumerate(MODE_ORDERS, 1):
            for order_index, mode in enumerate(order, 1):
                records.append(run_regal_trial(adb, ui, output_dir, index, total, block, order_index, mode))
                write_manifests(output_dir, records, metadata)
                index += 1

        overlay_specs = next_phase_overlay_cases()
        for spec in overlay_specs:
            camera_spec = dataclasses.replace(
                spec,
                camera_index=index,
                camera_total=total,
                camera_profile="PALMA NEXT-PHASE CAMERA SUITE",
                camera_title=f"RUN {index:03d} / {total:03d}",
            )
            parsed = run_one(adb, camera_spec, index, runs_dir)
            records.append(overlay_record(camera_spec, parsed, index))
            write_manifests(output_dir, records, metadata)
            index += 1
        show_slate(adb, total + 1, total, "PALMA NEXT-PHASE", f"CAMERA SUITE COMPLETE | RUNS {len(records)} | PHYSICAL RESULTS PENDING", 10_000)
    finally:
        adb.run("shell", "am", "force-stop", PACKAGE, check=False)
        try:
            adb.run("shell", "am", "start", "-W", "-n", LAUNCHER)
            ui.select_mode(initial["launcher"] or "Regal", "restore-launcher")
            adb.run("shell", "input", "keyevent", "BACK", check=False)
            adb.run("shell", "am", "start", "-W", "-n", SETTINGS)
            ui.select_mode(initial["settings"] or "HD", "restore-settings")
            adb.run("shell", "input", "keyevent", "BACK", check=False)
        except Exception as error:
            restoration_errors.append(str(error))
        collector.terminate()
        try:
            collector.wait(timeout=5)
        except subprocess.TimeoutExpired:
            collector.kill()
        log_handle.close()
        raw = (camera_dir / "logcat.txt").read_text(encoding="utf-8", errors="replace")
        (camera_dir / "app.log").write_text("\n".join(line for line in raw.splitlines() if "PalmaScreenScrub" in line) + "\n", encoding="utf-8")
        (camera_dir / "surfaceflinger.txt").write_text("\n".join(line for line in raw.splitlines() if "SurfaceFlinger" in line) + "\n", encoding="utf-8")
        (camera_dir / "sdm.txt").write_text("\n".join(line for line in raw.splitlines() if "SDM" in line or "transferEpdc" in line or "commitEpdc" in line) + "\n", encoding="utf-8")
        (camera_dir / "tcon.txt").write_text("\n".join(line for line in raw.splitlines() if "TCON" in line or "EBC" in line) + "\n", encoding="utf-8")
        metadata["endedAt"] = now()
        metadata["restorationErrors"] = restoration_errors
        write_manifests(output_dir, records, metadata)
    return 1 if restoration_errors else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
