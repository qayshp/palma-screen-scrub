#!/usr/bin/env python3
"""Reanalyze a saved Palma unattended matrix without touching the device."""

from __future__ import annotations

import argparse
import collections
import json
import re
import statistics
from pathlib import Path

import palma_epd_test as harness


def result_text(line: str | None) -> str:
    if not line:
        return "none"
    return line.split("result=", 1)[-1].replace("|", "/")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Saved matrix output directory")
    args = parser.parse_args()
    output = args.output.resolve()
    old_records = json.loads((output / "runs.json").read_text(encoding="utf-8"))
    metadata = json.loads((output / "metadata.json").read_text(encoding="utf-8"))

    records = []
    for index, old in enumerate(old_records, 1):
        spec = harness.RunSpec(**old["spec"])
        raw_path = output / f"{index:03d}-{spec.label}-raw-logcat.txt"
        records.append(
            harness.parse_run(
                spec,
                old["hostRunId"],
                raw_path.read_text(encoding="utf-8", errors="replace"),
                old["hostDurationSeconds"],
                old["timedOut"],
            )
        )

    (output / "runs-enriched.json").write_text(json.dumps(records, indent=2), encoding="utf-8")

    lines = [
        "# Palma unattended EPD analysis",
        "",
        "> No visual verification performed. A logged submission is not proof of physical-panel completion.",
        "",
        "## Explicit API calls",
        "",
        "| Run | SurfaceFlinger events | Wrong waveform | Logged event | Return |",
        "| --- | ---: | ---: | --- | --- |",
    ]
    for record in records:
        if record["requestCount"] or record.get("submissionCount"):
            continue
        sf = "; ".join(
            f"wf={event['waveform']} flags={event['flags']} rect={event['rect']}"
            for event in record["surfaceFlinger"]
        ) or "none"
        lines.append(
            f"| {record['label']} | {len(record['surfaceFlinger'])} | "
            f"{record['waveformWrongCount']} | {sf} | {result_text(record['callReturn'])} |"
        )

    lines.extend(
        [
            "",
            "## Pattern request correlation",
            "",
            "| Run | Requests | Followed by SDM before next request | SDM during run | Latency ms min/median/max | SDM modes |",
            "| --- | ---: | ---: | ---: | --- | --- |",
        ]
    )
    for record in records:
        if not record["requestCount"]:
            continue
        latencies = record["requestSdmLatenciesMs"]
        latency_text = (
            f"{min(latencies)}/{statistics.median(latencies):g}/{max(latencies)}" if latencies else "none"
        )
        modes = collections.Counter(
            (event["waveform"], event["updateMode"], event["flags"])
            for event in record["sdmDuringRun"]
        )
        mode_text = "; ".join(
            f"wf={waveform} update={update_mode} flags={flags} x{count}"
            for (waveform, update_mode, flags), count in sorted(modes.items())
        ) or "none"
        lines.append(
            f"| {record['label']} | {record['requestCount']} | {record['requestSdmMatchedCount']} | "
            f"{record['sdmDuringRunCount']} | {latency_text} | {mode_text} |"
        )

    sequence_records = [record for record in records if record.get("submissionCount")]
    if sequence_records:
        lines.extend(
            [
                "",
                "## Native region submission correlation",
                "",
                "| Run | Submits | SurfaceFlinger before next submit | SDM before next submit | Missing SF | SF latency ms min/median/max | Marker gaps | Marker reuse |",
                "| --- | ---: | ---: | ---: | ---: | --- | --- | ---: |",
            ]
        )
        for record in sequence_records:
            sf_latencies = [
                submission["surfaceFlingerLatencyMs"]
                for submission in record["submissions"]
                if submission["surfaceFlingerLatencyMs"] is not None
            ]
            sf_latency_text = (
                f"{min(sf_latencies)}/{statistics.median(sf_latencies):g}/{max(sf_latencies)}"
                if sf_latencies
                else "none"
            )
            gaps = record["surfaceFlingerMarkerGaps"]
            lines.append(
                f"| {record['label']} | {record['submissionCount']} | {record['submissionSfMatchedCount']} | "
                f"{record['submissionSdmBeforeNextCount']} | {record['apparentMissingSurfaceFlinger']} | "
                f"{sf_latency_text} | {','.join(map(str, gaps)) or 'none'} | "
                f"{record['surfaceFlingerMarkerReuse']} |"
            )

    mode_records = [record for record in records if re.search(r"native-(?:view|region)-0x(?:02|22|42|62)", record["label"])]
    if mode_records:
        lines.extend(
            [
                "",
                "## FULL-bit comparison",
                "",
                "| Run | Waveform | Flags | Rectangle | SF markers | SDM update modes | Return |",
                "| --- | --- | --- | --- | --- | --- | --- |",
            ]
        )
        for record in mode_records:
            sf_signatures = sorted(
                {f"wf={event['waveform']} flags={event['flags']} rect={event['rect']}" for event in record["surfaceFlinger"]}
            )
            sdm_modes = sorted(
                {f"wf={event['waveform']}/update={event['updateMode']}/flags={event['flags']}" for event in record["sdmDuringRun"]}
            )
            lines.append(
                f"| {record['label']} | {'; '.join(sf_signatures) or 'none'} | "
                f"{'; '.join(sorted({event['flags'] for event in record['surfaceFlinger']})) or 'none'} | "
                f"{'; '.join(sorted({event['rect'] for event in record['surfaceFlinger']})) or 'none'} | "
                f"{','.join(str(event['marker']) for event in record['surfaceFlinger']) or 'none'} | "
                f"{'; '.join(sdm_modes) or 'none'} | {result_text(record['callReturn'])} |"
            )

    geometry_records = [record for record in records if record["label"].startswith("region-0x")]
    if geometry_records:
        lines.extend(
            [
                "",
                "## Geometry audit",
                "",
                "| Run | Requested app-local left,top,width,height | SurfaceFlinger raw four fields | Waveform/flags | Return |",
                "| --- | --- | --- | --- | --- |",
            ]
        )
        for record in geometry_records:
            spec = record["spec"]
            requested = ",".join(
                str(spec[key])
                for key in ("native_left", "native_top", "native_width", "native_height")
            )
            raw_geometry = "; ".join(
                ",".join(map(str, event["rawGeometry"]))
                for event in record["surfaceFlinger"]
                if event.get("rawGeometry")
            ) or "none"
            modes = "; ".join(
                f"wf={event['waveform']} flags={event['flags']}"
                for event in record["surfaceFlinger"]
            ) or "none"
            lines.append(
                f"| {record['label']} | {requested} | {raw_geometry} | {modes} | "
                f"{result_text(record['callReturn'])} |"
            )

        geometry_by_label = {record["label"]: record for record in geometry_records}
        lines.extend(["", "### Geometry sensitivity", ""])
        for native_mode in ("0x02", "0x42"):
            baseline = geometry_by_label.get(f"region-{native_mode}-origin-100x100")
            width_change = geometry_by_label.get(f"region-{native_mode}-origin-200x100")
            height_change = geometry_by_label.get(f"region-{native_mode}-origin-100x200")
            if not (baseline and width_change and height_change):
                continue

            def raw(record: dict) -> list[list[int]]:
                return [event["rawGeometry"] for event in record["surfaceFlinger"]]

            lines.append(
                f"- `{native_mode}`: doubling requested width changed the logged transformed fields: "
                f"`{raw(baseline)}` → `{raw(width_change)}`."
            )
            lines.append(
                f"- `{native_mode}`: doubling requested height did not change the logged transformed fields: "
                f"`{raw(baseline)}` → `{raw(height_change)}`."
            )
        lines.append(
            "- These are transformed firmware log fields, not verified physical coordinates. The height-insensitivity "
            "makes this direct region route unsuitable for the practical scrub without external-camera coverage tests."
        )

    marker_echo_count = sum(
        len(lines_for_marker)
        for record in records
        for lines_for_marker in record.get("surfaceFlingerMarkerEchoes", {}).values()
    )

    lines.extend(
        [
            "",
            "## Boundary",
            "",
            "- The correlation only shows that an SDM log followed an app request before the next request.",
            "- It cannot establish that the TCON accepted, completed, or physically drove every panel tile.",
            f"- SurfaceFlinger marker echoes outside its own refresh line: {marker_echo_count}.",
        ]
    )
    (output / "analysis.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Reanalyzed {len(records)} runs: {output / 'analysis.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
