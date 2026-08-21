# Camera presets

## FULL_CAMERA

- Existing unchanged 43-run suite: `tools/palma_next_phase_suite.py`
- Existing planned manifests: `../palma-next-phase-20260820-212633/camera_manifest.json`, `.csv`, and `.md`
- Composition: 6 BOOX physical trials, 25 fast-overlay runs, and 12 transparency runs.

## SHORT_CAMERA

- Separate 16-run first-pass preset: `tools/palma_short_camera_suite.py`
- It imports already implemented cases from `FULL_CAMERA`; it does not modify or rebuild the app.
- Preparation is host-only with `--prepare-only`.
- Execution requires `--confirmed-recording` and must not begin until the user confirms that the camera is recording.

## PALMA_CORRECTIVE_OVERLAY_CAMERA_SUITE

- Separate ten-run overlay-only corrective preset: `tools/palma_corrective_overlay_suite.py`
- Excludes all BOOX Speed/Regal/HD physical-refresh diagnostics.
- Requires E-Ink Wise absence before seeding and again during the pre-scrub camera precondition.
- Uses four-second evaluation holds and never changes the production Quick Scrub default.
- Execution requires `--confirmed-recording`.
