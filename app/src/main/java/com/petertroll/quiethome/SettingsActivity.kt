package com.petertroll.quiethome

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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

        section(getString(R.string.settings_home))
        action(getString(R.string.configure_routine)) { showRoutineSettings() }
        text(getString(R.string.weather_fixed_stockholm))
        action(getString(R.string.clear_weather_cache)) {
            WeatherRepository(this).clear()
            message(getString(R.string.weather_cache_cleared))
        }

        section(getString(R.string.settings_calendar))
        val prefs = store.load()
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
        action(getString(R.string.delete_routine_history)) {
            val current = store.load()
            store.save(current.copy(routineCompletedEpochDays = emptySet(), habitDoneEpochDay = -1L))
            message(getString(R.string.routine_history_deleted))
        }

        section(getString(R.string.settings_appearance))
        text(getString(R.string.appearance_from_launcher_note))
    }

    private fun showRoutineSettings() {
        val current = store.load()
        val name = EditText(this).apply { setText(current.habitName); hint = getString(R.string.routine_name) }
        val target = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.routineWeeklyTarget.toString())
            hint = getString(R.string.weekly_target)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(name)
            addView(target)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.configure_routine)
            .setView(fields)
            .setPositiveButton(R.string.save) { _, _ ->
                store.save(current.copy(
                    habitName = name.text.toString().trim().ifEmpty { "Gym" },
                    routineWeeklyTarget = target.text.toString().toIntOrNull()?.coerceIn(1, 7) ?: 3,
                ))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
