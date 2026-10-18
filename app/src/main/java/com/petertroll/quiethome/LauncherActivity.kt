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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.petertroll.quiethome.domain.DisplayApp
import com.petertroll.quiethome.domain.HomeSections
import com.petertroll.quiethome.domain.LauncherPreferences
import com.petertroll.quiethome.domain.LauncherGestureAction
import com.petertroll.quiethome.domain.LauncherGesturePolicy
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.Executors

class LauncherActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherBinding
    private lateinit var store: PreferencesStore
    private lateinit var repository: AndroidAppRepository
    private lateinit var appAdapter: AppListAdapter
    private lateinit var gestureDetector: GestureDetector
    private var pendingGestureAction: LauncherGestureAction? = null
    private var preferences = LauncherPreferences()
    private var entries = emptyList<AppEntry>()
    private var catalogLoaded = false
    private var appFilter = AppFilter.ALL
    private var torchCameraId: String? = null
    private var torchEnabled = false
    private var catalogGeneration = 0
    private var receiverRegistered = false
    private var started = false
    private val catalogExecutor = Executors.newSingleThreadExecutor()
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
            binding.date.text = DateFormat.getDateInstance(DateFormat.FULL).format(now)
            renderHabit()
            mainHandler.postDelayed(this, CLOCK_REFRESH_MS)
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
        ViewCompat.setAccessibilityHeading(binding.favoritesHeading, true)
        ViewCompat.setAccessibilityHeading(binding.allAppsHeading, true)
        binding.clock.contentDescription = getString(R.string.open_clock)
        repository = AndroidAppRepository(this)
        appAdapter = AppListAdapter()
        binding.appList.adapter = appAdapter

        binding.appsButton.setOnClickListener { showDrawer(requestKeyboard = true) }
        binding.homeButton.setOnClickListener { showHome(clearSearch = true) }
        binding.clock.setOnClickListener { openClock() }
        binding.habitButton.setOnClickListener { toggleHabit() }
        binding.habitButton.setOnLongClickListener {
            showRenameHabit()
            true
        }
        binding.phoneButton.setOnClickListener { openPhone() }
        binding.cameraButton.setOnClickListener { openCamera() }
        binding.torchButton.setOnClickListener { requestOrToggleTorch() }
        binding.filterButton.setOnClickListener { showFilterMenu() }
        binding.sortButton.setOnClickListener { showSortMenu() }
        binding.settingsButton.setOnClickListener { showSettingsMenu() }
        binding.search.doAfterTextChanged { renderBrowse() }
        installBackHandling()
        installSwipeNavigation()

        val drawerActive = savedInstanceState?.getBoolean(STATE_DRAWER) == true
        appFilter = savedInstanceState?.getInt(STATE_FILTER)
            ?.let { AppFilter.entries.getOrNull(it) }
            ?: AppFilter.ALL
        binding.search.setText(savedInstanceState?.getString(STATE_SEARCH).orEmpty())
        if (drawerActive) showDrawer(requestKeyboard = false) else showHome(clearSearch = false)
        maybeShowOnboarding()
    }

    override fun onStart() {
        super.onStart()
        LaunchRouter.ownerStarted(this)
        started = true
        registerPackageReceiver()
        refreshCatalog()
    }

    override fun onResume() {
        super.onResume()
        preferences = store.load()
        showHome(clearSearch = true)
        renderHome()
        mainHandler.removeCallbacks(clockTick)
        clockTick.run()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockTick)
        super.onPause()
    }

    override fun onStop() {
        disableTorch()
        LaunchRouter.ownerStopped(this)
        started = false
        catalogGeneration++
        if (receiverRegistered) {
            unregisterReceiver(packageReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        catalogExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DRAWER, binding.drawerPanel.isVisible)
        outState.putString(STATE_SEARCH, binding.search.text.toString())
        outState.putInt(STATE_FILTER, appFilter.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pendingGestureAction = null
        gestureDetector.onTouchEvent(event)
        val claimedAction = pendingGestureAction
        if (claimedAction != null) {
            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
            pendingGestureAction = null
            performPendingGesture(claimedAction)
            return true
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
        val done = preferences.isHabitDone(LocalDate.now().toEpochDay())
        binding.habitButton.text = getString(
            if (done) R.string.habit_button_done else R.string.habit_button_not_done,
            preferences.habitName
        )
        binding.habitButton.contentDescription = getString(
            if (done) R.string.habit_done else R.string.habit_not_done,
            preferences.habitName
        )
        binding.habitButton.isSelected = done
    }

    private fun toggleHabit() {
        update(preferences.toggleHabit(LocalDate.now().toEpochDay()))
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
        binding.torchButton.text = getString(if (torchEnabled) R.string.torch_on else R.string.torch)
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
                val rawX = e1.rawX.toInt()
                val rawY = e1.rawY.toInt()
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
        })
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
        const val MIN_ROW_HEIGHT_DP = 56
        const val STATE_DRAWER = "drawer"
        const val STATE_SEARCH = "search"
        const val STATE_FILTER = "filter"
    }
}
