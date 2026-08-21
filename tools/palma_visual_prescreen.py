#!/usr/bin/env python3
"""Run one visual workaround prescreen candidate and hold its final white state."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import time
from pathlib import Path

from palma_epd_test import Adb, connected_serials, resolve_adb
from palma_next_phase_suite import ACTION, COMPONENT, PACKAGE, wait_for_log


CLASSIFICATIONS = {"CLEAN", "RESIDUAL", "UNCERTAIN"}
QUIET_HOLD_MS = 15_000
CANDIDATES = (
    {
        "id": "duplicate-16x100",
        "label": "16 bands x 100 ms duplicate",
        "bands": 16,
        "phaseMs": 100,
        "duplicates": 2,
        "reverse": False,
        "presentation": "t0",
    },
    {
        "id": "reverse-16x140",
        "label": "16 bands x 140 ms reverse",
        "bands": 16,
        "phaseMs": 140,
        "duplicates": 1,
        "reverse": True,
        "presentation": "t0",
    },
    {
        "id": "duplicate-12x100",
        "label": "12 bands x 100 ms duplicate",
        "bands": 12,
        "phaseMs": 100,
        "duplicates": 2,
        "reverse": False,
        "presentation": "t0",
    },
    {
        "id": "duplicate-8x100",
        "label": "8 bands x 100 ms duplicate",
        "bands": 8,
        "phaseMs": 100,
        "duplicates": 2,
        "reverse": False,
        "presentation": "t0",
    },
    {
        "id": "progressive-t1-16x220",
        "label": "T1 progressive transparency 16 x 220 ms",
        "bands": 16,
        "phaseMs": 220,
        "duplicates": 1,
        "reverse": False,
        "presentation": "t1",
    },
    {
        "id": "pipelined-t2-16x220",
        "label": "T2 pipelined transparency 16 x 220 ms",
        "bands": 16,
        "phaseMs": 220,
        "duplicates": 1,
        "reverse": False,
        "presentation": "t2",
    },
)


def now() -> str:
    return dt.datetime.now().astimezone().isoformat()


def initial_state() -> dict:
    return {
        "preset": "VISUAL_PRESCREEN",
        "cameraValidation": False,
        "productionDefaultPromotion": False,
        "createdAt": now(),
        "active": None,
        "candidates": [dict(candidate, classification="PENDING", attemptedAt=None, classifiedAt=None) for candidate in CANDIDATES],
    }


def load_state(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else initial_state()


def save_state(path: Path, state: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2), encoding="utf-8")


def resolve_device(args: argparse.Namespace) -> Adb:
    adb_path = resolve_adb(args.adb)
    serials = connected_serials(adb_path)
    serial = args.serial or (serials[0] if len(serials) == 1 else "")
    if not serial or serial not in serials:
        raise RuntimeError(f"expected the connected Palma, found {serials}")
    return Adb(adb_path, serial)


def candidate_by_id(state: dict, candidate_id: str) -> dict:
    return next(candidate for candidate in state["candidates"] if candidate["id"] == candidate_id)


def pause_app(adb: Adb, pid: str) -> None:
    result = adb.run("shell", "run-as", PACKAGE, "kill", "-STOP", pid, check=False).strip()
    if result:
        raise RuntimeError(f"failed to hold final white state: {result}")


def resume_and_stop(adb: Adb, pid: str | None) -> None:
    if pid:
        adb.run("shell", "run-as", PACKAGE, "kill", "-CONT", pid, check=False)
        time.sleep(0.2)
    adb.run("shell", "am", "force-stop", PACKAGE, check=False)


def run_next(args: argparse.Namespace, output_dir: Path, state: dict) -> int:
    if state.get("active"):
        active = state["active"]
        raise RuntimeError(f"candidate {active['id']} is already awaiting classification")
    candidate = next((item for item in state["candidates"] if item["classification"] == "PENDING"), None)
    if candidate is None:
        print("VISUAL_PRESCREEN complete: every candidate has a classification")
        return 0
    adb = resolve_device(args)
    adb.run("shell", "am", "force-stop", PACKAGE, check=False)
    adb.run("logcat", "-b", "all", "-c", check=False)
    host_run_id = f"prescreen-{candidate['id']}"
    command = [
        "shell", "am", "start", "-W", "-n", COMPONENT, "-a", ACTION,
        "--es", "experiment", "overlay-scrub",
        "--es", "hostRunId", host_run_id,
        "--ez", "confirmSafe", "true",
        "--ei", "bands", str(candidate["bands"]),
        "--el", "blackMs", str(candidate["phaseMs"]),
        "--el", "whiteMs", str(candidate["phaseMs"]),
        "--el", "safetyMs", "0",
        "--el", "quietMs", str(QUIET_HOLD_MS),
        "--ei", "repeats", "1",
        "--ei", "duplicates", str(candidate["duplicates"]),
        "--ez", "bottomToTop", str(candidate["reverse"]).lower(),
        "--es", "overlayPresentation", candidate["presentation"],
        "--ez", "cameraMode", "true",
        "--ei", "cameraIndex", str(state["candidates"].index(candidate) + 1),
        "--ei", "cameraTotal", str(len(state["candidates"])),
        "--es", "cameraProfile", "VISUAL PRESCREEN - NOT CAMERA VALIDATION",
        "--es", "cameraTitle", candidate["label"],
        "--es", "cameraDetail", "CLASSIFY FINAL WHITE: CLEAN / RESIDUAL / UNCERTAIN",
        "--el", "cameraSlateMs", "800",
        "--el", "cameraPreconditionMs", "600",
        "--el", "cameraBaselineMs", "800",
    ]
    launch = adb.run(*command, check=False, timeout=30)
    wait_for_log(adb, "QUIET_HOLD_BEGIN", 40)
    pid = adb.run("shell", "pidof", PACKAGE, check=False).strip()
    if not pid:
        raise RuntimeError("test app exited before the final white state could be held")
    pause_app(adb, pid)
    raw = adb.run("logcat", "-b", "all", "-d", "-v", "threadtime", check=False)
    candidate_dir = output_dir / "runs"
    candidate_dir.mkdir(parents=True, exist_ok=True)
    (candidate_dir / f"{candidate['id']}-launch.txt").write_text(launch, encoding="utf-8")
    (candidate_dir / f"{candidate['id']}-logcat.txt").write_text(raw, encoding="utf-8")
    candidate["attemptedAt"] = now()
    state["active"] = {"id": candidate["id"], "pid": pid, "heldAt": now(), "state": "FINAL_WHITE_HELD"}
    save_state(output_dir / "VISUAL_PRESCREEN_STATE.json", state)
    print(f"AWAITING_CLASSIFICATION id={candidate['id']} label={candidate['label']} pid={pid}")
    return 0


def classify(args: argparse.Namespace, output_dir: Path, state: dict) -> int:
    classification = args.classification.upper()
    if classification not in CLASSIFICATIONS:
        raise RuntimeError(f"classification must be one of {sorted(CLASSIFICATIONS)}")
    active = state.get("active")
    if not active:
        raise RuntimeError("no prescreen candidate is awaiting classification")
    adb = resolve_device(args)
    resume_and_stop(adb, active.get("pid"))
    candidate = candidate_by_id(state, active["id"])
    candidate["classification"] = classification
    candidate["classifiedAt"] = now()
    state["active"] = None
    save_state(output_dir / "VISUAL_PRESCREEN_STATE.json", state)
    remaining = sum(item["classification"] == "PENDING" for item in state["candidates"])
    print(f"CLASSIFIED id={candidate['id']} result={classification} remaining={remaining}")
    return 0


def status(state: dict) -> int:
    print(json.dumps(state, indent=2))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "run-next", "classify", "status"))
    parser.add_argument("classification", nargs="?")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    output_dir = args.output.resolve()
    state_path = output_dir / "VISUAL_PRESCREEN_STATE.json"
    state = load_state(state_path)
    if args.command == "prepare":
        if state_path.exists():
            raise RuntimeError("VISUAL_PRESCREEN state already exists; refusing to reset classifications")
        save_state(state_path, state)
        return 0
    if args.command == "run-next":
        return run_next(args, output_dir, state)
    if args.command == "classify":
        if not args.classification:
            raise RuntimeError("classify requires CLEAN, RESIDUAL, or UNCERTAIN")
        return classify(args, output_dir, state)
    return status(state)


if __name__ == "__main__":
    raise SystemExit(main())
