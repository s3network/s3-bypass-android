package com.darkbit.bypass

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    /**
     * The release LIST, not `/releases/latest`.
     *
     * `latest` excludes prereleases by definition, and every app release in
     * this repository is one. Measured against it, that endpoint answers with
     * `v0.1.0-android.1` — the only stable tag — whose sole asset is
     * `libxray.aar`, a library and not an installable app. The parser then
     * finds no `.apk` in it and returns null on every single check, forever:
     * the updater reports "up to date" while `v1.1.1` sits published and
     * visible, with nothing on screen to say why.
     *
     * Read as a list, the selection is ours to make — see [parseReleases].
     */
    const val RELEASES_URL =
        "https://api.github.com/repos/s3network/s3-bypass-android/releases?per_page=20"
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

    /** A release plus the date that only ever breaks a tie between equals. */
    private data class Stamped(
        val release: Release,
        val publishedAt: String,
    )

    /**
     * Every release worth offering, newest first.
     *
     * Three filters, each for a reason found in this repository's own data:
     *
     *  - **drafts** are never a published build;
     *  - **no `.apk`, not a candidate.** `libxray.aar` is the core library and
     *    ships under the `v0.1.0-android.*` tag line. Offering a library as an
     *    app update would be worse than reporting nothing at all;
     *  - **ordered by version, not by list position.** This repository carries
     *    two tag lines at once, so where a release sits in the response says
     *    nothing about how new it is. The published date breaks only ties.
     */
    fun parseReleases(body: String): List<Release> {
        val parsed = try {
            JSONArray(body)
        } catch (e: Exception) {
            // Expected whenever the tunnel is down mid-answer. An empty list is
            // the silent "no update known" path, never a crash.
            return emptyList()
        }

        val candidates = mutableListOf<Stamped>()
        for (i in 0 until parsed.length()) {
            val entry = parsed.optJSONObject(i) ?: continue
            if (entry.optBoolean("draft", false)) continue

            val tag = entry.optString("tag_name")
            if (tag.isBlank()) continue
            val version = tag.removePrefix("v")
            if (version.isBlank()) continue

            val asset = chooseApk(entry.optJSONArray("assets")) ?: continue

            candidates += Stamped(
                Release(
                    tag = tag,
                    version = version,
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = asset.optString("name"),
                    sizeBytes = asset.optLong("size", 0L),
                    prerelease = entry.optBoolean("prerelease", false),
                    pageUrl = entry.optString("html_url").ifBlank {
                        "https://github.com/s3network/s3-bypass-android/releases/latest"
                    },
                ),
                entry.optString("published_at"),
            )
        }

        return candidates.sortedWith(
            compareByDescending<Stamped> { it.release.version }
                .thenByDescending { it.publishedAt },
        ).map { it.release }
    }

    /** The newest release carrying an installable APK, or null when none is. */
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
            parseReleases(conn.inputStream.bufferedReader().use { it.readText() }).firstOrNull()
        } catch (e: Exception) {
            // Expected whenever the tunnel is down. Silent by design.
            Log.i(TAG, "release lookup failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The best APK in one release, in order of preference.
     *
     * arm64 first because that is what current phones run; universal installs
     * anywhere and is the fallback; armeabi-v7a is the last resort. A release
     * with no APK at all yields null — see [parseReleases].
     */
    private fun chooseApk(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        var chosen: JSONObject? = null
        for (i in 0 until assets.length()) {
            val candidate = assets.optJSONObject(i) ?: continue
            val name = candidate.optString("name")
            val rank = ASSET_PREFERENCE.indexOf(name)
            // Not in the preference list means not one of ours to offer.
            if (rank < 0) continue
            val currentRank =
                chosen?.let { ASSET_PREFERENCE.indexOf(it.optString("name")) } ?: -1
            if (chosen == null || rank < currentRank) {
                chosen = candidate
            }
        }
        return chosen
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