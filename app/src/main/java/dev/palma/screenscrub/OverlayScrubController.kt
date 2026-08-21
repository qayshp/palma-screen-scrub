package dev.palma.screenscrub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager

class OverlayScrubController(
    private val context: Context,
    private val config: ScrubConfig,
    private val finalWhiteHoldMs: Long,
    private val completionWait: (() -> BooxEpdBridge.ApiResult)? = null,
    private val presentationMode: PresentationMode = PresentationMode.T0_OPAQUE,
    private val sequenceMode: SequenceMode = SequenceMode.SCRUB,
    private val seedPhaseMs: Long = 600L,
    private val onAdded: (() -> Unit)? = null,
    private val onFinished: (String) -> Unit,
) {
    enum class PresentationMode(val wireValue: String) {
        T0_OPAQUE("t0"),
        T1_PROGRESSIVE("t1"),
        T2_PIPELINED("t2");

        companion object {
            fun fromWireValue(value: String?): PresentationMode =
                entries.firstOrNull { it.wireValue == value?.lowercase() } ?: T0_OPAQUE
        }
    }

    enum class SequenceMode {
        SCRUB,
        GHOST_SEED,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = InkTestView(context)
    private var added = false
    private var cancelled = false

    fun start() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            if (presentationMode == PresentationMode.T0_OPAQUE && sequenceMode == SequenceMode.SCRUB) {
                android.graphics.PixelFormat.OPAQUE
            } else {
                android.graphics.PixelFormat.TRANSLUCENT
            },
        ).apply {
            gravity = Gravity.FILL
            title = "PalmaScreenScrubOverlay"
        }
        runCatching {
            windowManager.addView(view, params)
            added = true
            DiagnosticLog.event(
                "I",
                "OVERLAY",
                "OVERLAY_ADDED type=TYPE_APPLICATION_OVERLAY width=${view.width} height=${view.height} " +
                    "display=${context.display?.mode?.physicalWidth}x${context.display?.mode?.physicalHeight} flags=${params.flags} " +
                    "presentation=${presentationMode.wireValue} sequence=$sequenceMode",
            )
            onAdded?.invoke()
            when {
                sequenceMode == SequenceMode.GHOST_SEED -> runGhostSeed()
                completionWait != null -> runWaitSweep()
                presentationMode == PresentationMode.T2_PIPELINED -> runPipelinedSweep()
                else -> runSweep()
            }
        }.onFailure { error ->
            DiagnosticLog.event("E", "OVERLAY", "OVERLAY_ADD_FAILED ${error.javaClass.simpleName}: ${error.message}")
            finish("overlay add failed: ${error.javaClass.simpleName}")
        }
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        finish("overlay cancelled")
    }

    private fun runSweep() {
        val safe = config.sanitized()
        val states = MutableList(safe.bandCount) { InkTestView.OverlayBandState.WHITE }
        var repeatIndex = 0
        var bandIndex = 0
        var black = true
        var duplicateIndex = 0
        var pendingTransparentIndex: Int? = null
        val advance = object : Runnable {
            override fun run() {
                if (cancelled) return
                pendingTransparentIndex?.let { transparentIndex ->
                    pendingTransparentIndex = null
                    states[transparentIndex] = InkTestView.OverlayBandState.TRANSPARENT
                    view.showOverlayBands(states)
                    DiagnosticLog.event(
                        "I",
                        "OVERLAY_STATE",
                        "band=${transparentIndex + 1}/${safe.bandCount} state=TRANSPARENT " +
                            "presentation=${presentationMode.wireValue} monotonicNs=${SystemClock.elapsedRealtimeNanos()}",
                    )
                }
                if (repeatIndex >= safe.repeatCount) {
                    DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=$finalWhiteHoldMs surface=overlay")
                    handler.postDelayed({
                        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=$finalWhiteHoldMs surface=overlay")
                        finish("overlay sweep complete")
                    }, finalWhiteHoldMs)
                    return
                }
                val drawIndex = if (safe.bottomToTop) safe.bandCount - 1 - bandIndex else bandIndex
                states[drawIndex] = if (black) {
                    InkTestView.OverlayBandState.BLACK
                } else {
                    InkTestView.OverlayBandState.WHITE
                }
                view.showOverlayBands(states)
                DiagnosticLog.event(
                    "I",
                    "OVERLAY",
                    "OVERLAY_DRAW repeat=${repeatIndex + 1}/${safe.repeatCount} band=${drawIndex + 1}/${safe.bandCount} " +
                        "phase=${if (black) "black" else "white"} duplicate=${duplicateIndex + 1}/${safe.duplicateSubmissions} " +
                            "presentation=${presentationMode.wireValue} monotonicNs=${SystemClock.elapsedRealtimeNanos()}",
                )
                val dwell = if (black) safe.blackDwellMs else safe.whiteDwellMs
                duplicateIndex += 1
                if (duplicateIndex >= safe.duplicateSubmissions) {
                    duplicateIndex = 0
                    if (black) {
                        black = false
                    } else {
                        if (presentationMode == PresentationMode.T1_PROGRESSIVE) {
                            pendingTransparentIndex = drawIndex
                        }
                        black = true
                        bandIndex += 1
                        if (bandIndex >= safe.bandCount) {
                            bandIndex = 0
                            repeatIndex += 1
                        }
                    }
                }
                handler.postDelayed(this, dwell + safe.safetyDelayMs)
            }
        }
        advance.run()
    }

    private fun runPipelinedSweep() {
        val safe = config.sanitized()
        val states = MutableList(safe.bandCount) { InkTestView.OverlayBandState.WHITE }
        val blackDuration = safe.blackDwellMs * safe.duplicateSubmissions + safe.safetyDelayMs
        val whiteDuration = safe.whiteDwellMs * safe.duplicateSubmissions + safe.safetyDelayMs
        val sweepStart = SystemClock.uptimeMillis()
        var completed = 0

        fun submitState(drawIndex: Int, state: InkTestView.OverlayBandState, duplicate: Int = 1) {
            if (cancelled) return
            states[drawIndex] = state
            view.showOverlayBands(states)
            DiagnosticLog.event(
                "I",
                "OVERLAY_STATE",
                "band=${drawIndex + 1}/${safe.bandCount} state=$state duplicate=$duplicate/${safe.duplicateSubmissions} " +
                    "presentation=${presentationMode.wireValue} monotonicNs=${SystemClock.elapsedRealtimeNanos()}",
            )
        }

        repeat(safe.repeatCount) { repeatIndex ->
            repeat(safe.bandCount) { sequenceIndex ->
                val drawIndex = if (safe.bottomToTop) safe.bandCount - 1 - sequenceIndex else sequenceIndex
                val bandBase = sweepStart +
                    repeatIndex * safe.bandCount * blackDuration + sequenceIndex * blackDuration
                repeat(safe.duplicateSubmissions) { duplicateIndex ->
                    handler.postAtTime(
                        { submitState(drawIndex, InkTestView.OverlayBandState.BLACK, duplicateIndex + 1) },
                        bandBase + duplicateIndex * safe.blackDwellMs,
                    )
                    handler.postAtTime(
                        { submitState(drawIndex, InkTestView.OverlayBandState.WHITE, duplicateIndex + 1) },
                        bandBase + blackDuration + duplicateIndex * safe.whiteDwellMs,
                    )
                }
                handler.postAtTime({
                    submitState(drawIndex, InkTestView.OverlayBandState.TRANSPARENT)
                    completed += 1
                    if (completed == safe.bandCount * safe.repeatCount) {
                        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=$finalWhiteHoldMs surface=overlay presentation=t2")
                        handler.postDelayed({
                            DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=$finalWhiteHoldMs surface=overlay presentation=t2")
                            finish("overlay pipelined sweep complete")
                        }, finalWhiteHoldMs)
                    }
                }, bandBase + blackDuration + whiteDuration)
            }
        }
    }

    private fun runGhostSeed() {
        val phaseMs = seedPhaseMs.coerceIn(100L, 5_000L)
        DiagnosticLog.event("I", "OVERLAY_SEED", "SEED_CHECKER_BEGIN durationMs=$phaseMs")
        view.show(InkTestView.Pattern.CHECKERBOARD)
        handler.postDelayed({
            DiagnosticLog.event("I", "OVERLAY_SEED", "SEED_INVERSE_BEGIN durationMs=$phaseMs")
            view.show(InkTestView.Pattern.INVERTED_CHECKERBOARD)
            handler.postDelayed({
                view.showSolidWhite()
                DiagnosticLog.event("I", "OVERLAY_SEED", "SEED_WHITE_HOLD_BEGIN durationMs=$finalWhiteHoldMs")
                handler.postDelayed({
                    DiagnosticLog.event("I", "OVERLAY_SEED", "SEED_WHITE_HOLD_END durationMs=$finalWhiteHoldMs")
                    finish("overlay ghost seed complete")
                }, finalWhiteHoldMs)
            }, phaseMs)
        }, phaseMs)
    }

    private fun runWaitSweep() {
        val safe = config.sanitized()
        val waitForCompletion = requireNotNull(completionWait)
        var repeatIndex = 0
        var bandIndex = 0
        var black = true
        var awaitingDraw = false
        var afterDraw: (() -> Unit)? = null
        var unavailableCount = 0

        view.onPatternDrawn = { _, _ ->
            if (awaitingDraw) {
                awaitingDraw = false
                afterDraw?.let { action ->
                    afterDraw = null
                    action()
                }
            }
        }

        fun submitNext() {
            if (cancelled) return
            if (repeatIndex >= safe.repeatCount) {
                DiagnosticLog.event(
                    "I",
                    "QUIET",
                    "QUIET_HOLD_BEGIN durationMs=$finalWhiteHoldMs surface=overlay realWait=true unavailable=$unavailableCount",
                )
                handler.postDelayed({
                    DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=$finalWhiteHoldMs surface=overlay realWait=true")
                    finish("overlay real-wait sweep complete; unavailable=$unavailableCount")
                }, finalWhiteHoldMs)
                return
            }

            val drawIndex = if (safe.bottomToTop) safe.bandCount - 1 - bandIndex else bandIndex
            val phase = if (black) "black" else "white"
            val commitSettleMs = if (black) safe.blackDwellMs else safe.whiteDwellMs
            awaitingDraw = true
            afterDraw = {
                DiagnosticLog.event(
                    "I",
                    "OVERLAY_REAL_WAIT",
                    "COMMIT_SETTLE band=${drawIndex + 1}/${safe.bandCount} phase=$phase durationMs=$commitSettleMs",
                )
                handler.postDelayed({
                    if (cancelled) return@postDelayed
                    val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    val watchdog = Runnable {
                        if (!cancelled) {
                            DiagnosticLog.event(
                                "W",
                                "OVERLAY_REAL_WAIT",
                                "WAIT_TIMEOUT band=${drawIndex + 1} phase=$phase timeoutMs=$WAIT_TIMEOUT_MS",
                            )
                            finish("overlay real completion wait timed out; stopped")
                        }
                    }
                    handler.postDelayed(watchdog, WAIT_TIMEOUT_MS)
                    DiagnosticLog.event(
                        "I",
                        "OVERLAY_REAL_WAIT",
                        "WAIT_BEGIN code=0xff0017 band=${drawIndex + 1}/${safe.bandCount} phase=$phase",
                    )
                    Thread {
                        val result = waitForCompletion()
                        val durationMs = (android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
                        handler.post {
                            handler.removeCallbacks(watchdog)
                            if (result is BooxEpdBridge.ApiResult.Unavailable) unavailableCount += 1
                            DiagnosticLog.event(
                                "I",
                                "OVERLAY_REAL_WAIT",
                                "WAIT_RETURN code=0xff0017 band=${drawIndex + 1}/${safe.bandCount} phase=$phase " +
                                    "durationMs=$durationMs result=${resultText(result)}",
                            )
                            if (cancelled) return@post
                            if (black) {
                                black = false
                            } else {
                                black = true
                                bandIndex += 1
                                if (bandIndex >= safe.bandCount) {
                                    bandIndex = 0
                                    repeatIndex += 1
                                }
                            }
                            handler.postDelayed(::submitNext, safe.safetyDelayMs)
                        }
                    }.apply {
                        name = "PalmaOverlayRealEpdWait"
                        isDaemon = true
                        start()
                    }
                }, commitSettleMs)
            }
            view.showScrubStripe(drawIndex, safe.bandCount, black)
            DiagnosticLog.event(
                "I",
                "OVERLAY_REAL_WAIT",
                "DRAW_REQUEST band=${drawIndex + 1}/${safe.bandCount} phase=$phase repeat=${repeatIndex + 1}/${safe.repeatCount}",
            )
        }

        submitNext()
    }

    private fun resultText(result: BooxEpdBridge.ApiResult): String = when (result) {
        is BooxEpdBridge.ApiResult.Success -> result.message
        is BooxEpdBridge.ApiResult.Unavailable -> "unavailable: ${result.reason}"
    }

    private fun finish(result: String) {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        view.onPatternDrawn = null
        if (added) {
            runCatching { windowManager.removeViewImmediate(view) }
            added = false
            DiagnosticLog.event("I", "OVERLAY", "OVERLAY_REMOVED result=$result")
        }
        onFinished(result)
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 5_000L
    }
}
