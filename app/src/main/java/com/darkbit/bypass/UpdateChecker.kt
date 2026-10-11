package com.darkbit.bypass

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer build of the app exists.
 *
 * Why GitHub rather than the Play in-app update API: that API only works for
 * apps installed from Google Play. This one is sideloaded from a release page,
 * so the Play path returns nothing at all and the user would be left hunting
 * for a download by hand.
 *
 * The check runs on [Dispatchers.IO] with a short timeout and every failure is
 * silent by design. The app is a VPN client: when its own tunnel is broken,
 * the request may never complete, and an updater that blocks the interface or
 * throws a dialog at that moment makes the one thing the user needs worse.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL =
        "https://api.github.com/repos/s3network/s3-bypass-android/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    /** Which asset to offer, in order of preference. */
    private val ASSET_PREFERENCE = listOf(
        "app-arm64-v8a-release.apk",
        "app-universal-release.apk",
        "app-armeabi-v7a-release.apk",
    )

    data class Release(
        val tag: String,
        val version: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long,
        val prerelease: Boolean,
        val pageUrl: String,
    )

    suspend fun checkLatest(): Release? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub rejects API requests without a User-Agent.
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "s3-bypass-android")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 here is almost always the unauthenticated rate limit
                // (60/hour per address). Not worth interrupting the user for.
                Log.w(TAG, "release lookup returned $code")
                return@withContext null
            }
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            // Expected whenever the tunnel is down. Silent by design.
            Log.i(TAG, "release lookup failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): Release? {
        val root = JSONObject(body)
        val tag = root.optString("tag_name").ifBlank { return null }
        val version = tag.removePrefix("v").ifBlank { return null }

        var chosen: JSONObject? = null
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (!name.endsWith(".apk")) continue
                if (chosen == null || ASSET_PREFERENCE.indexOf(name) in 0 until
                    ASSET_PREFERENCE.indexOf(chosen.optString("name"))) {
                    chosen = a
                }
            }
        }
        val asset = chosen ?: return null

        return Release(
            tag = tag,
            version = version,
            apkUrl = asset.optString("browser_download_url"),
            apkName = asset.optString("name"),
            sizeBytes = asset.optLong("size", 0L),
            prerelease = root.optBoolean("prerelease", false),
            pageUrl = root.optString("html_url").ifBlank {
                "https://github.com/s3network/s3-bypass-android/releases/latest"
            },
        )
    }

    /**
     * Compares dotted version strings component by component, numerically.
     *
     * String comparison gets this wrong at the exact moment it matters:
     * "1.1.9" > "1.1.10" lexicographically, so an update would never be offered
     * and a stale build would look current forever. Each component is parsed
     * as a number, and any non-numeric tail is ignored.
     */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    fun compare(a: String, b: String): Int {
        // Strip a leading "v" on both sides. parse() already removes it from
        // the tag, so this is belt and braces — but without it a stray "v"
        // becomes a component that parses as 0, silently downgrading "v1.2.0"
        // to "0.2.0" and hiding an update behind a wrong answer.
        val pa = a.removePrefix("v").split('.')
        val pb = b.removePrefix("v").split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            // "1.2.3-beta" → 3; anything unparseable counts as 0 rather than
            // failing, so a malformed tag cannot make an update look newer.
            val va = pa.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val vb = pb.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}