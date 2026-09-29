package com.cinejoytv.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates the app from the repo's latest GitHub release.
 *
 * CI tags each release "build-N", where N is also the versionCode of the APKs in it. Every build
 * is signed with the same release key, so Android accepts the download as an update.
 */
class Updater(private val activity: Activity) {

    private class Release(val build: Int, val apkUrl: String)

    /** Looks for a newer build. `manual` also reports "up to date" and errors. */
    fun check(manual: Boolean) {
        if (manual) toast("Checking for updates…")
        thread(name = "update-check") {
            val release = try {
                latestRelease()
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (manual) toast("Update check failed")
                return@thread
            }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (release == null || release.build <= BuildConfig.VERSION_CODE) {
                    if (manual) toast("Up to date (build ${BuildConfig.VERSION_CODE})")
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity)
                    .setTitle("Update available")
                    .setMessage("Build ${release.build} is available. You have build ${BuildConfig.VERSION_CODE}.")
                    .setPositiveButton("Install") { _, _ -> install(release) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    /** Blocking. Returns this app's APK from the latest release, or null if the release has none. */
    private fun latestRelease(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "fire-tv-apps-updater")
        val json = try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val build = json.getString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == BuildConfig.APK_NAME) {
                return Release(build, asset.getString("browser_download_url"))
            }
        }
        return null
    }

    private fun install(release: Release) {
        // Android 8+ asks per app whether it may install other apps (here: its own update).
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
                toast("Allow this app to install apps, then choose Install again")
            } catch (e: ActivityNotFoundException) {
                toast("Turn on Install unknown apps for this app in Settings > My Fire TV > Developer Options, then try again")
            }
            return
        }
        toast("Downloading update…")
        thread(name = "update-install") {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            var sessionId = -1
            try {
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    try {
                        session.openWrite("update.apk", 0, -1).use { out ->
                            conn.inputStream.use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    } finally {
                        conn.disconnect()
                    }
                    // The system shows its install confirmation; the result arrives in UpdateReceiver.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val callback = PendingIntent.getBroadcast(
                        activity, sessionId, Intent(activity, UpdateReceiver::class.java), flags
                    )
                    session.commit(callback.intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update install failed", e)
                if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
                toast("Update failed")
            }
        }
    }

    private fun toast(msg: String) =
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }

    companion object {
        const val TAG = "Updater"
    }
}

/** Receives the installer's result: shows the confirmation screen, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android closes the old version; reopen the app.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // User chose Cancel.
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(Updater.TAG, "Install failed: $status $msg")
                Toast.makeText(context, "Update failed: ${msg ?: status}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
