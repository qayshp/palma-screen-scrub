package dev.palma.screenscrub

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val epdBridge = BooxEpdBridge()
    private val surfaceFlingerBridge = SurfaceFlingerEpdBridge()
    private lateinit var preferences: ScrubPreferences
    private lateinit var testView: InkTestView
    private lateinit var controls: View
    private lateinit var status: TextView
    private var activeSequence: Runnable? = null
    private var diagnosticGeneration = 0
    private var awaitingPatternDraw = false
    private var afterPatternDraw: (() -> Unit)? = null
    private var overlayController: OverlayScrubController? = null
    private var launchedPendingOverlay = false
    private var automationMode = false
    private var automationHostRunId = "none"
    private var cameraMode = false
    private var cameraRunIndex = 0
    private var cameraRunTotal = 0
    private var cameraProfile = ""
    private var cameraSlateMs = 2_500L
    private var cameraPreconditionMs = 600L
    private var cameraBaselineMs = 800L
    private var productionToken: String? = null
    private var productionProfile: ScrubProfile? = null
    private var productionStartedAtNs = 0L
    private var overlayPermissionRequestActive = false
    private var overlayPermissionScreenWasShown = false

    private lateinit var bandInput: EditText
    private lateinit var blackInput: EditText
    private lateinit var whiteInput: EditText
    private lateinit var safetyInput: EditText
    private lateinit var settleInput: EditText
    private lateinit var quietInput: EditText
    private lateinit var repeatInput: EditText
    private lateinit var duplicateInput: EditText
    private lateinit var reverseInput: CheckBox
    private lateinit var experimentalInput: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = ScrubPreferences(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        testView = InkTestView(this)
        root.addView(testView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        showSystemBars()
        testView.onPatternDrawn = { pattern, drawnAtNs ->
            if (awaitingPatternDraw) {
                awaitingPatternDraw = false
                DiagnosticLog.event("I", "DRAW", "DRAW pattern=$pattern elapsedMs=${elapsedMs(drawnAtNs)}")
            }
            afterPatternDraw?.let { action ->
                afterPatternDraw = null
                action()
            }
        }

        when {
            isAlias(AUTOMATION_ALIAS) || intent.action == ACTION_AUTOMATION -> launchAutomation()
            isAlias(DIAGNOSTICS_ALIAS) || intent.action == ACTION_DIAGNOSTICS -> showDiagnostics(root)
            isAlias(QUICK_SCRUB_ALIAS) || intent.action == ACTION_QUICK_SCRUB -> launchProductionQuickScrub("launcher-quick")
            isAlias(QUICK_16_ALIAS) || intent.action == ACTION_QUICK_16 -> launchQuickScrub("launcher-16-band", preferences.quickConfig().copy(bandCount = 16))
            isAlias(QUICK_OVERLAY_ALIAS) || intent.action == ACTION_QUICK_OVERLAY -> launchOverlayScrub("launcher-overlay", returnAfter = true)
            isAlias(QUICK_ACCESSIBILITY_ALIAS) || intent.action == ACTION_QUICK_ACCESSIBILITY -> launchAccessibilityWhiteRefresh("launcher-accessibility")
            else -> launchProductionQuickScrub("default-package-launch")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val profile = preferences.productionProfile()
        if (productionToken != null) {
            val moved = moveTaskToBack(true)
            DiagnosticLog.event(
                "W",
                "PRODUCTION",
                "PRODUCTION_LAUNCH_IGNORED alreadyActive=true requestedProfileId=${profile.stableId} " +
                    "source=launcher-quick-relaunch taskReturnedToBackground=$moved",
            )
            return
        }
        launchProductionQuickScrub("launcher-quick-relaunch")
    }

    override fun onResume() {
        super.onResume()
        showSystemBars()
        if (preferences.pendingAction() == PENDING_PRODUCTION_QUICK) {
            if (Settings.canDrawOverlays(this)) {
                val pendingProfile = preferences.pendingProductionProfile()
                preferences.setPendingAction(null)
                preferences.setPendingProductionProfile(null)
                overlayPermissionRequestActive = false
                overlayPermissionScreenWasShown = false
                if (productionToken == null && !acquireProductionGate(pendingProfile, "overlay-permission-return")) return
                startProductionOverlay(pendingProfile, "overlay-permission-return")
            } else if (overlayPermissionScreenWasShown) {
                preferences.setPendingAction(null)
                preferences.setPendingProductionProfile(null)
                overlayPermissionRequestActive = false
                overlayPermissionScreenWasShown = false
                DiagnosticLog.event("E", "PRODUCTION", "PRODUCTION_PERMISSION_DENIED cleanup=true")
                releaseProductionGate()
                finishAndRemoveTask()
            }
            return
        }
        if (!launchedPendingOverlay && preferences.pendingAction() in setOf(PENDING_OVERLAY_QUICK, PENDING_OVERLAY_MENU) && Settings.canDrawOverlays(this)) {
            launchedPendingOverlay = true
            val returnAfter = preferences.pendingAction() == PENDING_OVERLAY_QUICK
            preferences.setPendingAction(null)
            launchOverlayScrub("overlay-permission-return", returnAfter)
        }
    }

    override fun onPause() {
        if (overlayPermissionRequestActive) overlayPermissionScreenWasShown = true
        super.onPause()
    }

    override fun onDestroy() {
        cancelSequence()
        overlayController?.cancel()
        releaseProductionGate()
        super.onDestroy()
    }

    private fun createDiagnosticsControls(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 11f
        }
        layout.addView(status)
        layout.addView(section("Diagnostic scrub configuration"))
        val config = preferences.quickConfig()
        bandInput = numberInput(config.bandCount.toString(), "bands")
        blackInput = numberInput(config.blackDwellMs.toString(), "black ms")
        whiteInput = numberInput(config.whiteDwellMs.toString(), "white ms")
        safetyInput = numberInput(config.safetyDelayMs.toString(), "safety ms")
        settleInput = numberInput(config.finalSettleMs.toString(), "settle ms")
        quietInput = numberInput(config.diagnosticQuietHoldMs.toString(), "quiet ms")
        repeatInput = numberInput(config.repeatCount.toString(), "repeats")
        duplicateInput = numberInput(config.duplicateSubmissions.toString(), "duplicates")
        reverseInput = CheckBox(this).apply { text = "bottom → top"; isChecked = config.bottomToTop; textSize = 10f }
        layout.addView(inputRow(bandInput, blackInput, whiteInput))
        layout.addView(inputRow(safetyInput, settleInput, quietInput))
        layout.addView(inputRow(repeatInput, duplicateInput, reverseInput))
        layout.addView(row("Save diagnostic config", "Run saved diagnostic", ::saveQuickConfig, { runConfiguredScrub("known-good-fixed", "menu", readConfig(), false) }))
        experimentalInput = CheckBox(this).apply {
            text = "Enable newly discovered public API tests (off by default; scope APIs remain disabled)"
            textSize = 10f
            isChecked = preferences.experimentalApisEnabled()
            setOnCheckedChangeListener { _, enabled ->
                preferences.setExperimentalApisEnabled(enabled)
                DiagnosticLog.event("W", "EXPERIMENTAL", "EXPERIMENTAL_API_TOGGLE enabled=$enabled; public one-shot/refresh tests only; scope APIs disabled")
            }
        }
        layout.addView(experimentalInput)
        layout.addView(section("Sweep experiments"))
        layout.addView(row("16-band fixed", "Whole black → white", { runConfiguredScrub("16-band-fixed", "menu", readConfig().copy(bandCount = 16), false) }, { runWholeBlackWhite() }))
        layout.addView(row("Checker → inverse → white", "Band matrix 2/4/8/16/32", { runCheckerSequence() }, ::runTimingSweep))
        layout.addView(row("16 bands + controller wait", "16 bands + device wait", { runWaitScrub(BooxEpdBridge.WaitTarget.CONTROLLER, readConfig().copy(bandCount = 16)) }, { runWaitScrub(BooxEpdBridge.WaitTarget.CURRENT_DEVICE, readConfig().copy(bandCount = 16)) }))
        layout.addView(row("True global GC 0x62", "16 bands + real SF wait", { runTrueGlobalGc(readConfig(), "menu") }, { runRealWaitScrub(readConfig().copy(bandCount = 16), "menu") }))
        layout.addView(section("GC/full-refresh experiments"))
        layout.addView(row("GC 1: controller invalidate", "GC 2: device manager", { runGc("controller-invalidate", "EpdController.invalidate(View, UpdateMode.GC)", { "view=${testView.width}x${testView.height}" }) { epdBridge.invalidateGc(testView) } }, { runGc("device-manager", "EpdDeviceManager.applyGCUpdate(View)", { "view=${testView.width}x${testView.height}" }) { epdBridge.applyManagerGc(testView) } }))
        layout.addView(row("GC 3: repaint everything", "GC explicit app rect", ::runRepaintEverythingGc, ::runExplicitGcRect))
        layout.addView(section("New reflected public API experiments"))
        layout.addView(row("refreshScreen GC", "refreshScreenRegion GC", { runExperimentalGc("refresh-screen-gc", "EpdController.refreshScreen(View, UpdateMode.GC)", { "view=${testView.width}x${testView.height}" }) { epdBridge.refreshScreenGc(testView) } }, { runExperimentalGc("refresh-screen-region-gc", "EpdController.refreshScreenRegion(View, 0, 0, width, height, UpdateMode.GC)", { "0,0,${testView.width},${testView.height}" }) { epdBridge.refreshScreenRegionGc(testView) } }))
        layout.addView(row("Native refresh mode 5", "Native region mode 5", { runExperimentalGc("native-refresh-mode-5", "View.refreshScreen(5)", { "view=${testView.width}x${testView.height}" }, "native-int-5") { epdBridge.refreshScreenNative(testView, 5) } }, { runExperimentalGc("native-refresh-region-mode-5", "View.refreshScreen(0, 0, width, height, 5)", { "0,0,${testView.width},${testView.height}" }, "native-int-5") { epdBridge.refreshScreenRegionNative(testView, 5) } }))
        layout.addView(row("Native GC 0x62", "Native region GC 0x62", { runExperimentalGc("native-refresh-gc-0x62", "View.refreshScreen(0x62)", { "view=${testView.width}x${testView.height}" }, "native-int-0x62") { epdBridge.refreshScreenNative(testView, 0x62) } }, { runExperimentalGc("native-refresh-region-gc-0x62", "View.refreshScreen(0, 0, width, height, 0x62)", { "0,0,${testView.width},${testView.height}" }, "native-int-0x62") { epdBridge.refreshScreenRegionNative(testView, 0x62) } }))
        layout.addView(button("Native repaint entire panel GC 0x62") { runExperimentalGc("native-repaint-entire-panel-gc-0x62", "ViewUpdateHelper.repaintEverything(0x62)", { "firmware full-display request" }, "native-int-0x62") { epdBridge.repaintEverythingNative(0x62) } })
        layout.addView(row("Controller GCOnce only", "SDM GCOnce only", { runExperimentalGc("controller-gc-once-only", "EpdController.applyGCOnce()", { "none" }, "implicit") { epdBridge.applyControllerGcOnce() } }, { runExperimentalGc("sdm-gc-once-only", "Device.currentDevice().applyGCOnce()", { "none" }, "implicit") { epdBridge.applyCurrentDeviceGcOnce() } }))
        layout.addView(row("Controller GCOnce → invalidate", "SDM GCOnce → invalidate", { runExperimentalGc("controller-gc-once-invalidate", "EpdController.applyGCOnce()", { "next View.invalidate(); view=${testView.width}x${testView.height}" }, "implicit", true) { epdBridge.applyControllerGcOnce() } }, { runExperimentalGc("sdm-gc-once-invalidate", "Device.currentDevice().applyGCOnce()", { "next View.invalidate(); view=${testView.width}x${testView.height}" }, "implicit", true) { epdBridge.applyCurrentDeviceGcOnce() } }))
        layout.addView(button("Control: normal invalidate without applyGCOnce") { runNormalInvalidateControl() })
        layout.addView(row("GC interval false", "GC interval true", { runExperimentalGc("gc-interval-false", "EpdDeviceManager.refreshScreenWithGCInterval(View, false)", { "view=${testView.width}x${testView.height}" }, "policy-dependent") { epdBridge.refreshManagerGcInterval(testView, false) } }, { runExperimentalGc("gc-interval-true", "EpdDeviceManager.refreshScreenWithGCInterval(View, true)", { "view=${testView.width}x${testView.height}" }, "policy-dependent") { epdBridge.refreshManagerGcInterval(testView, true) } }))
        layout.addView(row("GC interval without Regal", "GC interval with Regal", { runExperimentalGc("gc-interval-without-regal", "EpdDeviceManager.refreshScreenWithGCIntervalWithoutRegal(View)", { "view=${testView.width}x${testView.height}" }, "policy-dependent") { epdBridge.refreshManagerGcIntervalWithoutRegal(testView) } }, { runExperimentalGc("gc-interval-with-regal", "EpdDeviceManager.refreshScreenWithGCIntervalWithRegal(View)", { "view=${testView.width}x${testView.height}" }, "policy-dependent") { epdBridge.refreshManagerGcIntervalWithRegal(testView) } }))
        layout.addView(section("Overlay and reporting"))
        layout.addView(row("Overlay known-good sweep", "Overlay 16-band sweep", { launchOverlayScrub("menu-overlay", returnAfter = false) }, { launchOverlayScrub("menu-overlay-16", returnAfter = false, config = readConfig().copy(bandCount = 16)) }))
        layout.addView(row("Accessibility white refresh", "Log API inventory", { launchAccessibilityWhiteRefresh("menu-accessibility") }, ::logApiInventory))
        layout.addView(button("Log firmware mappings (read-only)", ::logFirmwareMappingInventory))
        layout.addView(row("Copy full report", "Live log", ::copyFullReport, { startActivity(Intent(this, LiveLogActivity::class.java)) }))
        layout.addView(button("Run history") { startActivity(Intent(this, RunHistoryActivity::class.java)) })
        layout.addView(button("Stop current test") { stopDiagnostic() })
        return ScrollView(this).apply { addView(layout) }
    }

    private fun section(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.BLACK)
        textSize = 12f
        setPadding(2, dp(3), 2, 0)
    }

    private fun inputRow(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun numberInput(value: String, hint: String): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 10f
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setSelectAllOnFocus(false)
        setSingleLine(true)
    }

    private fun row(first: String, second: String, firstAction: () -> Unit, secondAction: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(first, firstAction), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button(second, secondAction), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun readConfig(): ScrubConfig = ScrubConfig(
        bandCount = bandInput.text.toString().toIntOrNull() ?: 16,
        blackDwellMs = blackInput.text.toString().toLongOrNull() ?: 220L,
        whiteDwellMs = whiteInput.text.toString().toLongOrNull() ?: 220L,
        safetyDelayMs = safetyInput.text.toString().toLongOrNull() ?: 0L,
        finalSettleMs = settleInput.text.toString().toLongOrNull() ?: 500L,
        diagnosticQuietHoldMs = quietInput.text.toString().toLongOrNull() ?: 3_000L,
        repeatCount = repeatInput.text.toString().toIntOrNull() ?: 1,
        duplicateSubmissions = duplicateInput.text.toString().toIntOrNull() ?: 1,
        bottomToTop = reverseInput.isChecked,
    ).sanitized()

    private fun saveQuickConfig() {
        val config = readConfig()
        preferences.saveQuickConfig(config)
        setStatus("Quick Scrub saved: ${config.summary()}")
        DiagnosticLog.event("I", "CONFIG", "QUICK_CONFIG_SAVED ${config.summary()}")
    }

    private fun saveExternalLaunchAction(action: ExternalLaunchAction) {
        preferences.setExternalLaunchAction(action)
        setStatus("Default package / BOOX quick action: ${action.label}")
        DiagnosticLog.event("I", "CONFIG", "EXTERNAL_LAUNCH_ACTION ${action.storedValue}")
    }

    private fun showDiagnostics(root: FrameLayout) {
        controls = createDiagnosticsControls()
        root.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setStatus(
            "Diagnostics ready. Production default: ${preferences.productionProfile().displayName}. " +
                "Diagnostic config: ${preferences.quickConfig().summary()}",
        )
    }

    private fun launchQuickScrub(source: String, suppliedConfig: ScrubConfig = preferences.quickConfig()) {
        controls = View(this)
        testView.post { runConfiguredScrub("quick-scrub", source, suppliedConfig, true) }
    }

    private fun launchProductionQuickScrub(source: String) {
        controls = View(this)
        val profile = preferences.productionProfile()
        if (!acquireProductionGate(profile, source)) return
        if (!Settings.canDrawOverlays(this)) {
            preferences.setPendingAction(PENDING_PRODUCTION_QUICK)
            preferences.setPendingProductionProfile(profile)
            overlayPermissionRequestActive = true
            DiagnosticLog.event("I", "PRODUCTION", "PRODUCTION_PERMISSION_REQUIRED profileId=${profile.stableId} source=$source")
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure { error ->
                preferences.setPendingAction(null)
                preferences.setPendingProductionProfile(null)
                overlayPermissionRequestActive = false
                DiagnosticLog.event("E", "PRODUCTION", "PRODUCTION_PERMISSION_FLOW_FAILED ${error.javaClass.simpleName}: ${error.message}")
                releaseProductionGate()
                finishAndRemoveTask()
            }
            return
        }
        preferences.setPendingAction(null)
        preferences.setPendingProductionProfile(null)
        startProductionOverlay(profile, source)
    }

    private fun acquireProductionGate(profile: ScrubProfile, source: String): Boolean {
        val token = UUID.randomUUID().toString()
        if (!ProductionScrubGate.tryAcquire(token)) {
            val moved = moveTaskToBack(true)
            DiagnosticLog.event(
                "W",
                "PRODUCTION",
                "PRODUCTION_LAUNCH_IGNORED alreadyActive=true requestedProfileId=${profile.stableId} source=$source " +
                    "taskReturnedToBackground=$moved",
            )
            finish()
            return false
        }
        productionToken = token
        productionProfile = profile
        return true
    }

    private fun startProductionOverlay(profile: ScrubProfile, source: String) {
        productionProfile = profile
        productionStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val config = profile.config
        DiagnosticLog.event(
            "I",
            "PRODUCTION",
            "PRODUCTION_START profileId=${profile.stableId} overlayMode=${profile.presentationMode.wireValue} " +
                "bands=${config.bandCount} blackMs=${config.blackDwellMs} whiteMs=${config.whiteDwellMs} " +
                "duplicates=${config.duplicateSubmissions} direction=${if (config.bottomToTop) "bottom-to-top" else "top-to-bottom"} " +
                "startElapsedMs=${SystemClock.elapsedRealtime()}",
        )
        launchOverlayScrub(
            source = source,
            returnAfter = true,
            config = config,
            presentationMode = profile.presentationMode,
            backgroundUnderOverlay = true,
            production = profile,
        )
    }

    private fun releaseProductionGate() {
        productionToken?.let(ProductionScrubGate::release)
        productionToken = null
        productionProfile = null
        productionStartedAtNs = 0L
    }

    private fun launchAutomation() {
        controls = View(this)
        automationMode = true
        automationHostRunId = intent.getStringExtra(EXTRA_HOST_RUN_ID)?.take(80) ?: "unspecified"
        val experiment = intent.getStringExtra(EXTRA_EXPERIMENT)?.trim().orEmpty()
        cameraMode = intent.getBooleanExtra(EXTRA_CAMERA_MODE, false)
        cameraRunIndex = intent.getIntExtra(EXTRA_CAMERA_INDEX, 0).coerceAtLeast(0)
        cameraRunTotal = intent.getIntExtra(EXTRA_CAMERA_TOTAL, 0).coerceAtLeast(0)
        cameraProfile = intent.getStringExtra(EXTRA_CAMERA_PROFILE)?.take(60).orEmpty()
        cameraSlateMs = intent.getLongExtra(EXTRA_CAMERA_SLATE_MS, 2_500L).coerceIn(500L, 30_000L)
        cameraPreconditionMs = intent.getLongExtra(EXTRA_CAMERA_PRECONDITION_MS, 600L).coerceIn(100L, 5_000L)
        cameraBaselineMs = intent.getLongExtra(EXTRA_CAMERA_BASELINE_MS, 800L).coerceIn(100L, 5_000L)
        val config = ScrubConfig(
            bandCount = intent.getIntExtra(EXTRA_BANDS, 16),
            blackDwellMs = intent.getLongExtra(EXTRA_BLACK_MS, 50L),
            whiteDwellMs = intent.getLongExtra(EXTRA_WHITE_MS, 50L),
            safetyDelayMs = intent.getLongExtra(EXTRA_SAFETY_MS, 0L),
            finalSettleMs = intent.getLongExtra(EXTRA_SETTLE_MS, 500L),
            diagnosticQuietHoldMs = intent.getLongExtra(EXTRA_QUIET_MS, 1_000L),
            repeatCount = intent.getIntExtra(EXTRA_REPEATS, 1),
            duplicateSubmissions = intent.getIntExtra(EXTRA_DUPLICATES, 1),
            bottomToTop = intent.getBooleanExtra(EXTRA_BOTTOM_TO_TOP, false),
        ).sanitized()
        val overlayPresentation = OverlayScrubController.PresentationMode.fromWireValue(
            intent.getStringExtra(EXTRA_OVERLAY_PRESENTATION),
        )
        DiagnosticLog.event(
            "I",
            "AUTOMATION",
            "AUTOMATION_REQUEST hostRunId=$automationHostRunId experiment=$experiment config=${config.summary()} " +
                "overlayPresentation=${overlayPresentation.wireValue}",
        )
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || !intent.getBooleanExtra(EXTRA_CONFIRM_SAFE, false)) {
            testView.post { finishUnsupportedAutomation(experiment, "debug build and confirmSafe=true are required") }
            return
        }
        testView.post {
            if (cameraMode && experiment != "camera-slate") {
                runCameraPrelude(experiment, config)
            } else {
                runAutomationExperiment(experiment, config, overlayPresentation)
            }
        }
    }

    private fun runAutomationExperiment(
        experiment: String,
        config: ScrubConfig,
        overlayPresentation: OverlayScrubController.PresentationMode = OverlayScrubController.PresentationMode.T0_OPAQUE,
    ) {
        val source = "automation:$automationHostRunId"
        when (experiment) {
            "scrub" -> runConfiguredScrub("automation-scrub", source, config, false)
            "whole-black-white" -> runWholeBlackWhite(config, source)
            "checker-inverse-white" -> runCheckerSequence(config, source)
            "wait-controller" -> runWaitScrub(BooxEpdBridge.WaitTarget.CONTROLLER, config, source)
            "wait-device" -> runWaitScrub(BooxEpdBridge.WaitTarget.CURRENT_DEVICE, config, source)
            "true-global-gc" -> runTrueGlobalGc(config, source)
            "true-global-gc-wait" -> runTrueGlobalGcWithWait(config, source)
            "real-wait-scrub" -> runRealWaitScrub(config, source)
            "controller-invalidate" -> runGc(experiment, "EpdController.invalidate(View, UpdateMode.GC)", { "view=${testView.width}x${testView.height}" }, config = config, source = source) { epdBridge.invalidateGc(testView) }
            "device-manager" -> runGc(experiment, "EpdDeviceManager.applyGCUpdate(View)", { "view=${testView.width}x${testView.height}" }, config = config, source = source) { epdBridge.applyManagerGc(testView) }
            "gc-repaint-everything" -> runGc(experiment, "EpdController.repaintEveryThing(UpdateMode.GC)", { "API-defined global/full target" }, config = config, source = source, action = epdBridge::repaintEverythingGc)
            "gc-explicit-app-rect" -> runGc(experiment, "EpdController.invalidate(View, 0, 0, width, height, UpdateMode.GC)", { "0,0,${testView.width},${testView.height}" }, config = config, source = source) { epdBridge.invalidateGcRect(testView) }
            "refresh-screen-gc" -> runGc(experiment, "EpdController.refreshScreen(View, UpdateMode.GC)", { "view=${testView.width}x${testView.height}" }, config = config, source = source) { epdBridge.refreshScreenGc(testView) }
            "refresh-screen-region-gc" -> runGc(experiment, "EpdController.refreshScreenRegion(View, 0, 0, width, height, UpdateMode.GC)", { "0,0,${testView.width},${testView.height}" }, config = config, source = source) { epdBridge.refreshScreenRegionGc(testView) }
            "native-refresh-mode-5" -> runNativeAutomation(experiment, 5, false, config, source)
            "native-refresh-region-mode-5" -> runNativeAutomation(experiment, 5, true, config, source)
            "native-refresh-gc-0x62" -> runNativeAutomation(experiment, 0x62, false, config, source)
            "native-refresh-region-gc-0x62" -> runNativeAutomation(experiment, 0x62, true, config, source)
            "native-refresh-custom" -> runNativeAutomation(experiment, intent.getIntExtra(EXTRA_NATIVE_MODE, Int.MIN_VALUE), false, config, source)
            "native-refresh-region-custom" -> runNativeAutomation(experiment, intent.getIntExtra(EXTRA_NATIVE_MODE, Int.MIN_VALUE), true, config, source)
            "native-region-sequence" -> runNativeRegionSequence(
                experiment = experiment,
                nativeMode = intent.getIntExtra(EXTRA_NATIVE_MODE, Int.MIN_VALUE),
                sequenceName = intent.getStringExtra(EXTRA_NATIVE_SEQUENCE).orEmpty(),
                interRequestDelayMs = intent.getLongExtra(EXTRA_SEQUENCE_DELAY_MS, 30L),
                submissionCount = intent.getIntExtra(EXTRA_SEQUENCE_COUNT, 12),
                regionDivisor = intent.getIntExtra(EXTRA_REGION_DIVISOR, 1),
                alternateNativeMode = intent.getIntExtra(EXTRA_ALTERNATE_NATIVE_MODE, Int.MIN_VALUE),
                config = config,
                source = source,
            )
            "white-only" -> runWhiteOnly(config, source)
            "overlay-scrub" -> launchOverlayScrub(source, returnAfter = true, config = config, presentationMode = overlayPresentation)
            "overlay-seed-hold" -> launchOverlayScrub(
                source,
                returnAfter = true,
                config = config,
                presentationMode = OverlayScrubController.PresentationMode.T0_OPAQUE,
                sequenceMode = OverlayScrubController.SequenceMode.GHOST_SEED,
                backgroundUnderOverlay = true,
            )
            "overlay-real-wait-scrub" -> launchOverlayScrub(source, returnAfter = true, config = config, useRealWait = true)
            "accessibility-white" -> runAccessibilityAutomation(config, source)
            "camera-slate" -> runCameraSlate(config, source)
            "camera-sync" -> runCameraSync(config, source)
            "native-repaint-entire-panel-gc-0x62" -> runGc(experiment, "ViewUpdateHelper.repaintEverything(0x62)", { "firmware full-display request" }, "native-int-0x62", config = config, source = source) { epdBridge.repaintEverythingNative(0x62) }
            "controller-gc-once-only" -> runGc(experiment, "EpdController.applyGCOnce()", { "none" }, "implicit", config = config, source = source, action = epdBridge::applyControllerGcOnce)
            "sdm-gc-once-only" -> runGc(experiment, "Device.currentDevice().applyGCOnce()", { "none" }, "implicit", config = config, source = source, action = epdBridge::applyCurrentDeviceGcOnce)
            "controller-gc-once-invalidate" -> runGc(experiment, "EpdController.applyGCOnce()", { "next View.invalidate(); view=${testView.width}x${testView.height}" }, "implicit", true, config, source, epdBridge::applyControllerGcOnce)
            "sdm-gc-once-invalidate" -> runGc(experiment, "Device.currentDevice().applyGCOnce()", { "next View.invalidate(); view=${testView.width}x${testView.height}" }, "implicit", true, config, source, epdBridge::applyCurrentDeviceGcOnce)
            "normal-invalidate-control" -> runGc(experiment, "View.invalidate()", { "Android-selected dirty region for view=${testView.width}x${testView.height}" }, "normal Android", true, config, source) { BooxEpdBridge.ApiResult.Success("No applyGCOnce; proceeding to control View.invalidate()") }
            "gc-interval-false" -> runGc(experiment, "EpdDeviceManager.refreshScreenWithGCInterval(View, false)", { "view=${testView.width}x${testView.height}" }, "policy-dependent", config = config, source = source) { epdBridge.refreshManagerGcInterval(testView, false) }
            "gc-interval-true" -> runGc(experiment, "EpdDeviceManager.refreshScreenWithGCInterval(View, true)", { "view=${testView.width}x${testView.height}" }, "policy-dependent", config = config, source = source) { epdBridge.refreshManagerGcInterval(testView, true) }
            "gc-interval-without-regal" -> runGc(experiment, "EpdDeviceManager.refreshScreenWithGCIntervalWithoutRegal(View)", { "view=${testView.width}x${testView.height}" }, "policy-dependent", config = config, source = source) { epdBridge.refreshManagerGcIntervalWithoutRegal(testView) }
            "gc-interval-with-regal" -> runGc(experiment, "EpdDeviceManager.refreshScreenWithGCIntervalWithRegal(View)", { "view=${testView.width}x${testView.height}" }, "policy-dependent", config = config, source = source) { epdBridge.refreshManagerGcIntervalWithRegal(testView) }
            "api-inventory" -> runAutomationInventory(experiment, source, epdBridge.relatedApiMethods())
            "firmware-mapping" -> runAutomationInventory(experiment, source, epdBridge.firmwareMappingInventory(testView))
            "regal-policy-inventory" -> runAutomationInventory(
                experiment,
                source,
                epdBridge.regalPolicyInventory(
                    this,
                    listOf("com.microsoft.launcher", "com.android.settings", packageName),
                ),
            )
            else -> finishUnsupportedAutomation(experiment, "unsupported experiment")
        }
    }

    private fun runCameraPrelude(experiment: String, config: ScrubConfig) {
        val runNumber = if (cameraRunTotal > 0) "$cameraRunIndex / $cameraRunTotal" else cameraRunIndex.toString()
        val detail = intent.getStringExtra(EXTRA_CAMERA_DETAIL)?.take(120).orEmpty().ifBlank { config.summary() }
        val title = intent.getStringExtra(EXTRA_CAMERA_TITLE)?.take(60).orEmpty().ifBlank { "RUN $runNumber" }
        val detailLines = detail.split('|').map(String::trim).filter(String::isNotEmpty)
        testView.showCameraSlate(
            title,
            listOf(cameraProfile.ifBlank { "camera suite" }, experiment) + detailLines + "ID $automationHostRunId",
        )
            DiagnosticLog.event(
                "I",
                "CAMERA",
                "CAMERA_SLATE_BEGIN hostRunId=$automationHostRunId index=$cameraRunIndex total=$cameraRunTotal " +
                "profile=$cameraProfile experiment=$experiment title=$title " +
                    "detail=${detailLines.joinToString("|")} durationMs=$cameraSlateMs",
            )
        var stage = 0
        val advance = object : Runnable {
            override fun run() {
                when (stage++) {
                    0 -> {
                        DiagnosticLog.event("I", "CAMERA", "CAMERA_SLATE_END hostRunId=$automationHostRunId")
                        DiagnosticLog.event(
                            "I",
                            "CAMERA",
                            "PRECONDITION_BEGIN hostRunId=$automationHostRunId checkerMs=$cameraPreconditionMs baselineMs=$cameraBaselineMs",
                        )
                        testView.show(InkTestView.Pattern.CHECKERBOARD)
                        handler.postDelayed(this, cameraPreconditionMs)
                    }
                    1 -> {
                        testView.show(InkTestView.Pattern.INVERTED_CHECKERBOARD)
                        handler.postDelayed(this, cameraPreconditionMs)
                    }
                    2 -> {
                        testView.showSolidWhite()
                        handler.postDelayed(this, cameraBaselineMs)
                    }
                    else -> {
                        DiagnosticLog.event("I", "CAMERA", "PRECONDITION_END hostRunId=$automationHostRunId framebuffer=solid-white")
                        activeSequence = null
                        runAutomationExperiment(experiment, config)
                    }
                }
            }
        }
        activeSequence = advance
        handler.postDelayed(advance, cameraSlateMs)
    }

    private fun runCameraSlate(config: ScrubConfig, source: String) {
        val title = intent.getStringExtra(EXTRA_CAMERA_TITLE)?.take(40).orEmpty().ifBlank { "CAMERA SUITE" }
        val detail = intent.getStringExtra(EXTRA_CAMERA_DETAIL)?.take(120).orEmpty()
        val generation = startDiagnostic("camera-slate", source, "title=$title; durationMs=$cameraSlateMs")
        testView.showCameraSlate(title, listOf(cameraProfile.ifBlank { "camera suite" }, detail, "ID $automationHostRunId"))
        DiagnosticLog.event("I", "CAMERA", "CAMERA_SLATE_BEGIN hostRunId=$automationHostRunId title=$title durationMs=$cameraSlateMs")
        handler.postDelayed({
            if (generation != diagnosticGeneration) return@postDelayed
            DiagnosticLog.event("I", "CAMERA", "CAMERA_SLATE_END hostRunId=$automationHostRunId title=$title")
            finishAutomationPreservingFrame("camera slate complete")
        }, cameraSlateMs)
    }

    private fun runCameraSync(config: ScrubConfig, source: String) {
        val phaseMs = config.blackDwellMs.coerceIn(250L, 3_000L)
        startDiagnostic("camera-sync", source, "phaseMs=$phaseMs sequence=WHITE-BLACK-WHITE-BLACK-WHITE")
        val patterns = listOf(
            InkTestView.Pattern.WHITE,
            InkTestView.Pattern.BLACK,
            InkTestView.Pattern.WHITE,
            InkTestView.Pattern.BLACK,
            InkTestView.Pattern.WHITE,
        )
        fun show(index: Int) {
            if (index >= patterns.size) {
                finishDiagnostic("camera synchronization complete")
                return
            }
            val pattern = patterns[index]
            testView.show(pattern)
            DiagnosticLog.event("I", "CAMERA_SYNC", "phase=${index + 1}/${patterns.size} pattern=$pattern durationMs=$phaseMs")
            handler.postDelayed({ show(index + 1) }, phaseMs)
        }
        show(0)
    }

    private fun runWhiteOnly(config: ScrubConfig, source: String) {
        val generation = startDiagnostic("white-only", source, "quietMs=${config.diagnosticQuietHoldMs}; hostRunId=$automationHostRunId")
        testView.showSolidWhite()
        DiagnosticLog.event("I", "REQUEST", "WHITE_ONLY framebuffer=solid-white")
        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=${config.diagnosticQuietHoldMs} experiment=white-only")
        handler.postDelayed({
            if (generation != diagnosticGeneration) return@postDelayed
            DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=${config.diagnosticQuietHoldMs} experiment=white-only")
            finishDiagnostic("white-only complete")
        }, config.diagnosticQuietHoldMs)
    }

    private fun runAccessibilityAutomation(config: ScrubConfig, source: String) {
        testView.showSolidWhite()
        val service = AccessibilityRefreshService.active()
        if (service == null) {
            finishUnsupportedAutomation("accessibility-white", "accessibility service is not enabled")
            return
        }
        service.startLogicalWhiteRefresh(config, source) { result ->
            handler.post {
                testView.showSolidWhite()
                DiagnosticLog.event("I", "AUTOMATION", "AUTOMATION_END hostRunId=$automationHostRunId result=$result")
                handler.postDelayed({ finishAndRemoveTask() }, AutomationExitDelayMs)
            }
        }
    }

    private fun runNativeAutomation(experiment: String, nativeMode: Int, region: Boolean, config: ScrubConfig, source: String) {
        val allowedModes = setOf(0x2, 0x5, 0x22, 0x42, 0x62)
        if (nativeMode !in allowedModes) {
            finishUnsupportedAutomation(experiment, "native mode $nativeMode is not in the safe diagnostic allowlist")
            return
        }
        val modeLabel = "native-int-0x${nativeMode.toString(16)}"
        if (region) {
            val left = intent.getIntExtra(EXTRA_NATIVE_LEFT, 0).coerceIn(0, testView.width - 1)
            val top = intent.getIntExtra(EXTRA_NATIVE_TOP, 0).coerceIn(0, testView.height - 1)
            val width = intent.getIntExtra(EXTRA_NATIVE_WIDTH, testView.width - left).coerceIn(1, testView.width - left)
            val height = intent.getIntExtra(EXTRA_NATIVE_HEIGHT, testView.height - top).coerceIn(1, testView.height - top)
            runGc(experiment, "View.refreshScreen($left, $top, $width, $height, 0x${nativeMode.toString(16)})", { "left=$left,top=$top,width=$width,height=$height" }, modeLabel, config = config, source = source) { epdBridge.refreshScreenRegionNative(testView, nativeMode, left, top, width, height) }
        } else {
            runGc(experiment, "View.refreshScreen(0x${nativeMode.toString(16)})", { "view=${testView.width}x${testView.height}" }, modeLabel, config = config, source = source) { epdBridge.refreshScreenNative(testView, nativeMode) }
        }
    }

    private fun runNativeRegionSequence(
        experiment: String,
        nativeMode: Int,
        sequenceName: String,
        interRequestDelayMs: Long,
        submissionCount: Int,
        regionDivisor: Int,
        alternateNativeMode: Int,
        config: ScrubConfig,
        source: String,
    ) {
        val allowedModes = setOf(0x2, 0x22, 0x42, 0x62)
        val allowedSequences = setOf("same", "abc", "aba", "overlap", "large-small", "small-large")
        val allowedDivisors = setOf(1, 2, 4, 8, 16, 32)
        val alternating = alternateNativeMode != Int.MIN_VALUE
        val allowedPair = setOf(nativeMode, alternateNativeMode) in setOf(setOf(0x2, 0x22), setOf(0x42, 0x62))
        if (nativeMode !in allowedModes || sequenceName !in allowedSequences || regionDivisor !in allowedDivisors || (alternating && !allowedPair)) {
            finishUnsupportedAutomation(experiment, "unsafe or unsupported native sequence parameters")
            return
        }
        val delayMs = interRequestDelayMs.coerceIn(0L, 2_000L)
        val count = submissionCount.coerceIn(1, 256)
        val generation = startDiagnostic(
            experiment,
            source,
            "sequence=$sequenceName; nativeMode=0x${nativeMode.toString(16)}; delayMs=$delayMs; " +
                "alternateNativeMode=${if (alternating) "0x${alternateNativeMode.toString(16)}" else "none"}; " +
                "submissions=$count; regionDivisor=$regionDivisor; quietMs=${config.diagnosticQuietHoldMs}; " +
                "hostRunId=$automationHostRunId",
        )
        afterPatternDraw = {
            handler.postDelayed({
                if (generation != diagnosticGeneration) return@postDelayed
                val viewWidth = testView.width
                val viewHeight = testView.height
                val unitHeight = (viewHeight / regionDivisor).coerceAtLeast(1)
                val smallWidth = (viewWidth / 4).coerceAtLeast(1)
                val smallHeight = (viewHeight / 4).coerceAtLeast(1)
                val regions = when (sequenceName) {
                    "same" -> listOf(NativeRegion("A", 0, 0, viewWidth, unitHeight))
                    "abc" -> {
                        val third = (viewHeight / 3).coerceAtLeast(1)
                        listOf(
                            NativeRegion("A", 0, 0, viewWidth, third),
                            NativeRegion("B", 0, third, viewWidth, third),
                            NativeRegion("C", 0, third * 2, viewWidth, viewHeight - third * 2),
                        )
                    }
                    "aba" -> {
                        val half = (viewHeight / 2).coerceAtLeast(1)
                        listOf(
                            NativeRegion("A", 0, 0, viewWidth, half),
                            NativeRegion("B", 0, half, viewWidth, viewHeight - half),
                            NativeRegion("A", 0, 0, viewWidth, half),
                        )
                    }
                    "overlap" -> listOf(
                        NativeRegion("A", 0, 0, viewWidth, (viewHeight * 2 / 3).coerceAtLeast(1)),
                        NativeRegion("B", 0, viewHeight / 3, viewWidth, (viewHeight * 2 / 3).coerceAtLeast(1)),
                    )
                    "large-small" -> listOf(
                        NativeRegion("LARGE", 0, 0, viewWidth, viewHeight),
                        NativeRegion("SMALL", (viewWidth - smallWidth) / 2, (viewHeight - smallHeight) / 2, smallWidth, smallHeight),
                    )
                    else -> listOf(
                        NativeRegion("SMALL", (viewWidth - smallWidth) / 2, (viewHeight - smallHeight) / 2, smallWidth, smallHeight),
                        NativeRegion("LARGE", 0, 0, viewWidth, viewHeight),
                    )
                }
                val runId = DiagnosticLog.currentRunId() ?: "unknown"
                var submitIndex = 0
                val submit = object : Runnable {
                    override fun run() {
                        if (generation != diagnosticGeneration) return
                        if (submitIndex >= count) {
                            DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN run=$runId durationMs=${config.diagnosticQuietHoldMs} framebuffer=solid-white")
                            handler.postDelayed({
                                if (generation != diagnosticGeneration) return@postDelayed
                                DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END run=$runId durationMs=${config.diagnosticQuietHoldMs}")
                                finishDiagnostic("native sequence complete; submissions=$count")
                            }, config.diagnosticQuietHoldMs)
                            return
                        }
                        val region = regions[submitIndex % regions.size]
                        val sequenceNumber = submitIndex + 1
                        val submitMode = if (alternating && submitIndex % 2 == 1) alternateNativeMode else nativeMode
                        val rect = "${region.left},${region.top},${region.width},${region.height}"
                        val startedAt = SystemClock.elapsedRealtimeNanos()
                        DiagnosticLog.event(
                            "I",
                            "SUBMIT",
                            "SUBMIT_BEGIN run=$runId seq=$sequenceNumber label=${region.label} rect=$rect " +
                                "mode=0x${submitMode.toString(16)} monotonicNs=$startedAt",
                        )
                        val result = epdBridge.refreshScreenRegionNative(
                            testView,
                            submitMode,
                            region.left,
                            region.top,
                            region.width,
                            region.height,
                        )
                        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
                        DiagnosticLog.event(
                            "I",
                            "SUBMIT",
                            "SUBMIT_RETURN run=$runId seq=$sequenceNumber elapsedMs=$elapsedMs result=${resultText(result)}",
                        )
                        submitIndex += 1
                        handler.postDelayed(this, delayMs)
                    }
                }
                activeSequence = submit
                submit.run()
            }, RenderSettleMs)
        }
        testView.showSolidWhite()
    }

    private fun runAutomationInventory(experiment: String, source: String, entries: List<String>) {
        startDiagnostic(experiment, source, "readOnly=true; count=${entries.size}")
        entries.forEach { DiagnosticLog.event("I", "INVENTORY", it) }
        finishDiagnostic("$experiment complete; count=${entries.size}")
    }

    private fun finishUnsupportedAutomation(experiment: String, reason: String) {
        startDiagnostic(experiment.ifBlank { "invalid-automation-request" }, "automation:$automationHostRunId", reason)
        DiagnosticLog.event("E", "AUTOMATION", "AUTOMATION_REJECTED hostRunId=$automationHostRunId reason=$reason")
        finishDiagnostic("automation rejected: $reason")
    }

    private fun launchOverlayScrub(
        source: String,
        returnAfter: Boolean,
        config: ScrubConfig = preferences.quickConfig(),
        useRealWait: Boolean = false,
        presentationMode: OverlayScrubController.PresentationMode = OverlayScrubController.PresentationMode.T0_OPAQUE,
        sequenceMode: OverlayScrubController.SequenceMode = OverlayScrubController.SequenceMode.SCRUB,
        backgroundUnderOverlay: Boolean = false,
        production: ScrubProfile? = null,
    ) {
        if (returnAfter) controls = View(this)
        if (!Settings.canDrawOverlays(this)) {
            if (automationMode) {
                finishUnsupportedAutomation("overlay-scrub", "draw-over-other-apps permission is not granted")
                return
            }
            if (production != null) {
                preferences.setPendingAction(PENDING_PRODUCTION_QUICK)
                preferences.setPendingProductionProfile(production)
                overlayPermissionRequestActive = true
            } else {
                preferences.setPendingAction(if (returnAfter) PENDING_OVERLAY_QUICK else PENDING_OVERLAY_MENU)
            }
            DiagnosticLog.event("I", "OVERLAY", "OVERLAY_PERMISSION_REQUIRED source=$source")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val experiment = when {
            sequenceMode == OverlayScrubController.SequenceMode.GHOST_SEED -> "overlay-seed-hold"
            useRealWait -> "overlay-real-wait-scrub"
            else -> "overlay-${presentationMode.wireValue}-scrub"
        }
        val runId = DiagnosticLog.begin(this, experiment, source, "${config.summary()}; presentation=${presentationMode.wireValue}")
        DiagnosticLog.event("I", "OVERLAY", "OVERLAY_START run=$runId realWait=$useRealWait presentation=${presentationMode.wireValue} sequence=$sequenceMode")
        val finalWhiteHoldMs = when {
            automationMode -> config.diagnosticQuietHoldMs
            returnAfter -> config.finalSettleMs
            else -> config.diagnosticQuietHoldMs
        }
        overlayController = OverlayScrubController(
            this,
            config,
            finalWhiteHoldMs,
            completionWait = if (useRealWait) surfaceFlingerBridge::waitForAllUpdates else null,
            presentationMode = presentationMode,
            sequenceMode = sequenceMode,
            seedPhaseMs = cameraPreconditionMs,
            onAdded = if (backgroundUnderOverlay) ({
                val moved = moveTaskToBack(true)
                DiagnosticLog.event("I", "OVERLAY", "UNDERLAY_BACKGROUND_REQUEST moved=$moved")
            }) else null,
        ) { result ->
            DiagnosticLog.finish(this, result)
            overlayController = null
            if (production != null) {
                val durationMs = if (productionStartedAtNs == 0L) 0L else
                    (SystemClock.elapsedRealtimeNanos() - productionStartedAtNs) / NANOS_PER_MILLISECOND
                DiagnosticLog.event(
                    "I",
                    "PRODUCTION",
                    "PRODUCTION_COMPLETE profileId=${production.stableId} completionElapsedMs=${SystemClock.elapsedRealtime()} " +
                        "totalMs=$durationMs cleanup=true result=$result",
                )
                releaseProductionGate()
                finishAndRemoveTask()
                return@OverlayScrubController
            }
            if (automationMode) {
                testView.showSolidWhite()
                DiagnosticLog.event("I", "AUTOMATION", "AUTOMATION_END hostRunId=$automationHostRunId result=$result")
                handler.postDelayed({ finishAndRemoveTask() }, AutomationExitDelayMs)
                return@OverlayScrubController
            }
            if (returnAfter) {
                finishAndRemoveTask()
            } else {
                testView.show(InkTestView.Pattern.WHITE)
                controls.visibility = View.VISIBLE
                setStatus(result)
            }
        }.also { it.start() }
    }

    private fun launchAccessibilityWhiteRefresh(source: String) {
        controls = View(this)
        val service = AccessibilityRefreshService.active()
        if (service == null) {
            preferences.setPendingAction(PENDING_ACCESSIBILITY)
            DiagnosticLog.event("I", "ACCESSIBILITY", "ACCESSIBILITY_PERMISSION_REQUIRED source=$source")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        service.startLogicalWhiteRefresh(preferences.quickConfig(), source)
        finishAndRemoveTask()
    }

    private fun runConfiguredScrub(experiment: String, source: String, suppliedConfig: ScrubConfig, returnAfter: Boolean) {
        val config = suppliedConfig.sanitized()
        val generation = startDiagnostic(experiment, source, config.summary())
        var repeatIndex = 0
        var bandIndex = 0
        var black = true
        var duplicateIndex = 0
        val advance = object : Runnable {
            override fun run() {
                if (generation != diagnosticGeneration) return
                if (repeatIndex >= config.repeatCount) {
                    val finalHoldMs = if (returnAfter) config.finalSettleMs else config.diagnosticQuietHoldMs
                    if (returnAfter) {
                        testView.show(InkTestView.Pattern.WHITE)
                        testView.setDebugLabel("final settle ${config.finalSettleMs} ms")
                    } else {
                        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=$finalHoldMs experiment=$experiment")
                    }
                    handler.postDelayed({
                        if (generation != diagnosticGeneration) return@postDelayed
                        if (!returnAfter) DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=$finalHoldMs experiment=$experiment")
                        finishDiagnostic("$experiment complete")
                        if (returnAfter) finishAndRemoveTask()
                    }, finalHoldMs)
                    return
                }
                val drawIndex = if (config.bottomToTop) config.bandCount - 1 - bandIndex else bandIndex
                val phase = if (black) "black" else "white"
                awaitingPatternDraw = true
                val lastDiagnosticWhite = !returnAfter && !black &&
                    repeatIndex == config.repeatCount - 1 && bandIndex == config.bandCount - 1
                val label = if (lastDiagnosticWhite) null else "$experiment: ${drawIndex + 1}/${config.bandCount} $phase"
                testView.showScrubStripe(drawIndex, config.bandCount, black, label)
                DiagnosticLog.event("I", "REQUEST", "STRIP band=${drawIndex + 1}/${config.bandCount} phase=$phase repeat=${repeatIndex + 1}/${config.repeatCount} duplicate=${duplicateIndex + 1}/${config.duplicateSubmissions}")
                val dwell = if (black) config.blackDwellMs else config.whiteDwellMs
                duplicateIndex += 1
                if (duplicateIndex >= config.duplicateSubmissions) {
                    duplicateIndex = 0
                    if (black) {
                        black = false
                    } else {
                        black = true
                        bandIndex += 1
                        if (bandIndex >= config.bandCount) {
                            bandIndex = 0
                            repeatIndex += 1
                        }
                    }
                }
                handler.postDelayed(this, dwell + config.safetyDelayMs)
            }
        }
        activeSequence = advance
        advance.run()
    }

    private fun runWholeBlackWhite(config: ScrubConfig = readConfig(), source: String = "menu") {
        val generation = startDiagnostic("whole-black-white", source, config.summary())
        testView.show(InkTestView.Pattern.BLACK)
        testView.setDebugLabel("whole black ${config.blackDwellMs} ms")
        DiagnosticLog.event("I", "REQUEST", "WHOLE_BLACK")
        handler.postDelayed({
            if (generation != diagnosticGeneration) return@postDelayed
            testView.showSolidWhite()
            DiagnosticLog.event("I", "REQUEST", "WHOLE_WHITE")
            DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=${config.diagnosticQuietHoldMs} experiment=whole-black-white")
            handler.postDelayed({
                if (generation == diagnosticGeneration) {
                    DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=${config.diagnosticQuietHoldMs} experiment=whole-black-white")
                    finishDiagnostic("whole black → white complete")
                }
            }, config.whiteDwellMs + config.diagnosticQuietHoldMs)
        }, config.blackDwellMs)
    }

    private fun runCheckerSequence(config: ScrubConfig = readConfig(), source: String = "menu") {
        val generation = startDiagnostic("checker-inverse-white", source, config.summary())
        val checkerDwell = config.blackDwellMs
        testView.show(InkTestView.Pattern.CHECKERBOARD)
        testView.setDebugLabel("checker ${checkerDwell} ms")
        DiagnosticLog.event("I", "REQUEST", "CHECKER")
        handler.postDelayed({
            if (generation != diagnosticGeneration) return@postDelayed
            testView.show(InkTestView.Pattern.INVERTED_CHECKERBOARD)
            testView.setDebugLabel("inverse checker ${checkerDwell} ms")
            DiagnosticLog.event("I", "REQUEST", "CHECKER_INVERSE")
            handler.postDelayed({
                if (generation != diagnosticGeneration) return@postDelayed
                testView.showSolidWhite()
                DiagnosticLog.event("I", "REQUEST", "CHECKER_WHITE")
                DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=${config.diagnosticQuietHoldMs} experiment=checker-inverse-white")
                handler.postDelayed({
                    if (generation == diagnosticGeneration) {
                        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=${config.diagnosticQuietHoldMs} experiment=checker-inverse-white")
                        finishDiagnostic("checker → inverse → white complete")
                    }
                }, config.diagnosticQuietHoldMs)
            }, checkerDwell)
        }, checkerDwell)
    }

    private fun runTimingSweep() {
        val config = readConfig()
        val bandCounts = listOf(2, 4, 8, 16, 32)
        val generation = startDiagnostic("band-count-matrix", "menu", "bands=${bandCounts.joinToString()}; ${config.summary()}")
        var specIndex = 0
        fun nextSpec() {
            if (generation != diagnosticGeneration) return
            if (specIndex == bandCounts.size) {
                finishDiagnostic("band-count matrix complete")
                return
            }
            val bandCount = bandCounts[specIndex++]
            var bandIndex = 0
            var black = true
            val advance = object : Runnable {
                override fun run() {
                    if (generation != diagnosticGeneration) return
                    if (bandIndex == bandCount) {
                        DiagnosticLog.event("I", "SWEEP", "SPEC_COMPLETE bands=$bandCount blackMs=${config.blackDwellMs} whiteMs=${config.whiteDwellMs}")
                        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=${config.diagnosticQuietHoldMs} bands=$bandCount")
                        handler.postDelayed({
                            if (generation != diagnosticGeneration) return@postDelayed
                            DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=${config.diagnosticQuietHoldMs} bands=$bandCount")
                            nextSpec()
                        }, config.diagnosticQuietHoldMs)
                        return
                    }
                    val phase = if (black) "black" else "white"
                    awaitingPatternDraw = true
                    val drawIndex = if (config.bottomToTop) bandCount - 1 - bandIndex else bandIndex
                    val lastWhite = !black && bandIndex == bandCount - 1
                    val label = if (lastWhite) null else "matrix $specIndex/${bandCounts.size}: $bandCount bands — ${drawIndex + 1}/$bandCount $phase"
                    testView.showScrubStripe(drawIndex, bandCount, black, label)
                    DiagnosticLog.event("I", "SWEEP", "REQUEST bands=$bandCount blackMs=${config.blackDwellMs} whiteMs=${config.whiteDwellMs} band=${drawIndex + 1}/$bandCount phase=$phase")
                    if (black) black = false else {
                        black = true
                        bandIndex += 1
                    }
                    val dwellMs = if (phase == "black") config.blackDwellMs else config.whiteDwellMs
                    handler.postDelayed(this, dwellMs + config.safetyDelayMs)
                }
            }
            activeSequence = advance
            advance.run()
        }
        nextSpec()
    }

    private fun runGc(
        experiment: String,
        signature: String,
        requestedRectangle: () -> String,
        updateMode: String = "GC",
        followupInvalidate: Boolean = false,
        config: ScrubConfig = readConfig(),
        source: String = "menu",
        action: () -> BooxEpdBridge.ApiResult,
    ) {
        val generation = startDiagnostic(experiment, source, "signature=$signature; quietMs=${config.diagnosticQuietHoldMs}; hostRunId=$automationHostRunId")
        afterPatternDraw = {
            handler.postDelayed({
                if (generation != diagnosticGeneration) return@postDelayed
                val runId = DiagnosticLog.currentRunId() ?: "unknown"
                val geometry = diagnosticGeometry(signature, requestedRectangle(), updateMode)
                DiagnosticLog.event("I", "CALL", "CALL_BEGIN run=$runId $geometry")
                val startedAt = SystemClock.elapsedRealtimeNanos()
                val apiResult = action()
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
                val result = resultText(apiResult)
                DiagnosticLog.event("I", "CALL", "CALL_RETURN run=$runId elapsedMs=$elapsedMs result=$result")
                val beginQuietHold = {
                    DiagnosticLog.event("I", "ONYX", "$experiment result=$result")
                    DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN run=$runId durationMs=${config.diagnosticQuietHoldMs} framebuffer=solid-white")
                    handler.postDelayed({
                        if (generation != diagnosticGeneration) return@postDelayed
                        DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END run=$runId durationMs=${config.diagnosticQuietHoldMs}")
                        finishDiagnostic(result)
                    }, config.diagnosticQuietHoldMs)
                }
                if (followupInvalidate) {
                    val followupStartedAt = SystemClock.elapsedRealtimeNanos()
                    DiagnosticLog.event("I", "CALL", "FOLLOWUP_BEGIN run=$runId signature=View.invalidate()")
                    afterPatternDraw = {
                        DiagnosticLog.event("I", "CALL", "FOLLOWUP_DRAW run=$runId framebuffer=solid-white")
                        beginQuietHold()
                    }
                    testView.invalidate()
                    val followupElapsedMs = (SystemClock.elapsedRealtimeNanos() - followupStartedAt) / NANOS_PER_MILLISECOND
                    DiagnosticLog.event("I", "CALL", "FOLLOWUP_RETURN run=$runId elapsedMs=$followupElapsedMs")
                } else {
                    beginQuietHold()
                }
            }, RenderSettleMs)
        }
        testView.showSolidWhite()
    }

    private fun runExperimentalGc(
        experiment: String,
        signature: String,
        requestedRectangle: () -> String,
        updateMode: String = "GC",
        followupInvalidate: Boolean = false,
        action: () -> BooxEpdBridge.ApiResult,
    ) {
        if (!preferences.experimentalApisEnabled()) {
            setStatus("Enable newly discovered public API tests before running $experiment.")
            return
        }
        runGc(experiment, signature, requestedRectangle, updateMode, followupInvalidate, action = action)
    }

    private fun runNormalInvalidateControl() = runExperimentalGc(
        "normal-invalidate-control",
        "View.invalidate()",
        { "Android-selected dirty region for view=${testView.width}x${testView.height}" },
        "normal Android",
        true,
    ) {
        BooxEpdBridge.ApiResult.Success("No applyGCOnce; proceeding to control View.invalidate()")
    }

    private fun runRepaintEverythingGc() = runGc(
        "gc-repaint-everything",
        "EpdController.repaintEveryThing(UpdateMode.GC)",
        { "API-defined global/full target" },
        action = epdBridge::repaintEverythingGc,
    )

    private fun runExplicitGcRect() = runGc(
        "gc-explicit-app-rect",
        "EpdController.invalidate(View, 0, 0, width, height, UpdateMode.GC)",
        { "0,0,${testView.width},${testView.height}" },
    ) { epdBridge.invalidateGcRect(testView) }

    private fun runTrueGlobalGc(config: ScrubConfig, source: String) = runGc(
        experiment = "true-global-gc",
        signature = "SurfaceFlinger.transact(0xff0023, 0x62)",
        requestedRectangle = { "firmware global/full physical target" },
        updateMode = "UI_GC_MODE 0x62",
        config = config,
        source = source,
        action = surfaceFlingerBridge::trueGlobalGc,
    )

    private fun runTrueGlobalGcWithWait(config: ScrubConfig, source: String) {
        val safe = config.sanitized()
        val commitSettleMs = safe.blackDwellMs
        val generation = startDiagnostic(
            "true-global-gc-wait",
            source,
            "global=SurfaceFlinger 0xff0023 mode 0x62; commitSettleMs=$commitSettleMs; wait=SurfaceFlinger 0xff0017",
        )
        afterPatternDraw = {
            handler.postDelayed({
                if (generation != diagnosticGeneration) return@postDelayed
                val runId = DiagnosticLog.currentRunId() ?: "unknown"
                val geometry = diagnosticGeometry(
                    "SurfaceFlinger.transact(0xff0023, 0x62)",
                    "firmware global/full physical target",
                    "UI_GC_MODE 0x62",
                )
                DiagnosticLog.event("I", "CALL", "CALL_BEGIN run=$runId $geometry")
                val globalStartedAt = SystemClock.elapsedRealtimeNanos()
                val globalResult = surfaceFlingerBridge.trueGlobalGc()
                val globalDurationMs = (SystemClock.elapsedRealtimeNanos() - globalStartedAt) / NANOS_PER_MILLISECOND
                val globalResultText = resultText(globalResult)
                DiagnosticLog.event(
                    "I",
                    "CALL",
                    "CALL_RETURN run=$runId elapsedMs=$globalDurationMs result=$globalResultText",
                )
                DiagnosticLog.event(
                    "I",
                    "GLOBAL_GC_WAIT",
                    "COMMIT_SETTLE code=0xff0023 durationMs=$commitSettleMs purpose=allow-SF-HWC-submission-before-wait",
                )
                handler.postDelayed({
                    if (generation != diagnosticGeneration) return@postDelayed
                    val waitStartedAt = SystemClock.elapsedRealtimeNanos()
                    val watchdog = Runnable {
                        if (generation == diagnosticGeneration) {
                            DiagnosticLog.event(
                                "W",
                                "GLOBAL_GC_WAIT",
                                "WAIT_TIMEOUT code=0xff0017 timeoutMs=$RealWaitTimeoutMs",
                            )
                            cancelSequence()
                            finishDiagnostic("global GC completion wait timed out; stopped")
                        }
                    }
                    handler.postDelayed(watchdog, RealWaitTimeoutMs)
                    DiagnosticLog.event("I", "GLOBAL_GC_WAIT", "WAIT_BEGIN code=0xff0017")
                    Thread {
                        val waitResult = surfaceFlingerBridge.waitForAllUpdates()
                        val waitDurationMs =
                            (SystemClock.elapsedRealtimeNanos() - waitStartedAt) / NANOS_PER_MILLISECOND
                        handler.post {
                            handler.removeCallbacks(watchdog)
                            val waitResultText = resultText(waitResult)
                            DiagnosticLog.event(
                                "I",
                                "GLOBAL_GC_WAIT",
                                "WAIT_RETURN code=0xff0017 durationMs=$waitDurationMs result=$waitResultText",
                            )
                            if (generation != diagnosticGeneration) return@post
                            DiagnosticLog.event(
                                "I",
                                "QUIET",
                                "QUIET_HOLD_BEGIN run=$runId durationMs=${safe.diagnosticQuietHoldMs} framebuffer=solid-white",
                            )
                            handler.postDelayed({
                                if (generation != diagnosticGeneration) return@postDelayed
                                DiagnosticLog.event(
                                    "I",
                                    "QUIET",
                                    "QUIET_HOLD_END run=$runId durationMs=${safe.diagnosticQuietHoldMs}",
                                )
                                finishDiagnostic("global=$globalResultText; wait=$waitResultText")
                            }, safe.diagnosticQuietHoldMs)
                        }
                    }.apply {
                        name = "PalmaGlobalGcWait"
                        isDaemon = true
                        start()
                    }
                }, commitSettleMs)
            }, RenderSettleMs)
        }
        testView.showSolidWhite()
    }

    private fun runWaitScrub(target: BooxEpdBridge.WaitTarget, config: ScrubConfig, source: String = "menu") {
        val safe = config.sanitized()
        val generation = startDiagnostic("wait-${target.name.lowercase()}", source, safe.summary())
        var bandIndex = 0
        var black = true
        fun nextPhase() {
            if (generation != diagnosticGeneration) return
            if (bandIndex == safe.bandCount) {
                DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_BEGIN durationMs=${safe.diagnosticQuietHoldMs} experiment=wait-${target.name.lowercase()}")
                handler.postDelayed({
                    if (generation != diagnosticGeneration) return@postDelayed
                    DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=${safe.diagnosticQuietHoldMs} experiment=wait-${target.name.lowercase()}")
                    finishDiagnostic("${target.label} scrub complete")
                }, safe.diagnosticQuietHoldMs)
                return
            }
            val drawIndex = if (safe.bottomToTop) safe.bandCount - 1 - bandIndex else bandIndex
            val phase = if (black) "black" else "white"
            awaitingPatternDraw = true
            val lastWhite = !black && bandIndex == safe.bandCount - 1
            val watchdog = Runnable {
                if (generation == diagnosticGeneration) {
                    DiagnosticLog.event("W", "WAIT", "WAIT_TIMEOUT target=${target.name} band=${drawIndex + 1} timeoutMs=$WaitTimeoutMs")
                    finishDiagnostic("${target.label} timed out; stopped")
                }
            }
            handler.postDelayed(watchdog, WaitTimeoutMs)
            afterPatternDraw = {
                val startedAt = SystemClock.elapsedRealtimeNanos()
                DiagnosticLog.event("I", "WAIT", "WAIT_BEGIN target=${target.name} band=${drawIndex + 1} phase=$phase")
                Thread {
                    val result = epdBridge.waitForUpdateFinished(target)
                    val durationMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
                    handler.post {
                        handler.removeCallbacks(watchdog)
                        DiagnosticLog.event("I", "WAIT", "WAIT_RETURN target=${target.name} band=${drawIndex + 1} phase=$phase durationMs=$durationMs result=${resultText(result)}")
                        if (generation != diagnosticGeneration) return@post
                        if (black) black = false else {
                            black = true
                            bandIndex += 1
                        }
                        handler.postDelayed(::nextPhase, safe.safetyDelayMs)
                    }
                }.apply {
                    name = "PalmaEpdWait-${target.name}"
                    isDaemon = true
                    start()
                }
            }
            val label = if (lastWhite) null else "wait ${target.name.lowercase()}: ${drawIndex + 1}/${safe.bandCount} $phase"
            testView.showScrubStripe(drawIndex, safe.bandCount, black, label)
            DiagnosticLog.event("I", "REQUEST", "WAIT_STRIP target=${target.name} band=${drawIndex + 1}/${safe.bandCount} phase=$phase")
        }
        nextPhase()
    }

    private fun runRealWaitScrub(config: ScrubConfig, source: String = "menu") {
        val safe = config.sanitized()
        val generation = startDiagnostic(
            "real-wait-scrub",
            source,
            "${safe.summary()}; wait=SurfaceFlinger 0xff0017; dwell=post-draw commit settle",
        )
        var repeatIndex = 0
        var bandIndex = 0
        var black = true
        var unavailableCount = 0

        fun nextPhase() {
            if (generation != diagnosticGeneration) return
            if (repeatIndex >= safe.repeatCount) {
                DiagnosticLog.event(
                    "I",
                    "QUIET",
                    "QUIET_HOLD_BEGIN durationMs=${safe.diagnosticQuietHoldMs} experiment=real-wait-scrub unavailable=$unavailableCount",
                )
                handler.postDelayed({
                    if (generation != diagnosticGeneration) return@postDelayed
                    DiagnosticLog.event("I", "QUIET", "QUIET_HOLD_END durationMs=${safe.diagnosticQuietHoldMs} experiment=real-wait-scrub")
                    finishDiagnostic("real completion wait scrub complete; unavailable=$unavailableCount")
                }, safe.diagnosticQuietHoldMs)
                return
            }

            val drawIndex = if (safe.bottomToTop) safe.bandCount - 1 - bandIndex else bandIndex
            val phase = if (black) "black" else "white"
            val commitSettleMs = if (black) safe.blackDwellMs else safe.whiteDwellMs
            val lastWhite = !black && repeatIndex == safe.repeatCount - 1 && bandIndex == safe.bandCount - 1
            awaitingPatternDraw = true
            afterPatternDraw = {
                DiagnosticLog.event(
                    "I",
                    "REAL_WAIT",
                    "COMMIT_SETTLE band=${drawIndex + 1}/${safe.bandCount} phase=$phase durationMs=$commitSettleMs",
                )
                handler.postDelayed({
                    if (generation != diagnosticGeneration) return@postDelayed
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    val watchdog = Runnable {
                        if (generation == diagnosticGeneration) {
                            DiagnosticLog.event("W", "REAL_WAIT", "WAIT_TIMEOUT band=${drawIndex + 1} phase=$phase timeoutMs=$RealWaitTimeoutMs")
                            cancelSequence()
                            finishDiagnostic("real completion wait timed out; stopped")
                        }
                    }
                    handler.postDelayed(watchdog, RealWaitTimeoutMs)
                    DiagnosticLog.event(
                        "I",
                        "REAL_WAIT",
                        "WAIT_BEGIN code=0xff0017 band=${drawIndex + 1}/${safe.bandCount} phase=$phase",
                    )
                    Thread {
                        val result = surfaceFlingerBridge.waitForAllUpdates()
                        val durationMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
                        handler.post {
                            handler.removeCallbacks(watchdog)
                            if (result is BooxEpdBridge.ApiResult.Unavailable) unavailableCount += 1
                            DiagnosticLog.event(
                                "I",
                                "REAL_WAIT",
                                "WAIT_RETURN code=0xff0017 band=${drawIndex + 1}/${safe.bandCount} phase=$phase " +
                                    "durationMs=$durationMs result=${resultText(result)}",
                            )
                            if (generation != diagnosticGeneration) return@post
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
                            handler.postDelayed(::nextPhase, safe.safetyDelayMs)
                        }
                    }.apply {
                        name = "PalmaRealEpdWait"
                        isDaemon = true
                        start()
                    }
                }, commitSettleMs)
            }
            val label = if (lastWhite) null else "real wait: ${drawIndex + 1}/${safe.bandCount} $phase"
            testView.showScrubStripe(drawIndex, safe.bandCount, black, label)
            DiagnosticLog.event(
                "I",
                "REQUEST",
                "REAL_WAIT_STRIP band=${drawIndex + 1}/${safe.bandCount} phase=$phase repeat=${repeatIndex + 1}/${safe.repeatCount}",
            )
        }

        nextPhase()
    }

    private fun logApiInventory() {
        val methods = epdBridge.relatedApiMethods()
        DiagnosticLog.event("I", "API", "API_INVENTORY_BEGIN count=${methods.size}")
        methods.forEach { DiagnosticLog.event("I", "API", it) }
        DiagnosticLog.event("I", "API", "API_INVENTORY_END")
        setStatus("Logged ${methods.size} BOOX methods. See Live Log.")
    }

    private fun logFirmwareMappingInventory() {
        val mappings = epdBridge.firmwareMappingInventory(testView)
        DiagnosticLog.event("I", "MAPPING", "FIRMWARE_MAPPING_BEGIN count=${mappings.size} readOnly=true")
        mappings.forEach { DiagnosticLog.event("I", "MAPPING", it) }
        DiagnosticLog.event("I", "MAPPING", "FIRMWARE_MAPPING_END")
        setStatus("Logged ${mappings.size} read-only firmware mappings. See Live Log.")
    }

    private fun copyFullReport() {
        val report = DiagnosticLog.fullReport(this, epdBridge.relatedApiMethods(), preferences.quickConfig())
        DiagnosticLog.copyText(this, "Palma full diagnostic report", report)
        setStatus("Copied full diagnostic report to clipboard.")
    }

    private fun diagnosticGeometry(signature: String, requestedRectangle: String, updateMode: String): String {
        val root = testView.rootView
        val displayMode = display?.mode
        val physical = if (displayMode == null) "unavailable" else "${displayMode.physicalWidth}x${displayMode.physicalHeight}"
        val insetsText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            if (insets == null) "unavailable" else "left=${insets.left},top=${insets.top},right=${insets.right},bottom=${insets.bottom}"
        } else {
            "API<30"
        }
        return "signature=$signature root=${root.width}x${root.height} view=${testView.width}x${testView.height} " +
            "physicalDisplay=$physical requestedRect=$requestedRectangle updateMode=$updateMode systemInsets=[$insetsText] " +
            "epdMetrics=${resultText(epdBridge.panelSize())}"
    }

    private fun startDiagnostic(experiment: String, source: String, parameters: String): Int {
        cancelSequence()
        controls.visibility = View.GONE
        DiagnosticLog.begin(this, experiment, source, parameters)
        return diagnosticGeneration
    }

    private fun finishDiagnostic(message: String) {
        activeSequence = null
        awaitingPatternDraw = false
        afterPatternDraw = null
        if (cameraMode) {
            testView.showSolidWhite()
        } else {
            testView.show(InkTestView.Pattern.WHITE)
            testView.setDebugLabel("$message — controls restored")
        }
        DiagnosticLog.finish(this, message)
        if (automationMode) {
            DiagnosticLog.event("I", "AUTOMATION", "AUTOMATION_END hostRunId=$automationHostRunId result=$message")
            handler.postDelayed({ finishAndRemoveTask() }, AutomationExitDelayMs)
            return
        }
        controls.visibility = View.VISIBLE
        setStatus(message)
    }

    private fun finishAutomationPreservingFrame(message: String) {
        activeSequence = null
        awaitingPatternDraw = false
        afterPatternDraw = null
        DiagnosticLog.finish(this, message)
        DiagnosticLog.event("I", "AUTOMATION", "AUTOMATION_END hostRunId=$automationHostRunId result=$message")
        handler.postDelayed({ finishAndRemoveTask() }, AutomationExitDelayMs)
    }

    private fun stopDiagnostic() {
        cancelSequence()
        testView.show(InkTestView.Pattern.WHITE)
        testView.setDebugLabel("diagnostic stopped")
        controls.visibility = View.VISIBLE
        DiagnosticLog.finish(this, "stopped by user")
        setStatus("Stopped. No further updates were scheduled.")
    }

    private fun cancelSequence() {
        activeSequence?.let(handler::removeCallbacks)
        activeSequence = null
        awaitingPatternDraw = false
        afterPatternDraw = null
        diagnosticGeneration += 1
    }

    private fun resultText(result: BooxEpdBridge.ApiResult): String = when (result) {
        is BooxEpdBridge.ApiResult.Success -> result.message
        is BooxEpdBridge.ApiResult.Unavailable -> "BOOX API unavailable: ${result.reason}"
    }

    private fun setStatus(message: String) {
        DiagnosticLog.event("I", "STATUS", message)
        if (::status.isInitialized) status.text = message
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun elapsedMs(atNs: Long = SystemClock.elapsedRealtimeNanos()): Long = atNs / NANOS_PER_MILLISECOND

    private fun isAlias(aliasName: String): Boolean = intent.component?.className == "$packageName.$aliasName"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ACTION_DIAGNOSTICS = "dev.palma.screenscrub.action.DIAGNOSTICS"
        const val ACTION_QUICK_SCRUB = "dev.palma.screenscrub.action.QUICK_SCRUB"
        const val ACTION_QUICK_OVERLAY = "dev.palma.screenscrub.action.QUICK_OVERLAY"
        const val ACTION_QUICK_16 = "dev.palma.screenscrub.action.QUICK_16"
        const val ACTION_QUICK_ACCESSIBILITY = "dev.palma.screenscrub.action.QUICK_ACCESSIBILITY"
        const val ACTION_AUTOMATION = "dev.palma.screenscrub.action.AUTOMATION"
        const val EXTRA_EXPERIMENT = "experiment"
        const val EXTRA_HOST_RUN_ID = "hostRunId"
        const val EXTRA_CONFIRM_SAFE = "confirmSafe"
        const val EXTRA_BANDS = "bands"
        const val EXTRA_BLACK_MS = "blackMs"
        const val EXTRA_WHITE_MS = "whiteMs"
        const val EXTRA_SAFETY_MS = "safetyMs"
        const val EXTRA_SETTLE_MS = "settleMs"
        const val EXTRA_QUIET_MS = "quietMs"
        const val EXTRA_REPEATS = "repeats"
        const val EXTRA_DUPLICATES = "duplicates"
        const val EXTRA_BOTTOM_TO_TOP = "bottomToTop"
        const val EXTRA_OVERLAY_PRESENTATION = "overlayPresentation"
        const val EXTRA_NATIVE_MODE = "nativeMode"
        const val EXTRA_NATIVE_LEFT = "nativeLeft"
        const val EXTRA_NATIVE_TOP = "nativeTop"
        const val EXTRA_NATIVE_WIDTH = "nativeWidth"
        const val EXTRA_NATIVE_HEIGHT = "nativeHeight"
        const val EXTRA_NATIVE_SEQUENCE = "nativeSequence"
        const val EXTRA_SEQUENCE_DELAY_MS = "sequenceDelayMs"
        const val EXTRA_SEQUENCE_COUNT = "sequenceCount"
        const val EXTRA_REGION_DIVISOR = "regionDivisor"
        const val EXTRA_ALTERNATE_NATIVE_MODE = "alternateNativeMode"
        const val EXTRA_CAMERA_MODE = "cameraMode"
        const val EXTRA_CAMERA_INDEX = "cameraIndex"
        const val EXTRA_CAMERA_TOTAL = "cameraTotal"
        const val EXTRA_CAMERA_PROFILE = "cameraProfile"
        const val EXTRA_CAMERA_DETAIL = "cameraDetail"
        const val EXTRA_CAMERA_TITLE = "cameraTitle"
        const val EXTRA_CAMERA_SLATE_MS = "cameraSlateMs"
        const val EXTRA_CAMERA_PRECONDITION_MS = "cameraPreconditionMs"
        const val EXTRA_CAMERA_BASELINE_MS = "cameraBaselineMs"
        const val DIAGNOSTICS_ALIAS = "DiagnosticsAlias"
        const val QUICK_SCRUB_ALIAS = "QuickScrubAlias"
        const val QUICK_OVERLAY_ALIAS = "QuickOverlayAlias"
        const val QUICK_16_ALIAS = "Quick16Alias"
        const val QUICK_ACCESSIBILITY_ALIAS = "QuickAccessibilityAlias"
        const val AUTOMATION_ALIAS = "AutomationAlias"
        const val PENDING_OVERLAY_QUICK = "overlay-quick"
        const val PENDING_OVERLAY_MENU = "overlay-menu"
        const val PENDING_PRODUCTION_QUICK = "production-quick"
        const val PENDING_ACCESSIBILITY = "accessibility"
        const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = FrameLayout.LayoutParams.WRAP_CONTENT
        const val RenderSettleMs = 180L
        const val WaitTimeoutMs = 2_500L
        const val RealWaitTimeoutMs = 5_000L
        const val AutomationExitDelayMs = 100L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    private data class NativeRegion(
        val label: String,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )
}
