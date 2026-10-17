package com.petertroll.quiethome

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs

class LauncherActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLauncherBinding
    private lateinit var store: PreferencesStore
    private lateinit var repository: AndroidAppRepository
    private lateinit var appAdapter: AppListAdapter
    private lateinit var gestureDetector: GestureDetector
    private var preferences = LauncherPreferences()
    private var entries = emptyList<AppEntry>()
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
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
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
        binding.cameraButton.setOnClickListener { openCamera() }
        binding.torchButton.setOnClickListener { requestOrToggleTorch() }
        binding.filterButton.setOnClickListener { showFilterMenu() }
        binding.sortButton.setOnClickListener { showSortMenu() }
        binding.appearanceButton.setOnClickListener { showAppearanceMenu() }
        binding.permissionButton.setOnClickListener {
            startActivity(Intent(this, PermissionCenterActivity::class.java))
        }
        binding.usageDashboardButton.setOnClickListener { startActivity(Intent(this, UsageDashboardActivity::class.java)) }
        binding.inboxButton.setOnClickListener { startActivity(Intent(this, NotificationInboxActivity::class.java)) }
        binding.defaultHomeButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        binding.search.doAfterTextChanged { renderBrowse() }
        val specialAccessAvailable = resources.getBoolean(R.bool.special_access_features_available)
        binding.permissionButton.isVisible = specialAccessAvailable
        binding.usageDashboardButton.isVisible = specialAccessAvailable
        binding.inboxButton.isVisible = specialAccessAvailable
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
        renderHome()
        if (binding.drawerPanel.isVisible) renderBrowse()
        binding.defaultHomeButton.isVisible = !isDefaultHome()
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
        val handledSwipe = gestureDetector.onTouchEvent(event)
        return if (handledSwipe && event.actionMasked != MotionEvent.ACTION_DOWN) true else super.dispatchTouchEvent(event)
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
        if (favorites.isEmpty()) {
            binding.favoriteList.addView(labelView(getString(R.string.no_favorites), false))
        } else {
            favorites.forEach { binding.favoriteList.addView(appRow(it)) }
        }
    }

    private fun renderApps(apps: List<DisplayApp>) {
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
        minHeight = dp(ROW_HEIGHT_DP)
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
                    3 -> update(preferences.hide(app.packageName))
                    4 -> repository.openInfo(app.packageName)
                    5 -> repository.requestUninstall(app.packageName)
                }
            }.show()
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
        if (!opened) Toast.makeText(this, R.string.clock_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show()
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

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || abs(e2.x - e1.x) < dp(80) || abs(velocityX) < abs(velocityY)) return false
                if (e2.x < e1.x) showDrawer(requestKeyboard = true) else showHome(clearSearch = true)
                return true
            }
        })
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
            return appRow(getItem(position)!!, recycled).apply {
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(ROW_HEIGHT_DP)
                )
            }
        }
    }

    private companion object {
        const val CLOCK_REFRESH_MS = 30_000L
        const val ROW_HEIGHT_DP = 56
        const val STATE_DRAWER = "drawer"
        const val STATE_SEARCH = "search"
        const val STATE_FILTER = "filter"
    }
}
