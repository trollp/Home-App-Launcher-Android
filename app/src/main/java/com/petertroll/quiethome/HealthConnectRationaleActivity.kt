package com.petertroll.quiethome

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** System-facing Health Connect rationale. It intentionally contains no deep links or data. */
class HealthConnectRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply { text = "Health Connect and Quiet Home"; textSize = 24f })
            addView(TextView(context).apply {
                text = "Quiet Home can read sleep, exercise, heart rate, steps, and total calories to create neutral planner summaries. Access is optional and read-only. Raw Health Connect records, samples, IDs, and data origins are not stored; only bounded aggregates are cached on this device. If cloud planning is separately enabled, those aggregates can be included in the disclosed planner snapshot sent to OpenAI. You can revoke access in Health Connect at any time."
                textSize = 16f
                setPadding(0, padding, 0, padding)
            })
            addView(Button(context).apply { text = "Done"; setOnClickListener { finish() } })
        })
    }
}