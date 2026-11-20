package com.asnidev.sysreadout.cover

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.window.SplashScreen

/**
 * Invisible: asks Android to bring up its own PIN pad and fingerprint right away,
 * skipping the lock screen's clock page, then goes. Android does the unlocking.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityOptions.makeBasic().apply { splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR }.toBundle()
            } else {
                null
            }
            runCatching { context.startActivity(intent, options) }.onFailure { Log.w("UnlockActivity", "couldn't ask for the unlock", it) }
        }
    }
}
