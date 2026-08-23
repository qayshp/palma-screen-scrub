package dev.palma.screenscrub

private fun productionConfig(blackMs: Long, whiteMs: Long, duplicates: Int) = ScrubConfig(
    bandCount = 16,
    blackDwellMs = blackMs,
    whiteDwellMs = whiteMs,
    safetyDelayMs = 0L,
    finalSettleMs = 500L,
    diagnosticQuietHoldMs = 3_000L,
    repeatCount = 1,
    duplicateSubmissions = duplicates,
    bottomToTop = false,
)

enum class ScrubProfile(
    val stableId: String,
    val displayName: String,
    val description: String,
    val summary: String,
    val presentationMode: OverlayScrubController.PresentationMode,
    val config: ScrubConfig,
) {
    PROGRESSIVE_T2_16X220(
        stableId = "PROGRESSIVE_T2_16X220",
        displayName = "Progressive",
        description = "Reveals the screen as each area finishes clearing. Recommended.",
        summary = "16 bands · 220 ms · progressive reveal",
        presentationMode = OverlayScrubController.PresentationMode.T2_PIPELINED,
        config = productionConfig(blackMs = 220L, whiteMs = 220L, duplicates = 1),
    ),
    FAST_T0_16X100_DUP2(
        stableId = "FAST_T0_16X100_DUP2",
        displayName = "Fast",
        description = "Faster opaque scrub using repeated band updates.",
        summary = "16 bands · 100 ms · duplicate updates",
        presentationMode = OverlayScrubController.PresentationMode.T0_OPAQUE,
        config = productionConfig(blackMs = 100L, whiteMs = 100L, duplicates = 2),
    ),
    SAFE_T0_16X220(
        stableId = "SAFE_T0_16X220",
        displayName = "Safe",
        description = "Original conservative opaque scrub.",
        summary = "16 bands · 220 ms · opaque",
        presentationMode = OverlayScrubController.PresentationMode.T0_OPAQUE,
        config = productionConfig(blackMs = 220L, whiteMs = 220L, duplicates = 1),
    );

    companion object {
        val DEFAULT = PROGRESSIVE_T2_16X220

        fun fromStableId(value: String?): ScrubProfile =
            entries.firstOrNull { it.stableId == value } ?: DEFAULT
    }
}
