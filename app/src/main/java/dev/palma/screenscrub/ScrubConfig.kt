package dev.palma.screenscrub

import android.content.Context

data class ScrubConfig(
    val bandCount: Int = 16,
    val blackDwellMs: Long = 220L,
    val whiteDwellMs: Long = 220L,
    val safetyDelayMs: Long = 0L,
    val finalSettleMs: Long = 500L,
    val diagnosticQuietHoldMs: Long = 3_000L,
    val repeatCount: Int = 1,
    val duplicateSubmissions: Int = 1,
    val bottomToTop: Boolean = false,
) {
    fun sanitized(): ScrubConfig = copy(
        bandCount = bandCount.coerceIn(1, 64),
        blackDwellMs = blackDwellMs.coerceIn(0L, 5_000L),
        whiteDwellMs = whiteDwellMs.coerceIn(0L, 5_000L),
        safetyDelayMs = safetyDelayMs.coerceIn(0L, 5_000L),
        finalSettleMs = finalSettleMs.coerceIn(0L, 10_000L),
        diagnosticQuietHoldMs = diagnosticQuietHoldMs.coerceIn(0L, 15_000L),
        repeatCount = repeatCount.coerceIn(1, 10),
        duplicateSubmissions = duplicateSubmissions.coerceIn(1, 4),
    )

    fun summary(): String = "${bandCount} bands; black ${blackDwellMs} ms; white ${whiteDwellMs} ms; " +
        "safety ${safetyDelayMs} ms; settle ${finalSettleMs} ms; quiet ${diagnosticQuietHoldMs} ms; " +
        "repeats $repeatCount; duplicates $duplicateSubmissions; " +
        if (bottomToTop) "bottom→top" else "top→bottom"
}

enum class ExternalLaunchAction(val storedValue: String, val label: String) {
    QUICK_SCRUB("quick-scrub", "Quick Scrub"),
    QUICK_OVERLAY("quick-overlay", "Quick Overlay Scrub"),
    DIAGNOSTICS("diagnostics", "Diagnostics");

    companion object {
        fun fromStoredValue(value: String?): ExternalLaunchAction =
            entries.firstOrNull { it.storedValue == value } ?: QUICK_SCRUB
    }
}

class ScrubPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun quickConfig(): ScrubConfig = ScrubConfig(
        bandCount = preferences.getInt(BAND_COUNT, 16),
        blackDwellMs = preferences.getLong(BLACK_DWELL, 220L),
        whiteDwellMs = preferences.getLong(WHITE_DWELL, 220L),
        safetyDelayMs = preferences.getLong(SAFETY_DELAY, 0L),
        finalSettleMs = preferences.getLong(FINAL_SETTLE, 500L),
        diagnosticQuietHoldMs = preferences.getLong(DIAGNOSTIC_QUIET_HOLD, 3_000L),
        repeatCount = preferences.getInt(REPEAT_COUNT, 1),
        duplicateSubmissions = preferences.getInt(DUPLICATES, 1),
        bottomToTop = preferences.getBoolean(BOTTOM_TO_TOP, false),
    ).sanitized()

    fun saveQuickConfig(config: ScrubConfig) {
        val safe = config.sanitized()
        preferences.edit()
            .putInt(BAND_COUNT, safe.bandCount)
            .putLong(BLACK_DWELL, safe.blackDwellMs)
            .putLong(WHITE_DWELL, safe.whiteDwellMs)
            .putLong(SAFETY_DELAY, safe.safetyDelayMs)
            .putLong(FINAL_SETTLE, safe.finalSettleMs)
            .putLong(DIAGNOSTIC_QUIET_HOLD, safe.diagnosticQuietHoldMs)
            .putInt(REPEAT_COUNT, safe.repeatCount)
            .putInt(DUPLICATES, safe.duplicateSubmissions)
            .putBoolean(BOTTOM_TO_TOP, safe.bottomToTop)
            .apply()
    }

    fun productionProfile(): ScrubProfile =
        ScrubProfile.fromStableId(preferences.getString(PRODUCTION_PROFILE_ID, null))

    fun setProductionProfile(profile: ScrubProfile) {
        preferences.edit().putString(PRODUCTION_PROFILE_ID, profile.stableId).apply()
    }

    fun pendingProductionProfile(): ScrubProfile =
        ScrubProfile.fromStableId(preferences.getString(PENDING_PRODUCTION_PROFILE_ID, null))

    fun setPendingProductionProfile(profile: ScrubProfile?) {
        preferences.edit().apply {
            if (profile == null) remove(PENDING_PRODUCTION_PROFILE_ID)
            else putString(PENDING_PRODUCTION_PROFILE_ID, profile.stableId)
        }.apply()
    }

    fun setPendingAction(action: String?) {
        preferences.edit().putString(PENDING_ACTION, action).apply()
    }

    fun pendingAction(): String? = preferences.getString(PENDING_ACTION, null)

    fun experimentalApisEnabled(): Boolean = preferences.getBoolean(EXPERIMENTAL_APIS, false)

    fun setExperimentalApisEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(EXPERIMENTAL_APIS, enabled).apply()
    }

    fun externalLaunchAction(): ExternalLaunchAction =
        ExternalLaunchAction.fromStoredValue(preferences.getString(EXTERNAL_LAUNCH_ACTION, null))

    fun setExternalLaunchAction(action: ExternalLaunchAction) {
        preferences.edit().putString(EXTERNAL_LAUNCH_ACTION, action.storedValue).apply()
    }

    private companion object {
        const val PREFS = "scrub_preferences"
        const val BAND_COUNT = "band_count"
        const val BLACK_DWELL = "black_dwell"
        const val WHITE_DWELL = "white_dwell"
        const val SAFETY_DELAY = "safety_delay"
        const val FINAL_SETTLE = "final_settle"
        const val DIAGNOSTIC_QUIET_HOLD = "diagnostic_quiet_hold"
        const val REPEAT_COUNT = "repeat_count"
        const val DUPLICATES = "duplicates"
        const val BOTTOM_TO_TOP = "bottom_to_top"
        const val PENDING_ACTION = "pending_action"
        const val EXPERIMENTAL_APIS = "experimental_apis"
        const val EXTERNAL_LAUNCH_ACTION = "external_launch_action"
        const val PRODUCTION_PROFILE_ID = "production_profile_id"
        const val PENDING_PRODUCTION_PROFILE_ID = "pending_production_profile_id"
    }
}
