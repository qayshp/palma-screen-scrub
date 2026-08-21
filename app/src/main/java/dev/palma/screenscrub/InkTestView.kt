package dev.palma.screenscrub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.SystemClock
import android.view.View

class InkTestView(context: Context) : View(context) {
    enum class Pattern {
        WHITE,
        BLACK,
        CHECKERBOARD,
        INVERTED_CHECKERBOARD,
        NORMAL_INVALIDATION,
        SCRUB_STRIPE,
        CHECKER_CLEANUP,
        OVERLAY_BANDS,
        CAMERA_SLATE,
    }

    enum class OverlayBandState {
        WHITE,
        BLACK,
        TRANSPARENT,
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pattern = Pattern.WHITE
    private var invalidationCount = 0
    private var scrubStripeIndex = 0
    private var scrubStripeCount = 1
    private var scrubStripeIsBlack = false
    private var checkerCleanupCount = 0
    private var checkerCleanupTotal = 1
    private var overlayBandStates: List<OverlayBandState> = emptyList()
    private var debugLabel: String? = null
    private var slateTitle = ""
    private var slateLines: List<String> = emptyList()
    var onPatternDrawn: ((String, Long) -> Unit)? = null

    init {
        isClickable = true
    }

    fun show(nextPattern: Pattern) {
        pattern = nextPattern
        invalidate()
    }

    fun showSolidWhite() {
        pattern = Pattern.WHITE
        debugLabel = null
        invalidate()
    }

    fun normalInvalidate() {
        pattern = Pattern.NORMAL_INVALIDATION
        invalidationCount += 1
        invalidate()
    }

    fun showScrubStripe(index: Int, count: Int, black: Boolean, label: String? = debugLabel) {
        pattern = Pattern.SCRUB_STRIPE
        scrubStripeIndex = index
        scrubStripeCount = count.coerceAtLeast(1)
        scrubStripeIsBlack = black
        debugLabel = label
        invalidate()
    }

    fun showCheckerCleanup(cleanedStripeCount: Int, totalStripeCount: Int) {
        pattern = Pattern.CHECKER_CLEANUP
        checkerCleanupTotal = totalStripeCount.coerceAtLeast(1)
        checkerCleanupCount = cleanedStripeCount.coerceIn(0, checkerCleanupTotal)
        invalidate()
    }

    fun showOverlayBands(states: List<OverlayBandState>) {
        pattern = Pattern.OVERLAY_BANDS
        overlayBandStates = states.toList()
        debugLabel = null
        invalidate()
    }

    fun setDebugLabel(label: String?) {
        debugLabel = label
        invalidate()
    }

    fun showCameraSlate(title: String, lines: List<String>) {
        pattern = Pattern.CAMERA_SLATE
        slateTitle = title
        slateLines = lines
        debugLabel = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (pattern) {
            Pattern.WHITE, Pattern.NORMAL_INVALIDATION -> {
                canvas.drawColor(Color.WHITE)
                if (pattern == Pattern.NORMAL_INVALIDATION) drawInvalidationMarker(canvas)
            }
            Pattern.BLACK -> canvas.drawColor(Color.BLACK)
            Pattern.CHECKERBOARD -> drawCheckerboard(canvas, inverted = false)
            Pattern.INVERTED_CHECKERBOARD -> drawCheckerboard(canvas, inverted = true)
            Pattern.SCRUB_STRIPE -> drawScrubStripe(canvas)
            Pattern.CHECKER_CLEANUP -> drawCheckerCleanup(canvas)
            Pattern.OVERLAY_BANDS -> drawOverlayBands(canvas)
            Pattern.CAMERA_SLATE -> drawCameraSlate(canvas)
        }
        debugLabel?.let { label -> drawDebugLabel(canvas, label) }
        onPatternDrawn?.invoke(pattern.name, SystemClock.elapsedRealtimeNanos())
    }

    private fun drawCheckerboard(canvas: Canvas, inverted: Boolean) {
        val cellSize = (width.coerceAtMost(height) / 10).coerceAtLeast(48)
        for (top in 0 until height step cellSize) {
            for (left in 0 until width step cellSize) {
                val index = (left / cellSize) + (top / cellSize)
                val black = (index % 2 == 0) xor inverted
                paint.color = if (black) Color.BLACK else Color.WHITE
                canvas.drawRect(left.toFloat(), top.toFloat(), (left + cellSize).toFloat(), (top + cellSize).toFloat(), paint)
            }
        }
    }

    private fun drawInvalidationMarker(canvas: Canvas) {
        paint.color = Color.BLACK
        val size = (width / 14).coerceAtLeast(24)
        val left = (invalidationCount * size) % (width - size).coerceAtLeast(1)
        canvas.drawRect(left.toFloat(), 0f, (left + size).toFloat(), size.toFloat(), paint)
    }

    private fun drawScrubStripe(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        if (!scrubStripeIsBlack) return

        val top = height * scrubStripeIndex / scrubStripeCount
        val bottom = height * (scrubStripeIndex + 1) / scrubStripeCount
        paint.color = Color.BLACK
        canvas.drawRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat(), paint)
    }

    private fun drawCheckerCleanup(canvas: Canvas) {
        drawCheckerboard(canvas, inverted = true)
        val bottom = height * checkerCleanupCount / checkerCleanupTotal
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), bottom.toFloat(), paint)
    }

    private fun drawOverlayBands(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val states = overlayBandStates.ifEmpty { listOf(OverlayBandState.WHITE) }
        states.forEachIndexed { index, state ->
            if (state == OverlayBandState.TRANSPARENT) return@forEachIndexed
            val top = height * index / states.size
            val bottom = height * (index + 1) / states.size
            paint.color = if (state == OverlayBandState.BLACK) Color.BLACK else Color.WHITE
            canvas.drawRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat(), paint)
        }
    }

    private fun drawCameraSlate(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val margin = (24 * resources.displayMetrics.density).coerceAtLeast(24f)
        val headerHeight = (150 * resources.displayMetrics.density).coerceAtLeast(150f)
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = (34 * resources.displayMetrics.scaledDensity).coerceAtLeast(34f)
        canvas.drawText(slateTitle, width / 2f, headerHeight * 0.62f, paint)

        paint.color = Color.BLACK
        paint.textSize = (23 * resources.displayMetrics.scaledDensity).coerceAtLeast(23f)
        var baseline = headerHeight + margin * 2f
        val lineHeight = paint.textSize * 1.7f
        slateLines.forEach { line ->
            canvas.drawText(line, width / 2f, baseline, paint)
            baseline += lineHeight
        }
        paint.isFakeBoldText = false
        paint.textSize = (18 * resources.displayMetrics.scaledDensity).coerceAtLeast(18f)
        canvas.drawText("DO NOT TOUCH DEVICE", width / 2f, height - margin * 1.8f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawDebugLabel(canvas: Canvas, label: String) {
        val labelHeight = (28 * resources.displayMetrics.density).coerceAtLeast(28f)
        paint.color = Color.WHITE
        canvas.drawRect(0f, (height - labelHeight).coerceAtLeast(0f), width.toFloat(), height.toFloat(), paint)
        paint.color = Color.BLACK
        paint.textSize = (15 * resources.displayMetrics.scaledDensity).coerceAtLeast(15f)
        paint.isFakeBoldText = true
        canvas.drawText(label, 10f, (height - 8 * resources.displayMetrics.density).coerceAtLeast(paint.textSize), paint)
        paint.isFakeBoldText = false
    }
}
