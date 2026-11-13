/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.openhab.habdroid.BuildConfig

/**
 * Acasa: checks GitHub releases of fizroy/Acasa for a newer build and installs it.
 */
object AcasaUpdater {
    private val TAG = AcasaUpdater::class.java.simpleName
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/fizroy/Acasa/releases/latest"
    private const val PREFS = "acasa_updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private data class Release(val build: Int, val notes: String, val apkUrl: String)

    suspend fun check(activity: AppCompatActivity, manual: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MILLIS) {
            return
        }

        val release = fetchLatestRelease()
        if (release == null) {
            if (manual) {
                toast(activity, "Nu am putut verifica update-urile")
            }
            return
        }
        prefs.edit { putLong(KEY_LAST_CHECK, now) }

        val installed = BuildConfig.ACASA_BUILD
        if (release.build <= installed) {
            if (manual) {
                toast(activity, "Ai ultima versiune (build $installed)")
            }
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        val message = buildString {
            append("Versiunea nouă: build ${release.build}\n")
            append("Versiunea instalată: build $installed")
            if (release.notes.isNotBlank()) {
                append("\n\n")
                append(release.notes.trim())
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Update disponibil")
            .setMessage(message)
            .setNegativeButton("Mai târziu", null)
            .setPositiveButton("Instalează") { _, _ ->
                if (activity is MainActivity) {
                    activity.launchAcasaInstall(release.apkUrl)
                }
            }
            .show()
    }

    suspend fun downloadAndInstall(activity: AppCompatActivity, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast(activity, "Permite instalarea pentru Acasa, apoi apasă din nou Instalează")
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${activity.packageName}".toUri()
            )
            activity.startActivity(intent)
            return
        }

        toast(activity, "Se descarcă update-ul…")
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "Acasa.apk")
                client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                }
                target
            } catch (e: IOException) {
                Log.e(TAG, "Update download failed", e)
                null
            }
        }
        if (file == null) {
            toast(activity, "Descărcarea a eșuat")
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.acasaupdates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                response.body.string()
            }
            val json = JSONObject(body)
            val build = json.getString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            val notes = json.optString("body").lines()
                .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") }
                .joinToString("\n")
                .trim()
            apkUrl?.let { Release(build, notes, it) }
        } catch (e: IOException) {
            Log.d(TAG, "Update check failed", e)
            null
        } catch (e: JSONException) {
            Log.d(TAG, "Update check failed", e)
            null
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
