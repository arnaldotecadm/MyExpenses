package org.totschnig.myexpenses.util.bundle

import android.app.Activity
import android.app.Application
import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.MyApplication.DEFAULT_LANGUAGE
import timber.log.Timber


class PlatformLocaleManager : LocaleManager {
    private lateinit var manager: SplitInstallManager
    var listener: SplitInstallStateUpdatedListener? = null
    var callback: (() -> Unit)? = null
    override fun initApplication(application: Application) {
        SplitCompat.install(application)
        manager = SplitInstallManagerFactory.create(application)
    }

    override fun initActivity(activity: Activity) {
        SplitCompat.installActivity(activity)
    }

    override fun requestLocale(context: Context) {
        val application = context.applicationContext as MyApplication
        val userLanguage = application.defaultLanguage
        if (userLanguage.equals(DEFAULT_LANGUAGE)) {
            callback?.invoke()
        } else {
            val installedLanguages = manager.installedLanguages
            Timber.d("Downloaded languages: %s", installedLanguages.joinToString())
            val userPreferedLocale = application.resolveLocale(userLanguage)
            if (installedLanguages.contains(userPreferedLocale.language)) {
                Timber.d("Already installed")
                callback?.invoke()
            } else {
                val request = SplitInstallRequest.newBuilder()
                        .addLanguage(userPreferedLocale)
                        .build()
                manager.startInstall(request)
                        .addOnFailureListener { exception -> Timber.e(exception) }

            }
        }
    }

    override fun onResume(onAvailable: () -> Unit) {
        callback = onAvailable
        listener = SplitInstallStateUpdatedListener { state ->
            if (state.status() == SplitInstallSessionStatus.INSTALLED) {
                callback?.invoke()
            }
        }
        manager.registerListener(listener)
    }

    override fun onPause() {
        callback = null
        manager.unregisterListener(listener)
    }
}