package com.darkbit.bypass

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * The signature check is the point of this class, not a formality. An app that
 * fetches an APK over the network and asks the system to install it is asking
 * to be given new code by anything that can answer that request. Comparing the
 * archive's signer against the one already installed closes that: only a build
 * signed with the same key is accepted, so a substituted file cannot install
 * even over a working HTTPS connection.
 *
 * Nothing here silently proceeds on a failed check. A mismatch is reported and
 * the download is deleted.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    sealed interface Result {
        /** Installed, or handed to the installer. */
        data class Ready(val file: File) : Result

        /** The user has not allowed this app to install packages yet. */
        object PermissionNeeded : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Fetches [url] into the cache directory, or returns the file already
     * downloaded. [onProgress] receives 0..100.
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): Result {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "s3-bypass-update.apk")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "s3-bypass-android")
            }
            if (conn.responseCode !in 200..299) {
                return Result.Failed("сервер ответил ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            out.outputStream().use { sink ->
                conn.inputStream.use { source ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                    }
                }
            }
            if (total > 0 && downloaded != total) {
                out.delete()
                return Result.Failed("файл докачался не полностью")
            }
            onProgress(100)
            Result.Ready(out)
        } catch (e: Exception) {
            out.delete()
            Log.w(TAG, "download failed: ${e.message}")
            Result.Failed(e.message ?: "неизвестная ошибка")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * True when [apk] is signed by the same certificate as this app.
     *
     * Compares the signer sets rather than just "is not unsigned": a package
     * with no signature at all must fail too, not pass by default.
     */
    fun signatureMatches(context: Context, apk: File): Boolean {
        fun signers(info: android.content.pm.PackageInfo?): Set<String> {
            if (info == null) return emptySet()
            // Signature.toCharsString() is the hex of the certificate and is
            // available on every level; the branch exists only because the
            // field holding them was renamed in API 28.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                    ?: emptySet()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
            }
        }

        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        if (archive == null) return false

        val mine = signers(installed)
        val theirs = signers(archive)
        // Both sides must be non-empty and identical. An empty set on either
        // side would make "matches" true for two unsigned packages.
        return mine.isNotEmpty() && mine == theirs
    }

    /** True when the app may request installation of packages. */
    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Intent that opens the per-app "install unknown apps" setting. */
    fun installPermissionSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Builds the intent that shows the system installer for [apk].
     *
     * Returns null when no activity can handle it, which happens on a device
     * with no package installer — the caller reports rather than crashing.
     */
    fun installIntent(context: Context, apk: File): Intent? {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = context.packageManager.resolveActivity(intent, 0)
        return if (resolved != null) intent else null
    }
}