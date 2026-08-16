package dev.palma.screenscrub

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RunHistoryActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(Button(this).apply {
            text = "Copy history"
            isAllCaps = false
            setOnClickListener { DiagnosticLog.copyText(this@RunHistoryActivity, "Palma run history", renderHistory()) }
        })
        root.addView(Button(this).apply {
            text = "Clear history"
            isAllCaps = false
            setOnClickListener {
                DiagnosticLog.clearHistory(this@RunHistoryActivity)
                refresh()
            }
        })
        root.addView(annotationRow("Clean", "Missed band", "No visible effect"))
        text = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        text.text = renderHistory()
    }

    private fun annotationRow(vararg labels: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        labels.forEach { label ->
            addView(Button(this@RunHistoryActivity).apply {
                text = label
                textSize = 10f
                isAllCaps = false
                setOnClickListener {
                    DiagnosticLog.annotateNewest(this@RunHistoryActivity, label)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderHistory(): String = DiagnosticLog.history(this)
        .asReversed()
        .joinToString("\n\n") { entry -> entry.toString(2) }
        .ifBlank { "No completed runs saved." }
}
