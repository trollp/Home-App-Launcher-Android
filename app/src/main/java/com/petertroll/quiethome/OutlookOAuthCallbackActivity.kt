package com.petertroll.quiethome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.petertroll.quiethome.domain.OutlookSupport

/** Minimal exported trampoline for the exact Microsoft OAuth callback route. */
class OutlookOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.data
        if (callback?.scheme == OutlookSupport.REDIRECT_SCHEME &&
            callback.host == OutlookSupport.REDIRECT_HOST &&
            callback.path == OutlookSupport.REDIRECT_PATH
        ) {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                data = callback
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        finish()
    }
}
