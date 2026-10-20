package com.petertroll.quiethome

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.petertroll.quiethome.databinding.ActivityLauncherBinding
import com.petertroll.quiethome.domain.AppCatalog
import com.petertroll.quiethome.domain.AppEntry
import com.petertroll.quiethome.domain.AppFilter
import com.petertroll.quiethome.domain.AppSort
import com.petertroll.quiethome.domain.AppTheme
import com.petertroll.quiethome.domain.ActivityKind
import com.petertroll.quiethome.domain.PlannerPlan
import com.petertroll.quiethome.domain.DisplayApp
import com.petertroll.quiethome.domain.GestureNudgePolicy
import com.petertroll.quiethome.domain.HomeSections
import com.petertroll.quiethome.domain.HomeDateFormatter
import com.petertroll.quiethome.domain.LauncherPreferences
import com.petertroll.quiethome.domain.LauncherGestureAction
import com.petertroll.quiethome.domain.LauncherGesturePolicy
import com.petertroll.quiethome.domain.IntentApp
import com.petertroll.quiethome.domain.CloudIntentPreflight
import com.petertroll.quiethome.domain.IntentInputPolicy
import com.petertroll.quiethome.domain.IntentParseResult
import com.petertroll.quiethome.domain.LocalIntentParser
import com.petertroll.quiethome.domain.LocalTasks
import com.petertroll.quiethome.domain.PlanAction
import com.petertroll.quiethome.domain.PlanValidator
import com.petertroll.quiethome.domain.WeatherSnapshot
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.Executors

class LauncherActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherBinding
    private lateinit var store: PreferencesStore
    private lateinit var repository: AndroidAppRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var appAdapter: AppListAdapter
    private lateinit var gestureDetector: GestureDetector
    private var pendingGestureAction: LauncherGestureAction? = null
    private var gestureStartRawX = 0f
    private var gestureStartRawY = 0f
    private var gestureStartedInHome = false
    private var gestureStartedInProtectedRegion = false
    private var preferences = LauncherPreferences()
    private var entries = emptyList<AppEntry>()
    private var catalogLoaded = false
    private var appFilter = AppFilter.ALL
    private var torchCameraId: String? = null
    private var torchEnabled = false
    private var catalogGeneration = 0
    private var receiverRegistered = false
    private val intentRequestGate = ActiveRequestGate()

    private var weatherSnapshot: WeatherSnapshot? = null
    private var started = false
    private lateinit var calendarObserver: CalendarChangeObserver
    private var lastPlannerTrigger = 0L
    private val catalogExecutor = Executors.newSingleThreadExecutor()
    private val contextExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val torchPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            toggleTorch()
        } else {
            snackbar(R.string.camera_permission_needed)
                .setAction(R.string.open_app_settings) { openAppSettings() }
                .show()
        }
    }
    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            binding.clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now)
            binding.date.text = HomeDateFormatter.format(LocalDate.now())
            renderHabit()
            mainHandler.postDelayed(this, CLOCK_REFRESH_MS)
        }
    }
    private val plannerTick = object : Runnable {
        override fun run() {
            triggerPlanner()
            mainHandler.postDelayed(this, PLANNER_REFRESH_MS)
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCatalog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        store = PreferencesStore(this)
        preferences = store.load()
        applyTheme(preferences.theme)
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scaleText(binding.root, preferences.textScale)
        ViewCompat.setAccessibilityHeading(binding.clock, true)
        ViewCompat.setAccessibilityHeading(binding.allAppsHeading, true)
        binding.clock.contentDescription = getString(R.string.open_clock)
        repository = AndroidAppRepository(this)
        weatherRepository = WeatherRepository(this)
        calendarObserver = CalendarChangeObserver(this) { triggerPlanner(calendarBypass = true) }
        appAdapter = AppListAdapter()
        binding.appList.adapter = appAdapter

        binding.appsButton.setOnClickListener { showDrawer(requestKeyboard = true) }
        binding.homeButton.setOnClickListener { showHome(clearSearch = true) }
        binding.clock.setOnClickListener { openClock() }
        binding.habitButton.setOnClickListener { startActivity(Intent(this, AssistantActivity::class.java)) }
        binding.habitButton.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        binding.weatherText.setOnClickListener { showWeatherDetails() }
        binding.plannerHeadline.setOnClickListener { startActivity(Intent(this, AssistantActivity::class.java)) }
        binding.plannerDigest.setOnClickListener { startActivity(Intent(this, AssistantActivity::class.java)) }
        binding.phoneButton.setOnClickListener { openPhone() }
        binding.cameraButton.setOnClickListener { openCamera() }
        binding.torchButton.setOnClickListener { requestOrToggleTorch() }
        binding.filterButton.setOnClickListener { showFilterMenu() }
        binding.sortButton.setOnClickListener { showSortMenu() }
        binding.settingsButton.setOnClickListener { showSettingsMenu() }
        binding.search.doAfterTextChanged { renderBrowse() }
        binding.intentInput.filters = arrayOf(InputFilter.LengthFilter(IntentInputPolicy.MAX_CHARS), InputFilter { source, start, end, _, _, _ ->
            if ((start until end).any { index -> java.lang.Character.isISOControl(source[index]) || source[index] == '\u2028' || source[index] == '\u2029' }) "" else null
        })
        binding.intentGo.setOnClickListener { submitIntent() }
        binding.intentInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { submitIntent(); true } else false
        }
        installBackHandling()
        installSwipeNavigation()

        val drawerActive = savedInstanceState?.getBoolean(STATE_DRAWER) == true
        appFilter = savedInstanceState?.getInt(STATE_FILTER)
            ?.let { AppFilter.entries.getOrNull(it) }
            ?: AppFilter.ALL
        binding.search.setText(savedInstanceState?.getString(STATE_SEARCH).orEmpty())
        binding.intentInput.setText(savedInstanceState?.getString(STATE_INTENT).orEmpty())
        if (drawerActive) showDrawer(requestKeyboard = false) else showHome(clearSearch = false)
        maybeShowOnboarding()
    }

    override fun onStart() {
        super.onStart()
        preferences = store.load()
        LaunchRouter.ownerStarted(this)
        intentRequestGate.start()
        binding.intentGo.isEnabled = true
        started = true
        registerPackageReceiver()
        refreshCatalog()
        refreshWeather()
        calendarObserver.start()
        renderPlan(PlanStore(this).load()?.first ?: PlannerCoordinator(this).immediateFallback())
        triggerPlanner()
    }

    override fun onResume() {
        super.onResume()
        preferences = store.load()
        showHome(clearSearch = true)
        renderHome()
        mainHandler.removeCallbacks(clockTick)
        clockTick.run()
        mainHandler.removeCallbacks(plannerTick)
        mainHandler.postDelayed(plannerTick, PLANNER_REFRESH_MS)
        PlannerCoordinator.schedule(this)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockTick)
        mainHandler.removeCallbacks(plannerTick)
        super.onPause()
    }

    override fun onStop() {
        disableTorch()
        LaunchRouter.ownerStopped(this)
        intentRequestGate.stop()
        started = false
        catalogGeneration++
        if (receiverRegistered) {
            unregisterReceiver(packageReceiver)
            receiverRegistered = false
        }
        calendarObserver.stop()
        super.onStop()
    }

    override fun onDestroy() {
        catalogExecutor.shutdownNow()
        contextExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DRAWER, binding.drawerPanel.isVisible)
        outState.putString(STATE_SEARCH, binding.search.text.toString())
        outState.putInt(STATE_FILTER, appFilter.ordinal)
        outState.putString(STATE_INTENT, binding.intentInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pendingGestureAction = null
            gestureStartRawX = event.rawX
            gestureStartRawY = event.rawY
            gestureStartedInHome = isPointInside(binding.homePanel, event.rawX.toInt(), event.rawY.toInt())
            gestureStartedInProtectedRegion = isPointInside(
                binding.bottomQuickActions,
                event.rawX.toInt(),
                event.rawY.toInt(),
            )
            resetGestureNudge(animated = false)
        }

        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) applyGestureNudge(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && pendingGestureAction == null) {
            val action = LauncherGesturePolicy.classifySwipeByDistance(
                startX = gestureStartRawX,
                startY = gestureStartRawY,
                endX = event.rawX,
                endY = event.rawY,
                density = resources.displayMetrics.density,
            )
            queueGestureAction(action, gestureStartRawX.toInt(), gestureStartRawY.toInt())
        }

        val claimedAction = pendingGestureAction
        if (claimedAction != null) {
            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
            pendingGestureAction = null
            resetGestureNudge(animated = false)
            performPendingGesture(claimedAction)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            resetGestureNudge(animated = true)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun installBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerPanel.isVisible) showHome(clearSearch = true)
                // Home is the task root: deliberately consume Back rather than finishing it.
            }
        })
    }

    private fun registerPackageReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }


    private fun refreshCatalog() {
        val generation = ++catalogGeneration
        catalogExecutor.execute {
            val loaded = repository.launchableApps()
            mainHandler.post {
                if (!started || generation != catalogGeneration || isDestroyed) return@post
                entries = loaded
                catalogLoaded = true
                renderHome()
                if (binding.drawerPanel.isVisible) {
                    renderBrowse()
                }
            }
        }
    }

    private fun catalog() = AppCatalog(entries, preferences)

    private fun renderHome() {
        renderHabit()
        PlanStore(this).load()?.first?.let(::renderPlan)
        binding.favoriteList.removeAllViews()
        val favorites = HomeSections.from(catalog()).favorites
        if (!catalogLoaded) {
            binding.favoriteList.addView(labelView(getString(R.string.loading_apps), false))
        } else if (favorites.isEmpty()) {
            binding.favoriteList.addView(labelView(getString(R.string.no_favorites), false))
        } else {
            favorites.forEach { binding.favoriteList.addView(appRow(it)) }
        }
    }

    private fun renderApps(apps: List<DisplayApp>) {
        if (!catalogLoaded) {
            appAdapter.replace(emptyList())
            binding.emptyApps.setText(R.string.loading_apps)
            binding.emptyApps.isVisible = true
            binding.appList.isVisible = false
            return
        }
        appAdapter.replace(apps)
        binding.emptyApps.text = getString(
            if (binding.search.text.isNullOrBlank()) R.string.no_apps_found else R.string.no_matching_apps
        )
        binding.emptyApps.isVisible = apps.isEmpty()
        binding.appList.isVisible = apps.isNotEmpty()
    }

    private fun renderBrowse() {
        val filterNames = resources.getStringArray(R.array.app_filters)
        val sortNames = resources.getStringArray(R.array.app_sorts)
        binding.filterButton.text = getString(R.string.filter_label, filterNames[appFilter.ordinal])
        binding.sortButton.text = getString(R.string.sort_label, sortNames[preferences.appSort.ordinal])
        renderApps(catalog().browse(binding.search.text.toString(), preferences.appSort, appFilter))
    }

    private fun appRow(app: DisplayApp, recycled: TextView? = null): TextView =
        (recycled ?: labelView(app.displayLabel, true)).apply {
            text = app.displayLabel
            textSize = 18f * preferences.textScale
            contentDescription = getString(R.string.open_app, app.displayLabel)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { launch(app) }
            setOnLongClickListener {
                showAppActions(app)
                true
            }
        }

    private fun labelView(textValue: String, actionable: Boolean) = TextView(this).apply {
        text = textValue
        textSize = 18f * preferences.textScale
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(MIN_ROW_HEIGHT_DP)
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = actionable
        isFocusable = actionable
        if (actionable) setBackgroundResource(android.R.drawable.list_selector_background)
    }

    private fun launch(app: DisplayApp) {
        preferences = preferences.recordLaunch(app.packageName, System.currentTimeMillis())
        store.save(preferences)
        LaunchRouter.fromLauncher(this, app.packageName, app.activityName, app.displayLabel)
    }

    private fun showAppActions(app: DisplayApp) {
        val favoriteAction = getString(if (app.isFavorite) R.string.remove_favorite else R.string.add_favorite)
        val actions = arrayOf(
            favoriteAction,
            getString(R.string.configure_limits),
            getString(R.string.rename),
            getString(R.string.hide),
            getString(R.string.app_info),
            getString(R.string.uninstall)
        )
        AlertDialog.Builder(this)
            .setTitle(app.displayLabel)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> update(preferences.toggleFavorite(app.packageName))
                    1 -> startActivity(Intent(this, AppRulesActivity::class.java)
                        .putExtra(AppRulesActivity.EXTRA_PACKAGE, app.packageName)
                        .putExtra(AppRulesActivity.EXTRA_LABEL, app.displayLabel))
                    2 -> showRename(app)
                    3 -> hideApp(app)
                    4 -> repository.openInfo(app.packageName)
                    5 -> repository.requestUninstall(app.packageName)
                }
            }.show()
    }

    private fun hideApp(app: DisplayApp) {
        val previous = preferences
        update(preferences.hide(app.packageName))
        snackbar(getString(R.string.app_hidden, app.displayLabel))
            .setAction(R.string.undo) { update(previous) }
            .show()
    }

    private fun showRename(app: DisplayApp) {
        val input = EditText(this).apply {
            setText(preferences.aliasFor(app.packageName) ?: app.displayLabel)
            selectAll()
            hint = getString(R.string.app_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_app, app.displayLabel))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                update(preferences.rename(app.packageName, input.text.toString()))
            }
            .setNeutralButton(R.string.use_original) { _, _ -> update(preferences.rename(app.packageName, "")) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun update(newValue: LauncherPreferences) {
        preferences = newValue
        store.save(newValue)
        renderHome()
        if (binding.drawerPanel.isVisible) renderBrowse()
    }

    private fun showDrawer(requestKeyboard: Boolean) {
        binding.homePanel.isVisible = false
        binding.bottomQuickActions.isVisible = false
        binding.drawerPanel.isVisible = true
        renderBrowse()
        if (requestKeyboard) {
            binding.search.requestFocus()
            binding.search.post {
                getSystemService<InputMethodManager>()
                    ?.showSoftInput(binding.search, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun showHome(clearSearch: Boolean) {
        if (clearSearch) binding.search.setText("")
        binding.search.clearFocus()
        binding.drawerPanel.isVisible = false
        binding.homePanel.isVisible = true
        binding.bottomQuickActions.isVisible = true
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(binding.search.windowToken, 0)
    }

    private fun showAppearanceMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance_visibility)
            .setItems(R.array.appearance_actions) { _, which ->
                when (which) {
                    0 -> saveAndRecreate(preferences.copy(theme = AppTheme.SYSTEM))
                    1 -> saveAndRecreate(preferences.copy(theme = AppTheme.LIGHT))
                    2 -> saveAndRecreate(preferences.copy(theme = AppTheme.DARK))
                    3 -> saveAndRecreate(preferences.copy(textScale = .85f))
                    4 -> saveAndRecreate(preferences.copy(textScale = 1f))
                    5 -> saveAndRecreate(preferences.copy(textScale = 1.25f))
                    6 -> update(preferences.copy(hiddenPackages = emptySet()))
                }
            }.show()
    }

    private fun showSettingsMenu() {
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        actions += R.string.daily_information_settings to {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        actions += R.string.launcher_settings to { showAppearanceMenu() }
        if (resources.getBoolean(R.bool.special_access_features_available)) {
            actions += R.string.permission_center to {
                startActivity(Intent(this, PermissionCenterActivity::class.java))
            }
            actions += R.string.usage_dashboard to {
                startActivity(Intent(this, UsageDashboardActivity::class.java))
            }
            actions += R.string.notification_inbox to {
                startActivity(Intent(this, NotificationInboxActivity::class.java))
            }
        }
        actions += R.string.choose_home to {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFilterMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_apps)
            .setSingleChoiceItems(R.array.app_filters, appFilter.ordinal) { dialog, which ->
                appFilter = AppFilter.entries[which]
                dialog.dismiss()
                renderBrowse()
            }
            .show()
    }

    private fun showSortMenu() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_apps)
            .setSingleChoiceItems(R.array.app_sorts, preferences.appSort.ordinal) { dialog, which ->
                dialog.dismiss()
                update(preferences.copy(appSort = AppSort.entries[which]))
            }
            .show()
    }

    private fun renderHabit() {
        if (preferences.routines.isEmpty()) {
            binding.habitButton.text = "Add priorities"
            binding.habitButton.contentDescription = "Add up to 15 routines or priorities"
            binding.habitButton.isSelected = false
            return
        }
        val active = preferences.routines.count { it.enabled }
        binding.habitButton.text = "Priorities · $active active"
        binding.habitButton.contentDescription = "Open $active active priorities"
        binding.habitButton.isSelected = false
    }

    private fun toggleHabit() {
        update(preferences.toggleHabit(LocalDate.now().toEpochDay()))
    }

    private fun visibleIntentApps(): List<IntentApp> = entries
        .filterNot { preferences.isHidden(it.packageName) || it.packageName == packageName }
        .map { IntentApp(preferences.aliasFor(it.packageName) ?: it.label, it.packageName, it.activityName) }

    private fun submitIntent() {
        val raw = binding.intentInput.text.toString()
        val clean = IntentInputPolicy.clean(raw)
        if (clean == null) return setIntentStatus("Use one line of at most 240 characters.")
        val apps = visibleIntentApps()
        when (val parsed = LocalIntentParser.parse(clean, apps, preferences.localTasks)) {
            is IntentParseResult.OpenApp -> {
                setIntentStatus("Opening ${parsed.app.label}…")
                logIntent("open_app", parsed.app.packageName)
                binding.intentInput.setText("")
                LaunchRouter.fromLauncher(this, parsed.app.packageName, parsed.app.activityName, parsed.app.label)
            }
            IntentParseResult.OpenApps -> { logIntent("apps", null); binding.intentInput.setText(""); showDrawer(requestKeyboard = true) }
            IntentParseResult.OpenPlan -> { logIntent("plan", null); binding.intentInput.setText(""); startActivity(Intent(this, AssistantActivity::class.java)) }
            is IntentParseResult.Action -> collectAndHandleAction(clean, apps, parsed.value, cloud = false)
            is IntentParseResult.Ambiguous -> setIntentStatus("More than one ${parsed.kind} matches. Be more specific.")
            is IntentParseResult.Invalid -> setIntentStatus(parsed.reason)
            IntentParseResult.Unmatched -> classifyIntent(clean, apps)
        }
    }

    private fun classifyIntent(text: String, apps: List<IntentApp>) {
        preferences = store.load()
        CloudIntentPreflight.ambiguity(text, apps, preferences.localTasks)?.let {
            setIntentStatus("More than one ${it.kind} matches. Be more specific.")
            return
        }
        val key = CredentialStore(this).openAiKey()
        if (!preferences.intentCloudEnabled || key.isNullOrBlank()) {
            setIntentStatus("Command not recognized. Try: apps, plan, alarm 07:30, task …, done …, or gym done.")
            return
        }
        val token = intentRequestGate.newRequest() ?: return
        binding.intentGo.isEnabled = false
        setIntentStatus("Checking command…")
        contextExecutor.execute {
            val result = runCatching {
                val collected = IntentContextCollector(this).collect(text, apps)
                val proposed = IntentClient().classify(key, collected.classifier, preferences.plannerProfile)
                proposed?.let { PlanValidator.validateAction(it, collected.validationSnapshot) } to collected
            }
            mainHandler.post {
                if (!intentRequestGate.accepts(token) || !started || isDestroyed) return@post
                binding.intentGo.isEnabled = true
                result.fold(
                    onSuccess = { (action, collected) -> if (action == null) setIntentStatus("No safe action matched.") else handleValidatedAction(action, collected.validationSnapshot) },
                    onFailure = { setIntentStatus("Could not safely classify that command.") },
                )
            }
        }
    }

    private fun collectAndHandleAction(text: String, apps: List<IntentApp>, action: PlanAction, cloud: Boolean) {
        val token = intentRequestGate.newRequest() ?: return
        binding.intentGo.isEnabled = false
        setIntentStatus(if (cloud) "Checking command…" else "Checking current state…")
        contextExecutor.execute {
            val result = runCatching {
                val collected = IntentContextCollector(this).collect(text, apps)
                PlanValidator.validateAction(action, collected.validationSnapshot)?.let { it to collected.validationSnapshot }
            }
            mainHandler.post {
                if (!intentRequestGate.accepts(token) || !started || isDestroyed) return@post
                binding.intentGo.isEnabled = true
                val validated = result.getOrNull()
                if (validated == null) setIntentStatus("That action is no longer available.") else handleValidatedAction(validated.first, validated.second)
            }
        }
    }

    private fun handleValidatedAction(action: PlanAction, snapshot: com.petertroll.quiethome.domain.PlannerSnapshot) {
        if (action.tool in setOf("add_task", "create_calendar_block", "set_alarm", "toggle_dnd")) {
            AlertDialog.Builder(this).setTitle(action.label).setMessage(actionSummary(action))
                .setPositiveButton(R.string.intent_confirm) { _, _ -> executeIntentAction(action, snapshot) }
                .setNegativeButton(R.string.cancel, null).show()
        } else executeIntentAction(action, snapshot)
    }

    private fun executeIntentAction(action: PlanAction, snapshot: com.petertroll.quiethome.domain.PlannerSnapshot) {
        val token = intentRequestGate.newRequest() ?: return
        binding.intentGo.isEnabled = false
        setIntentStatus("Checking current state…")
        contextExecutor.execute {
            // Calendar/provider/package revalidation and execution preparation never block the UI thread.
            if (!intentRequestGate.accepts(token)) return@execute
            val result = ActionExecutor(this).execute(action, snapshot, confirmed = true) {
                intentRequestGate.accepts(token) && started && !isDestroyed
            }
            mainHandler.post {
                if (!intentRequestGate.accepts(token) || !started || isDestroyed) return@post
                binding.intentGo.isEnabled = true
                finishIntentAction(action, result)
            }
        }
    }

    private fun finishIntentAction(action: PlanAction, result: Result<Unit>) {
        result.onSuccess {
            logIntent(action.tool, action.args["taskId"] ?: action.args["eventId"] ?: action.args["packageName"])
            binding.intentInput.setText("")
            setIntentStatus("Done · ${action.label}")
            if (action.tool in setOf("complete_task", "add_task", "mark_gym_done", "create_calendar_block")) {
                PlanStore(this).invalidate()
                lastPlannerTrigger = 0
                triggerPlanner(calendarBypass = true)
            }
            when (action.tool) {
                "complete_task" -> snackbar("Task completed.").setAction(R.string.undo) {
                    if (ActionExecutor(this).undoCompleteTask(action.args.getValue("taskId"))) { PlanStore(this).invalidate(); lastPlannerTrigger = 0; triggerPlanner(calendarBypass = true) }
                }.show()
                "mark_gym_done" -> ActivityLogStore(this).load().firstOrNull { it.kind == ActivityKind.GYM_ACTUAL }?.let { record ->
                    snackbar("Gym marked done.").setAction(R.string.undo) { if (ActionExecutor(this).undoGym(record.id)) { PlanStore(this).invalidate(); lastPlannerTrigger = 0; triggerPlanner(calendarBypass = true) } }.show()
                }
            }
        }.onFailure { setIntentStatus("Action failed or is no longer valid.") }
    }

    private fun actionSummary(action: PlanAction): String = when (action.tool) {
        "add_task" -> "Add task: ${action.args["title"]}"
        "set_alarm" -> "Open the alarm app for ${action.args["time"]}?"
        "toggle_dnd" -> "Turn Do Not Disturb ${action.args["state"]}?"
        "create_calendar_block" -> "Open the calendar to add: ${action.args["title"]}"
        else -> action.label
    }

    private fun logIntent(tool: String, ref: String?) {
        // Never persist command text. Only the fixed tool and an already-known reference are recorded.
        ActivityLogStore(this).append(ActivityKind.INTENT_COMMAND, listOfNotNull(tool, ref).joinToString("/"))
    }

    private fun setIntentStatus(value: String) { binding.intentStatus.text = value }


    private fun refreshWeather() {
        val cached = weatherRepository.cached()
        if (cached != null) {
            weatherSnapshot = cached
            renderWeather(cached)
            if (weatherRepository.isFresh(cached)) return
        }
        contextExecutor.execute {
            val refreshed = runCatching { weatherRepository.refresh() }.getOrNull()
            mainHandler.post {
                if (!started || isDestroyed) return@post
                if (refreshed != null) {
                    weatherSnapshot = refreshed
                    renderWeather(refreshed)
                    triggerPlanner()
                } else if (weatherSnapshot == null) {
                    binding.weatherText.setText(R.string.weather_unavailable)
                }
            }
        }
    }

    private fun triggerPlanner(calendarBypass: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!calendarBypass && now - lastPlannerTrigger < 5 * 60_000L) return
        lastPlannerTrigger = now
        contextExecutor.execute {
            val plan = PlannerCoordinator(this).refresh(calendarBypass)
            mainHandler.post { if (started && !isDestroyed) renderPlan(plan) }
        }
    }

    private fun renderPlan(plan: PlannerPlan) {
        if (!::binding.isInitialized) return
        binding.plannerHeadline.text = plan.headline
        binding.plannerNextItems.removeAllViews()
        plan.next.take(3).forEach { item ->
            binding.plannerNextItems.addView(labelView(listOfNotNull(item.time, item.text).joinToString(" · "), true).apply {
                setOnClickListener {
                    ActivityLogStore(this@LauncherActivity).append(ActivityKind.PLAN_SHOWN, item.refId)
                    startActivity(Intent(this@LauncherActivity, AssistantActivity::class.java))
                }
            })
        }
        val timestamp = PlanStore(this).load()?.second ?: System.currentTimeMillis()
        val age = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000)
        binding.plannerUpdated.text = if (age < 1) "Updated just now" else "Updated $age min ago"
        binding.plannerDigest.text = plan.digest.firstOrNull()?.text ?: "Digest · no additional items"
    }

    private fun renderWeather(snapshot: WeatherSnapshot) {
        val headline = snapshot.headline()
        binding.weatherText.text = if (weatherRepository.isFresh(snapshot)) {
            headline
        } else {
            getString(R.string.weather_stale, headline)
        }
        binding.weatherText.contentDescription = "Stockholm: $headline. ${snapshot.detail()}"
    }

    private fun showWeatherDetails() {
        val snapshot = weatherSnapshot ?: return showMessage(R.string.weather_unavailable)
        AlertDialog.Builder(this)
            .setTitle(snapshot.headline())
            .setMessage(getString(R.string.weather_details, snapshot.detail(), getString(R.string.smhi_attribution)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showRenameHabit() {
        val input = EditText(this).apply {
            setText(preferences.habitName)
            selectAll()
            hint = getString(R.string.habit_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_habit)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> update(preferences.renameHabit(input.text.toString())) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openClock() {
        val intents = listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS), Intent(AlarmClock.ACTION_SET_ALARM))
        val opened = intents.any { intent ->
            try {
                startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
        if (!opened) showMessage(R.string.clock_unavailable)
    }

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.camera_unavailable)
        }
    }

    private fun openPhone() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL))
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.phone_unavailable)
        }
    }

    private fun requestOrToggleTorch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            toggleTorch()
        } else {
            torchPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleTorch() {
        val cameraManager = getSystemService<CameraManager>() ?: return showTorchUnavailable()
        try {
            val cameraId = torchCameraId ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { torchCameraId = it } ?: return showTorchUnavailable()
            torchEnabled = !torchEnabled
            cameraManager.setTorchMode(cameraId, torchEnabled)
            renderTorch()
        } catch (_: Exception) {
            torchEnabled = false
            renderTorch()
            showTorchUnavailable()
        }
    }

    private fun disableTorch() {
        if (!torchEnabled) return
        val cameraId = torchCameraId ?: return
        runCatching { getSystemService<CameraManager>()?.setTorchMode(cameraId, false) }
        torchEnabled = false
        if (::binding.isInitialized) renderTorch()
    }

    private fun renderTorch() {
        binding.torchButton.setIconResource(
            if (torchEnabled) R.drawable.ic_flashlight_on_24 else R.drawable.ic_flashlight_24,
        )
        binding.torchButton.contentDescription = getString(if (torchEnabled) R.string.torch_on else R.string.torch_off)
        binding.torchButton.isSelected = torchEnabled
    }

    private fun showTorchUnavailable() {
        showMessage(R.string.torch_unavailable)
    }

    private fun saveAndRecreate(value: LauncherPreferences) {
        store.save(value)
        recreate()
    }

    private fun applyTheme(theme: AppTheme) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    private fun scaleText(view: View, scale: Float) {
        if (view is TextView) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, view.textSize * scale)
        if (view is ViewGroup) for (index in 0 until view.childCount) scaleText(view.getChildAt(index), scale)
    }

    private fun maybeShowOnboarding() {
        val state = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (!isDefaultHome() && !state.getBoolean("shown", false)) {
            state.edit().putBoolean("shown", true).apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        return packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == packageName
    }

    private fun installSwipeNavigation() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val capabilityAvailable = resources.getBoolean(R.bool.special_access_features_available)
                val interactiveTarget = isInteractiveAt(binding.root, e.rawX.toInt(), e.rawY.toInt())
                if (interactiveTarget) return false
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && capabilityAvailable) {
                    showMessage(R.string.lock_gesture_unsupported)
                    return false
                }
                if (!LauncherGesturePolicy.canLockOnDoubleTap(
                        isInteractiveTarget = interactiveTarget,
                        accessibilityCapabilityAvailable = capabilityAvailable,
                        sdkInt = Build.VERSION.SDK_INT,
                    )
                ) return false
                pendingGestureAction = LauncherGestureAction.LOCK_SCREEN
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val action = LauncherGesturePolicy.classifySwipe(
                    startX = e1.x,
                    startY = e1.y,
                    endX = e2.x,
                    endY = e2.y,
                    velocityX = velocityX,
                    velocityY = velocityY,
                    density = resources.displayMetrics.density,
                )
                return queueGestureAction(action, e1.rawX.toInt(), e1.rawY.toInt())
            }
        })
    }

    private fun queueGestureAction(action: LauncherGestureAction, rawX: Int, rawY: Int): Boolean {
        if (!LauncherGesturePolicy.canHandleSwipe(
                action = action,
                isInteractiveTarget = isInteractiveAt(binding.root, rawX, rawY),
                startsInHomeContent = isPointInside(binding.homePanel, rawX, rawY),
                startsInProtectedRegion = isPointInside(binding.bottomQuickActions, rawX, rawY),
            )
        ) return false
        if (
            action == LauncherGestureAction.OPEN_BROWSER &&
            (!binding.homePanel.isVisible || binding.homePanel.canScrollVertically(1))
        ) return false
        if (
            action == LauncherGestureAction.OPEN_NOTIFICATIONS &&
            binding.homePanel.isVisible &&
            binding.homePanel.canScrollVertically(-1)
        ) return false
        if (
            action == LauncherGestureAction.OPEN_PREVIOUS_APP &&
            !resources.getBoolean(R.bool.special_access_features_available)
        ) return false
        pendingGestureAction = action
        return true
    }

    private fun applyGestureNudge(event: MotionEvent) {
        val dx = event.rawX - gestureStartRawX
        val dy = event.rawY - gestureStartRawY
        val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
        val scrollBoundaryAllowsNudge = horizontal ||
            (dy < 0f && !binding.homePanel.canScrollVertically(1)) ||
            (dy > 0f && !binding.homePanel.canScrollVertically(-1))
        val nudge = GestureNudgePolicy.offset(
            dx = dx,
            dy = dy,
            density = resources.displayMetrics.density,
            enabled = binding.homePanel.isVisible && gestureStartedInHome &&
                !gestureStartedInProtectedRegion && scrollBoundaryAllowsNudge,
        )
        binding.homePanel.animate().cancel()
        binding.homePanel.translationX = nudge.x
        binding.homePanel.translationY = nudge.y
    }

    private fun resetGestureNudge(animated: Boolean) {
        binding.homePanel.animate().cancel()
        if (animated && (binding.homePanel.translationX != 0f || binding.homePanel.translationY != 0f)) {
            binding.homePanel.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(GESTURE_NUDGE_RESET_MS)
                .start()
        } else {
            binding.homePanel.translationX = 0f
            binding.homePanel.translationY = 0f
        }
    }

    private fun performPendingGesture(action: LauncherGestureAction) {
        when (action) {
            LauncherGestureAction.LOCK_SCREEN -> lockScreen()
            LauncherGestureAction.OPEN_NOTIFICATIONS -> openNotificationShade()
            LauncherGestureAction.OPEN_BROWSER -> openBrowser()
            LauncherGestureAction.OPEN_DRAWER -> showDrawer(requestKeyboard = true)
            LauncherGestureAction.OPEN_PREVIOUS_APP -> openPreviousApp()
            LauncherGestureAction.NONE -> Unit
        }
    }

    private fun openBrowser() {
        val browserIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        try {
            startActivity(browserIntent)
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.browser_unavailable)
        }
    }

    private fun openNotificationShade() {
        if (QuietEnforcementService.performSystemAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return
        val expanded = runCatching {
            val statusBar = getSystemService("statusbar") ?: return@runCatching false
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
            true
        }.getOrDefault(false)
        if (!expanded) {
            if (resources.getBoolean(R.bool.special_access_features_available)) {
                showAccessibilityNeeded(R.string.notifications_gesture_needs_accessibility)
            } else {
                showMessage(R.string.notifications_gesture_unavailable)
            }
        }
    }

    private fun lockScreen() {
        if (!QuietEnforcementService.performSystemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            showAccessibilityNeeded(R.string.lock_gesture_needs_accessibility)
        }
    }

    private fun openPreviousApp() {
        catalogExecutor.execute {
            val packageName = RecentAppResolver(this).resolve()
            val intent = packageName?.let(packageManager::getLaunchIntentForPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            mainHandler.post {
                if (!started || isDestroyed) return@post
                if (intent == null) {
                    showMessage(R.string.previous_app_unavailable)
                } else {
                    runCatching { startActivity(intent) }.onFailure {
                        showMessage(R.string.previous_app_unavailable)
                    }
                }
            }
        }
    }

    private fun isPointInside(view: View, rawX: Int, rawY: Int): Boolean {
        if (!view.isShown) return false
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX, rawY)
    }

    private fun isInteractiveAt(view: View, rawX: Int, rawY: Int): Boolean {
        if (!view.isShown) return false
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(rawX, rawY)) return false
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                if (isInteractiveAt(view.getChildAt(index), rawX, rawY)) return true
            }
        }
        return view !== binding.root && (view.isClickable || view.isLongClickable || view is EditText)
    }

    private fun showAccessibilityNeeded(message: Int) {
        snackbar(message)
            .setAction(R.string.open_accessibility_settings) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .show()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun snackbar(message: Int): Snackbar = snackbar(getString(message))

    private fun snackbar(message: CharSequence): Snackbar =
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setActionTextColor(ContextCompat.getColor(this, R.color.quiet_on_primary))
            .also { snackbar ->
                if (binding.bottomQuickActions.isVisible) {
                    snackbar.anchorView = binding.bottomQuickActions
                }
            }

    private fun showMessage(message: Int) {
        snackbar(message).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class AppListAdapter : ArrayAdapter<DisplayApp>(this@LauncherActivity, 0) {
        fun replace(apps: List<DisplayApp>) {
            clear()
            addAll(apps)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val recycled = convertView as? TextView
            return appRow(getItem(position)!!, recycled)
        }
    }

    private companion object {
        const val CLOCK_REFRESH_MS = 30_000L
        const val PLANNER_REFRESH_MS = 30 * 60_000L
        const val GESTURE_NUDGE_RESET_MS = 90L
        const val MIN_ROW_HEIGHT_DP = 56
        const val STATE_DRAWER = "drawer"
        const val STATE_SEARCH = "search"
        const val STATE_FILTER = "filter"
        const val STATE_INTENT = "intent"
    }
}
