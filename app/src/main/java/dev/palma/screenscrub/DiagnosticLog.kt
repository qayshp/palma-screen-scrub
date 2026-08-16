package dev.palma.screenscrub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DiagnosticLog {
    const val TAG = "PalmaScreenScrub"
    private const val PREFS = "diagnostic_history"
    private const val HISTORY = "history"
    private const val MAX_EVENTS = 500
    private const val MAX_RUNS = 80
    private val events = ArrayDeque<Event>()
    private var currentRun: Run? = null

    data class Event(
        val wallTime: String,
        val elapsedMs: Long,
        val runId: String?,
        val severity: String,
        val category: String,
        val message: String,
    ) {
        fun line(): String = "$wallTime +${elapsedMs}ms ${runId ?: "no-run"} $severity/$category $message"
    }

    data class Run(
        val id: String,
        val experiment: String,
        val source: String,
        val parameters: String,
        val startedWallTime: String,
        val startedElapsedMs: Long,
        var result: String = "running",
        var endedElapsedMs: Long? = null,
    )

    @Synchronized
    fun begin(context: Context, experiment: String, source: String, parameters: String): String {
        currentRun?.let { finish(context, "superseded") }
        val run = Run(
            id = UUID.randomUUID().toString().take(8),
            experiment = experiment,
            source = source,
            parameters = parameters,
            startedWallTime = wallTime(),
            startedElapsedMs = SystemClock.elapsedRealtime(),
        )
        currentRun = run
        event("I", "RUN", "RUN_BEGIN experiment=$experiment source=$source parameters=$parameters", run.id)
        return run.id
    }

    @Synchronized
    fun finish(context: Context, result: String) {
        val run = currentRun ?: return
        run.result = result
        run.endedElapsedMs = SystemClock.elapsedRealtime()
        event("I", "RUN", "RUN_END result=$result", run.id)
        saveRun(context, run)
        currentRun = null
    }

    @Synchronized
    fun event(severity: String, category: String, message: String, runId: String? = currentRun?.id) {
        val entry = Event(wallTime(), SystemClock.elapsedRealtime(), runId, severity, category, message)
        while (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(entry)
        when (severity) {
            "E" -> Log.e(TAG, entry.line())
            "W" -> Log.w(TAG, entry.line())
            else -> Log.i(TAG, entry.line())
        }
    }

    @Synchronized
    fun currentRunId(): String? = currentRun?.id

    @Synchronized
    fun eventText(): String = events.joinToString("\n") { it.line() }

    fun history(context: Context): List<JSONObject> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HISTORY, "[]") ?: "[]"
        val array = JSONArray(raw)
        List(array.length()) { array.getJSONObject(it) }
    }.getOrDefault(emptyList())

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(HISTORY).apply()
        event("I", "HISTORY", "history cleared")
    }

    fun annotateNewest(context: Context, annotation: String) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(preferences.getString(HISTORY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        if (array.length() == 0) return
        array.getJSONObject(array.length() - 1).put("annotation", annotation)
        preferences.edit().putString(HISTORY, array.toString()).apply()
        event("I", "HISTORY", "newest run annotated=$annotation")
    }

    fun copyText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        event("I", "EXPORT", "copied $label (${text.length} characters)")
    }

    fun fullReport(context: Context, apiInventory: List<String>, config: ScrubConfig): String = buildString {
        appendLine("Palma Screen Scrub diagnostic report")
        appendLine("App: ${context.packageName}")
        appendLine("SDK: onyxsdk-device 1.3.5 when optional BOOX build is installed")
        appendLine("Current quick configuration: ${config.summary()}")
        appendLine("Current run: ${currentRun?.id ?: "none"}")
        appendLine("Runtime API inventory:")
        apiInventory.forEach { appendLine(it) }
        appendLine("Recent run history:")
        history(context).takeLast(12).forEach { appendLine(it.toString()) }
        appendLine("Live app events:")
        appendLine(eventText())
    }

    private fun saveRun(context: Context, run: Run) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(preferences.getString(HISTORY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        while (array.length() >= MAX_RUNS) array.remove(0)
        array.put(JSONObject().apply {
            put("id", run.id)
            put("experiment", run.experiment)
            put("source", run.source)
            put("parameters", run.parameters)
            put("startedWallTime", run.startedWallTime)
            put("startedElapsedMs", run.startedElapsedMs)
            put("endedElapsedMs", run.endedElapsedMs)
            put("durationMs", (run.endedElapsedMs ?: SystemClock.elapsedRealtime()) - run.startedElapsedMs)
            put("result", run.result)
        })
        preferences.edit().putString(HISTORY, array.toString()).apply()
    }

    private fun wallTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
