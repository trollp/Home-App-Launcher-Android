package com.petertroll.quiethome

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.snackbar.Snackbar
import com.petertroll.quiethome.domain.AiProvider
import com.petertroll.quiethome.domain.OutlookSupport
import com.petertroll.quiethome.domain.Proactivity
import com.petertroll.quiethome.domain.Routine
import com.petertroll.quiethome.domain.RoutineCollection
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    private lateinit var store: PreferencesStore
    private lateinit var credentials: CredentialStore
    private lateinit var root: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreferencesStore(this)
        credentials = CredentialStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
        handleOutlookRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOutlookRedirect(intent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        title(getString(R.string.settings))
        val prefs = store.load()

        section(getString(R.string.settings_home))
        action("Manage up to five routines / priorities") { showRoutineSettings() }
        text(if (prefs.routines.isEmpty()) "No routines configured." else prefs.routines.joinToString("\n") { "${if (it.enabled) "On" else "Off"} · ${it.name} · ${it.weeklyTarget}/week" })
        action("Proactive suggestions: ${prefs.proactivity.name.lowercase().replace('_', ' ')}") { chooseProactivity() }
        text(getString(R.string.weather_fixed_stockholm))
        action(getString(R.string.clear_weather_cache)) {
            WeatherRepository(this).clear()
            message(getString(R.string.weather_cache_cleared))
        }

        section(getString(R.string.settings_calendar))
        text(when {
            credentials.hasOutlookConnection() -> getString(R.string.outlook_connected)
            prefs.outlookClientId.isBlank() -> getString(R.string.outlook_not_configured)
            else -> getString(R.string.outlook_ready_to_connect)
        })
        action(getString(R.string.outlook_app_registration)) { editOutlookClientId() }
        if (credentials.hasOutlookConnection()) {
            action(getString(R.string.outlook_disconnect)) {
                credentials.clearOutlookTokens()
                render()
            }
        } else {
            action(getString(R.string.outlook_connect)) { connectOutlook() }
        }

        section(getString(R.string.settings_ai))
        text(getString(R.string.ai_status_format, prefs.aiProvider.name.lowercase(), if (credentials.hasApiKey(prefs.aiProvider)) getString(R.string.key_saved) else getString(R.string.no_key_saved)))
        action(getString(R.string.choose_ai_provider)) { chooseProvider() }
        action(getString(R.string.enter_api_key)) { enterApiKey() }
        action(getString(R.string.test_api_key)) { testApiKey() }
        action(getString(R.string.delete_api_key)) {
            credentials.clearApiKey(store.load().aiProvider)
            render()
        }

        section(getString(R.string.settings_privacy))
        text(getString(R.string.privacy_data_flow))
        text(getString(R.string.smhi_attribution))
        text(getString(R.string.byok_risk))
        action(if (prefs.learningEnabled) "Pause learning" else "Resume learning") {
            store.save(prefs.copy(learningEnabled = !prefs.learningEnabled)); render()
        }
        action("Clear feedback") { store.save(prefs.copy(feedback = emptyList())); message("Feedback cleared.") }
        action(getString(R.string.delete_routine_history)) {
            val current = store.load()
            store.save(current.copy(routines = current.routines.map { it.copy(completedEpochDays = emptySet()) }, routineCompletedEpochDays = emptySet(), habitDoneEpochDay = -1L))
            message(getString(R.string.routine_history_deleted))
        }

        section(getString(R.string.settings_appearance))
        text(getString(R.string.appearance_from_launcher_note))
    }

    private fun showRoutineSettings() {
        val current = store.load()
        val labels = current.routines.map { "${if (it.enabled) "On" else "Off"} · ${it.name}" }.toMutableList()
        if (current.routines.size < RoutineCollection.MAX_ROUTINES) labels += "Add routine"
        if (labels.isEmpty()) return editRoutine(null)
        AlertDialog.Builder(this).setTitle("Routines and priorities").setItems(labels.toTypedArray()) { _, index ->
            editRoutine(current.routines.getOrNull(index))
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun editRoutine(existing: Routine?) {
        val current = store.load()
        val name = EditText(this).apply { setText(existing?.name.orEmpty()); hint = getString(R.string.routine_name) }
        val normal = EditText(this).apply { setText(existing?.normalAction.orEmpty()); hint = "Normal action" }
        val smaller = EditText(this).apply { setText(existing?.smallerAction.orEmpty()); hint = "Smaller action" }
        val target = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.weeklyTarget ?: 3).toString())
            hint = getString(R.string.weekly_target)
        }
        val enabled = CheckBox(this).apply { text = "Enabled"; isChecked = existing?.enabled ?: true }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(name); addView(normal); addView(smaller); addView(target); addView(enabled)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add routine" else "Edit routine")
            .setView(fields)
            .setPositiveButton(R.string.save) { _, _ ->
                val cleanName = name.text.toString().trim().take(60)
                if (cleanName.isNotEmpty()) {
                    val id = existing?.id ?: "routine-${System.currentTimeMillis()}"
                    val changed = Routine(id, cleanName,
                        normal.text.toString().trim().take(100).ifEmpty { "Spend 15 minutes on $cleanName" },
                        smaller.text.toString().trim().take(100).ifEmpty { "Take one small step on $cleanName" },
                        target.text.toString().toIntOrNull()?.coerceIn(1, 7) ?: 3,
                        enabled.isChecked, existing?.completedEpochDays ?: emptySet())
                    val list = if (existing == null) current.routines + changed else current.routines.map { if (it.id == existing.id) changed else it }
                    store.save(current.copy(routines = RoutineCollection.sanitize(list)))
                    render()
                }
            }
            .setNeutralButton(if (existing == null) "Reset" else "Delete") { _, _ ->
                if (existing != null) { store.save(current.copy(routines = current.routines.filterNot { it.id == existing.id })); render() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseProactivity() {
        val labels = arrayOf("Off", "Once daily", "Context changes")
        val current = store.load()
        AlertDialog.Builder(this).setTitle("Proactive suggestion card").setSingleChoiceItems(labels, current.proactivity.ordinal) { dialog, which ->
            store.save(current.copy(proactivity = Proactivity.entries[which])); dialog.dismiss(); render()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseProvider() {
        val values = arrayOf(getString(R.string.ai_off), "OpenAI · GPT-5 nano", "Groq")
        val current = store.load()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_ai_provider)
            .setSingleChoiceItems(values, current.aiProvider.ordinal) { dialog, which ->
                val provider = AiProvider.entries[which]
                val model = when (provider) {
                    AiProvider.OPENAI -> "gpt-5-nano"
                    AiProvider.GROQ -> "openai/gpt-oss-20b"
                    AiProvider.NONE -> current.aiModel
                }
                store.save(current.copy(aiProvider = provider, aiModel = model))
                dialog.dismiss()
                render()
            }.show()
    }

    private fun enterApiKey() {
        val provider = store.load().aiProvider
        if (provider == AiProvider.NONE) return message(getString(R.string.configure_ai_first))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.api_key_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_api_key)
            .setMessage(R.string.api_key_security_note)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> credentials.saveApiKey(provider, input.text.toString()); render() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun testApiKey() {
        val prefs = store.load()
        val key = credentials.apiKey(prefs.aiProvider)
        if (prefs.aiProvider == AiProvider.NONE || key == null) return message(getString(R.string.configure_ai_first))
        message(getString(R.string.testing_connection))
        executor.execute {
            val result = runCatching { AiCoachClient().suggest(prefs.aiProvider, key, prefs.aiModel, "Connection test only.") }
            mainHandler.post {
                message(result.fold({ getString(R.string.ai_test_success) }, { getString(R.string.ai_test_failed, it.message ?: "error") }))
            }
        }
    }

    private fun editOutlookClientId() {
        val current = store.load()
        val input = EditText(this).apply { setText(current.outlookClientId); hint = getString(R.string.microsoft_client_id) }
        AlertDialog.Builder(this)
            .setTitle(R.string.outlook_app_registration)
            .setMessage(R.string.outlook_registration_explanation)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                store.save(current.copy(outlookClientId = input.text.toString().trim()))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun connectOutlook() {
        val clientId = store.load().outlookClientId
        if (clientId.isBlank()) return message(getString(R.string.outlook_client_id_needed))
        runCatching { startActivity(OutlookCalendarClient(this).authorizationIntent(clientId)) }
            .onFailure { message(getString(R.string.outlook_connect_failed, it.message ?: "error")) }
    }

    private fun handleOutlookRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != OutlookSupport.REDIRECT_SCHEME ||
            uri.host != OutlookSupport.REDIRECT_HOST ||
            uri.path != OutlookSupport.REDIRECT_PATH
        ) return
        val clientId = store.load().outlookClientId
        if (clientId.isBlank()) return message(getString(R.string.outlook_client_id_needed))
        message(getString(R.string.outlook_connecting))
        intent.data = null
        executor.execute {
            val result = runCatching { OutlookCalendarClient(this).completeAuthorization(uri, clientId) }
            mainHandler.post {
                if (isDestroyed) return@post
                result.onSuccess {
                    render()
                    message(getString(R.string.outlook_connected_success))
                }.onFailure {
                    message(getString(R.string.outlook_connect_failed, it.message ?: "error"))
                }
            }
        }
    }

    private fun title(value: String) = root.addView(TextView(this).apply {
        text = value; textSize = 28f; gravity = Gravity.START; setPadding(0, 0, 0, dp(16))
    })

    private fun section(value: String) = root.addView(TextView(this).apply {
        text = value; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8)); ViewCompat.setAccessibilityHeading(this, true)
    })

    private fun text(value: String) = root.addView(TextView(this).apply {
        text = value; textSize = 16f; setPadding(0, dp(4), 0, dp(8))
    })

    private fun action(value: String, listener: () -> Unit) = root.addView(Button(this).apply {
        text = value; textSize = 16f; isAllCaps = false; minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { listener() }
    })

    private fun message(value: String) = Snackbar.make(root, value, Snackbar.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
