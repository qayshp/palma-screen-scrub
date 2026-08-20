#!/usr/bin/env python3
"""Merge camera-scored Palma runs with the saved software evidence manifest."""

from __future__ import annotations

import argparse
import collections
import csv
import json
from pathlib import Path


def status(record: dict) -> str:
    if record.get("timedOut"):
        return "TIMEOUT"
    if record.get("fatalOrAnr"):
        return "CRASH_OR_ANR"
    if record.get("automationRejected"):
        return "SKIPPED_OR_UNAVAILABLE"
    return "COMPLETED"


def result_text(line: str | None) -> str:
    if not line:
        return ""
    return line.split("result=", 1)[-1]


def mode_summary(events: list[dict]) -> str:
    modes = collections.Counter(
        (event.get("waveform"), event.get("updateMode"), event.get("flags"), event.get("rect"))
        for event in events
    )
    return "; ".join(
        f"wf={waveform}/update={update_mode}/flags={flags}/rect={rect} x{count}"
        for (waveform, update_mode, flags, rect), count in sorted(modes.items())
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", type=Path, help="Completed camera-suite output directory")
    parser.add_argument("review", type=Path, help="Camera visual-results JSON")
    parser.add_argument("--output", type=Path, help="Output directory; defaults beside the review JSON")
    args = parser.parse_args()

    suite = args.suite.resolve()
    review_path = args.review.resolve()
    output = (args.output or review_path.parent).resolve()
    output.mkdir(parents=True, exist_ok=True)

    manifest = json.loads((suite / "manifest.json").read_text(encoding="utf-8"))
    review = json.loads(review_path.read_text(encoding="utf-8"))
    visual_by_run = {int(item["run"]): item for item in review["runs"]}
    manifest_by_run = {
        int(record["visibleRunId"].split("/", 1)[0]): record
        for record in manifest
    }
    expected = set(range(1, len(manifest) + 1))
    if set(manifest_by_run) != expected or set(visual_by_run) != expected:
        raise RuntimeError(
            f"run-key mismatch: manifest={sorted(manifest_by_run)} visual={sorted(visual_by_run)}"
        )

    merged = []
    csv_rows = []
    for run in sorted(expected):
        software = manifest_by_run[run]
        visual = visual_by_run[run]
        waits = software.get("realWaitDurationsMs", [])
        global_waits = software.get("globalWaitDurationsMs", [])
        entry = {
            "run": run,
            "visibleRunId": software["visibleRunId"],
            "label": software["label"],
            "visual": visual,
            "software": {
                "status": status(software),
                "hostDurationSeconds": software.get("hostDurationSeconds"),
                "callResult": result_text(software.get("callReturn")),
                "surfaceFlingerEventCount": len(software.get("surfaceFlinger", [])),
                "sdmDuringRunCount": software.get("sdmDuringRunCount", 0),
                "sdmModes": mode_summary(software.get("sdmDuringRun", [])),
                "requestCount": software.get("requestCount", 0),
                "requestSdmMatchedCount": software.get("requestSdmMatchedCount", 0),
                "realWaitCount": software.get("realWaitCount", 0),
                "realWaitDurationsMs": waits,
                "realWaitUnavailableCount": software.get("realWaitUnavailableCount", 0),
                "globalWaitCount": software.get("globalWaitCount", 0),
                "globalWaitDurationsMs": global_waits,
                "globalSdmObservedBeforeWaitBegin": software.get(
                    "globalSdmObservedBeforeWaitBegin", False
                ),
                "submissionCount": software.get("submissionCount", 0),
                "apparentMissingSurfaceFlinger": software.get(
                    "apparentMissingSurfaceFlinger", 0
                ),
                "artifacts": software.get("artifacts", {}),
            },
        }
        merged.append(entry)
        csv_rows.append(
            {
                "run": run,
                "visible_run_id": software["visibleRunId"],
                "label": software["label"],
                "visual_result": visual["visual_result"],
                "confidence": visual["confidence"],
                "visual_notes": visual["notes"],
                "representative_hold_time_seconds": visual[
                    "representative_hold_time_seconds"
                ],
                "software_status": status(software),
                "call_result": result_text(software.get("callReturn")),
                "sf_event_count": len(software.get("surfaceFlinger", [])),
                "sdm_during_run_count": software.get("sdmDuringRunCount", 0),
                "sdm_modes": mode_summary(software.get("sdmDuringRun", [])),
                "request_count": software.get("requestCount", 0),
                "request_sdm_matched_count": software.get("requestSdmMatchedCount", 0),
                "real_wait_count": software.get("realWaitCount", 0),
                "real_wait_min_ms": min(waits) if waits else "",
                "real_wait_max_ms": max(waits) if waits else "",
                "real_wait_unavailable_count": software.get(
                    "realWaitUnavailableCount", 0
                ),
                "global_wait_ms": ",".join(map(str, global_waits)),
                "global_sdm_before_wait": software.get(
                    "globalSdmObservedBeforeWaitBegin", False
                ),
                "missing_surfaceflinger": software.get(
                    "apparentMissingSurfaceFlinger", 0
                ),
            }
        )

    json_output = output / "MERGED_CAMERA_AND_LOG_EVIDENCE.json"
    csv_output = output / "MERGED_CAMERA_AND_LOG_EVIDENCE.csv"
    markdown_output = output / "MERGED_CAMERA_AND_LOG_EVIDENCE.md"
    json_output.write_text(
        json.dumps(
            {
                "sourceVideo": review["source_video"],
                "durationSeconds": review["duration_seconds"],
                "reviewNote": review["review_note"],
                "keyFindings": review["key_findings"],
                "runs": merged,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    with csv_output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(csv_rows[0]))
        writer.writeheader()
        writer.writerows(csv_rows)

    lines = [
        "# Palma merged camera and software evidence",
        "",
        f"- Source video: `{review['source_video']}`",
        f"- Camera-scored runs: `{len(visual_by_run)}`",
        f"- Software-recorded runs: `{len(manifest_by_run)}`",
        "- Join key: visible run number",
        "",
        "## Camera findings",
        "",
    ]
    lines.extend(f"- {finding}" for finding in review["key_findings"])
    lines.extend(
        [
            "",
            "## Joined runs",
            "",
            "| Run | Test | Camera result | SDM events | Wait evidence | Software status |",
            "| ---: | --- | --- | ---: | --- | --- |",
        ]
    )
    for entry in merged:
        software = entry["software"]
        waits = software["realWaitDurationsMs"]
        global_waits = software["globalWaitDurationsMs"]
        wait_text = (
            f"global={','.join(map(str, global_waits))} ms; SDM-before={software['globalSdmObservedBeforeWaitBegin']}"
            if global_waits
            else f"real={len(waits)} calls, {min(waits)}–{max(waits)} ms"
            if waits
            else "none"
        )
        lines.append(
            f"| {entry['run']} | {entry['label']} | {entry['visual']['visual_result']} | "
            f"{software['sdmDuringRunCount']} | {wait_text} | {software['status']} |"
        )
    lines.extend(
        [
            "",
            "## Evidence boundary",
            "",
            "- Camera scoring establishes physical-panel behavior; logs establish software submission only.",
            "- A SurfaceFlinger or SDM event does not prove physical waveform completion.",
            "- Original manifests, logs, and camera sidecars remain unchanged.",
        ]
    )
    markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Merged {len(merged)} runs into {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
