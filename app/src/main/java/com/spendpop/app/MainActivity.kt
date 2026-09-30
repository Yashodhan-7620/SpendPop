package com.spendpop.app

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private var wasUnlocked = false; private var away = 0L
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { SyncWorker.kick(this) }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots / recents preview
        val vm = ViewModelProvider(this)[Vm::class.java]
        setContent { PopTheme { if (unlocked) App(vm, ::connect, ::askPerms) else LockScreen(::lock) } }
        checkUpdate()
    }
    override fun onStart() { super.onStart(); if (wasUnlocked && SystemClock.elapsedRealtime() - away < 30_000) unlocked = true else lock() }
    override fun onStop() { super.onStop(); wasUnlocked = unlocked; unlocked = false; away = SystemClock.elapsedRealtime() }

    private fun lock() {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock SpendPop")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { unlocked = true }
        }).authenticate(info)   // fails closed if the phone has no screen lock
    }
    private fun connect() {
        Identity.getAuthorizationClient(this).authorize(Auth.req()).addOnSuccessListener { r ->
            if (r.hasResolution()) authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else SyncWorker.kick(this)
        }
    }
    private fun askPerms() {
        permLauncher.launch(if (BuildConfig.SMS) arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS)
                            else arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    private fun checkUpdate() {   // in-app updates: works for Play-installed builds
        val m = AppUpdateManagerFactory.create(this)
        m.registerListener { if (it.installStatus() == InstallStatus.DOWNLOADED) m.completeUpdate() }
        m.appUpdateInfo.addOnSuccessListener { i ->
            if (i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE))
                m.startUpdateFlow(i, this, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
        }
    }
}
