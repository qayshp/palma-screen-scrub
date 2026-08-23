package dev.palma.screenscrub

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class ScrubSettingsActivity : Activity() {
    private lateinit var preferences: ScrubPreferences
    private lateinit var selectedSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = ScrubPreferences(this)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val selected = preferences.productionProfile()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(text("Palma Screen Scrub", 24f, dp(6)))
        content.addView(text("Default clear style", 17f, dp(14)))

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        ScrubProfile.entries.forEach { profile ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = profile.displayName
                textSize = 18f
                setTextColor(Color.BLACK)
                isChecked = profile == selected
                contentDescription = "${profile.displayName}: ${profile.description}"
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectProfile(profile)
                }
            }
            group.addView(button, RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
            group.addView(text(profile.description, 14f, dp(2)).apply {
                setPadding(dp(48), 0, dp(8), 0)
            })
            group.addView(text(profile.summary, 13f, dp(14)).apply {
                setPadding(dp(48), 0, dp(8), 0)
            })
        }
        content.addView(group)
        content.addView(text("Selected profile", 15f, dp(6)))
        selectedSummary = text("${selected.displayName}\n${selected.summary}", 15f, 0)
        content.addView(selectedSummary)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun selectProfile(profile: ScrubProfile) {
        preferences.setProductionProfile(profile)
        selectedSummary.text = "${profile.displayName}\n${profile.summary}"
        DiagnosticLog.event("I", "PRODUCTION_CONFIG", "PROFILE_SELECTED id=${profile.stableId}")
    }

    private fun text(value: String, size: Float, bottomPadding: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(0, 0, 0, bottomPadding)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
