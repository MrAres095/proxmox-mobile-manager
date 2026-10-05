package com.ares.proxmoxmobilemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val versionName: String, val downloadUrl: String, val releaseUrl: String)

class UpdateManager(private val context: Context) {
    private val repoApi = "https://api.github.com/repos/MrAres095/proxmox-mobile-manager/releases/latest"

    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(repoApi).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 7000; requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Proxmox-Mobile-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)
            val version = release.optString("tag_name").removePrefix("v").trim()
            val releaseUrl = release.optString("html_url")
            val assets = release.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url"); break
                }
            }
            val url = apkUrl ?: return@withContext null
            if (!isNewer(version, currentVersionName())) return@withContext null
            AppUpdate(version, url, releaseUrl)
        } finally { connection.disconnect() }
    }

    suspend fun downloadAndInstall(update: AppUpdate) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "proxmox-mobile-manager-${'$'}{update.versionName}.apk")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 30000; requestMethod = "GET"
            setRequestProperty("User-Agent", "Proxmox-Mobile-Manager")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Preuzimanje ažuriranja nije uspjelo (HTTP ${'$'}{connection.responseCode}).")
            connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
        } finally { connection.disconnect() }
        withContext(Dispatchers.Main) { installApk(apk) }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${'$'}{context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun canInstallUnknownApps(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun installApk(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${'$'}{context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun currentVersionName(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(value: String) = value.split(".", "-", "_").map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local); val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val av = a.getOrElse(i) { 0 }; val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }
}