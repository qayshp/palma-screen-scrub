package dev.palma.screenscrub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.WindowManager

class AccessibilityRefreshService : AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        DiagnosticLog.event("I", "ACCESSIBILITY", "SERVICE_CONNECTED")
        val preferences = ScrubPreferences(this)
        if (preferences.pendingAction() == PENDING_ACCESSIBILITY) {
            preferences.setPendingAction(null)
            Handler(Looper.getMainLooper()).post { startLogicalWhiteRefresh(preferences.quickConfig(), "accessibility-permission-return") }
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        DiagnosticLog.event("I", "ACCESSIBILITY", "SERVICE_DESTROYED")
        super.onDestroy()
    }

    fun startLogicalWhiteRefresh(config: ScrubConfig, source: String, onFinished: ((String) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DiagnosticLog.event("W", "ACCESSIBILITY", "SCREENSHOT_UNAVAILABLE api=${Build.VERSION.SDK_INT}")
            onFinished?.invoke("accessibility screenshot unavailable")
            return
        }
        DiagnosticLog.begin(this, "accessibility-logical-white-refresh", source, config.summary())
        DiagnosticLog.event("I", "ACCESSIBILITY", "SCREENSHOT_REQUEST display=DEFAULT threshold=$WhiteThreshold tile=$TileSize")
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val sourceBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                val bitmap = sourceBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                sourceBitmap?.recycle()
                buffer.close()
                if (bitmap == null) {
                    DiagnosticLog.event("E", "ACCESSIBILITY", "SCREENSHOT_FAILED bitmap wrap returned null")
                    DiagnosticLog.finish(this@AccessibilityRefreshService, "screenshot bitmap unavailable")
                    onFinished?.invoke("screenshot bitmap unavailable")
                    return
                }
                runCatching {
                    val width = bitmap.width
                    val height = bitmap.height
                    val rects = whiteTiles(bitmap)
                    bitmap.recycle()
                    DiagnosticLog.event("I", "ACCESSIBILITY", "SCREENSHOT_SUCCESS width=$width height=$height whiteTiles=${rects.size}")
                    AccessibilityMaskOverlayController(this@AccessibilityRefreshService, rects, config) { result ->
                        DiagnosticLog.finish(this@AccessibilityRefreshService, result)
                        onFinished?.invoke(result)
                    }.start()
                }.onFailure { error ->
                    DiagnosticLog.event("E", "ACCESSIBILITY", "SCREENSHOT_PROCESS_FAILED ${error.javaClass.simpleName}: ${error.message}")
                    DiagnosticLog.finish(this@AccessibilityRefreshService, "screenshot processing failed")
                    onFinished?.invoke("screenshot processing failed")
                }
            }

            override fun onFailure(errorCode: Int) {
                DiagnosticLog.event("E", "ACCESSIBILITY", "SCREENSHOT_FAILED errorCode=$errorCode")
                DiagnosticLog.finish(this@AccessibilityRefreshService, "screenshot failed code=$errorCode")
                onFinished?.invoke("screenshot failed code=$errorCode")
            }
        })
    }

    private fun whiteTiles(bitmap: Bitmap): List<Rect> {
        val rects = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        for (top in 0 until height step TileSize) {
            for (left in 0 until width step TileSize) {
                val right = minOf(left + TileSize, width)
                val bottom = minOf(top + TileSize, height)
                val pixel = bitmap.getPixel(minOf(left + TileSize / 2, width - 1), minOf(top + TileSize / 2, height - 1))
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                if (luminance >= WhiteThreshold) rects += Rect(left, top, right, bottom)
            }
        }
        return rects
    }

    companion object {
        private const val WhiteThreshold = 240
        private const val TileSize = 32
        private const val PENDING_ACCESSIBILITY = "accessibility"
        @Volatile var current: AccessibilityRefreshService? = null

        fun active(): AccessibilityRefreshService? = current
    }
}

private class AccessibilityMaskOverlayController(
    private val service: AccessibilityService,
    private val blackTiles: List<Rect>,
    private val config: ScrubConfig,
    private val onFinished: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val view = WhiteMaskView(service, blackTiles)
    private var added = false

    fun start() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            title = "PalmaAccessibilityWhiteMask"
        }
        runCatching {
            windowManager.addView(view, params)
            added = true
            DiagnosticLog.event("I", "ACCESSIBILITY", "MASK_OVERLAY_ADDED width=${view.width} height=${view.height} tiles=${blackTiles.size}")
            handler.postDelayed({ finish("accessibility logical-white refresh complete") }, config.blackDwellMs + config.finalSettleMs)
        }.onFailure { error ->
            DiagnosticLog.event("E", "ACCESSIBILITY", "MASK_OVERLAY_FAILED ${error.javaClass.simpleName}: ${error.message}")
            finish("accessibility overlay failed")
        }
    }

    private fun finish(result: String) {
        if (added) {
            runCatching { windowManager.removeViewImmediate(view) }
            added = false
            DiagnosticLog.event("I", "ACCESSIBILITY", "MASK_OVERLAY_REMOVED result=$result")
        }
        onFinished(result)
    }
}

private class WhiteMaskView(context: android.content.Context, private val tiles: List<Rect>) : View(context) {
    private val paint = Paint().apply { color = Color.BLACK }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        tiles.forEach { rect -> canvas.drawRect(rect, paint) }
    }
}
