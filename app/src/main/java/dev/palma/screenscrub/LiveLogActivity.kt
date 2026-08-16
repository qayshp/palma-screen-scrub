package dev.palma.screenscrub

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LiveLogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private var paused = false
    private val update = object : Runnable {
        override fun run() {
            if (!paused) {
                text.text = DiagnosticLog.eventText().ifBlank { "No app diagnostic events yet." }
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(Button(this).apply {
            text = "Pause"
            isAllCaps = false
            setOnClickListener {
                paused = !paused
                text = if (paused) "Resume" else "Pause"
            }
        })
        root.addView(Button(this).apply {
            text = "Copy live log"
            isAllCaps = false
            setOnClickListener { DiagnosticLog.copyText(this@LiveLogActivity, "Palma live log", DiagnosticLog.eventText()) }
        })
        text = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(update)
    }

    override fun onPause() {
        handler.removeCallbacks(update)
        super.onPause()
    }
}
