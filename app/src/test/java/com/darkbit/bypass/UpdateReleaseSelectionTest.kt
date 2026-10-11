package com.darkbit.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which release the updater actually looks at.
 *
 * The comparison in [UpdateChecker.isNewer] was already right, but it was
 * applied to whatever `GET /releases/latest` happened to return — and that
 * endpoint excludes prereleases. Every app release here is a prerelease, so
 * the endpoint answered with `v0.1.0-android.1`, the only stable tag, whose
 * sole asset is `libxray.aar` — a library, not an installable app.
 *
 * The result was not a wrong version, it was *no version*: no `.apk` in that
 * release means `chosen = null` and the checker returned null on every single
 * check, forever. `v1.1.1` sat published and visible while the app reported
 * "up to date" — silently, with no error anywhere to explain it.
 *
 * So the checker reads the release *list* and selects by version, and a
 * release without an APK is not a candidate at all: `libxray.aar` is the
 * core library, it ships through a different tag line, and offering it as
 * "the update" would have been worse than reporting nothing.
 *
 * The bodies below are the repository's real release payloads, trimmed of
 * fields the parser ignores.
 */
class UpdateReleaseSelectionTest {

    /** `v1.1.1` — a prerelease carrying the three APK variants. */
    private val v111 = """
        {
          "tag_name": "v1.1.1",
          "html_url": "https://github.com/s3network/s3-bypass-android/releases/tag/v1.1.1",
          "prerelease": true,
          "draft": false,
          "published_at": "2026-10-11T07:45:41Z",
          "assets": [
            {"name": "app-arm64-v8a-release.apk",
             "browser_download_url": "https://example.invalid/v1.1.1/app-arm64-v8a-release.apk",
             "size": 41056013},
            {"name": "app-armeabi-v7a-release.apk",
             "browser_download_url": "https://example.invalid/v1.1.1/app-armeabi-v7a-release.apk",
             "size": 28002102},
            {"name": "app-universal-release.apk",
             "browser_download_url": "https://example.invalid/v1.1.1/app-universal-release.apk",
             "size": 121000000}
          ]
        }
    """.trimIndent()

    /** `v0.1.0-android.1` — the only stable tag, and an AAR-only release. */
    private val aarOnly = """
        {
          "tag_name": "v0.1.0-android.1",
          "html_url": "https://github.com/s3network/s3-bypass-android/releases/tag/v0.1.0-android.1",
          "prerelease": false,
          "draft": false,
          "published_at": "2026-09-01T00:00:00Z",
          "assets": [
            {"name": "libxray.aar",
             "browser_download_url": "https://example.invalid/v0.1.0-android.1/libxray.aar",
             "size": 9000000}
          ]
        }
    """.trimIndent()

    @Test
    fun `the app release is found even though every release is a prerelease`() {
        val picked = UpdateChecker.parseReleases("[$v111,$aarOnly]").firstOrNull()

        assertNotNull("the only release carrying an APK must be offered", picked)
        assertEquals("1.1.1", picked!!.version)
        assertEquals("app-arm64-v8a-release.apk", picked.apkName)
    }

    @Test
    fun `a release carrying only the library is not a candidate`() {
        // libxray.aar is the core, published under a different tag line. It is
        // not installable and must never be offered as an app update.
        val picked = UpdateChecker.parseReleases("[$aarOnly]")

        assertTrue("an AAR-only release is not an app update", picked.isEmpty())
    }

    @Test
    fun `the arm64 apk is preferred over the universal and the armeabi one`() {
        val picked = UpdateChecker.parseReleases("[$v111]").first()

        assertEquals("app-arm64-v8a-release.apk", picked.apkName)
        assertEquals(41056013L, picked.sizeBytes)
    }

    @Test
    fun `drafts are never offered`() {
        val draft = v111.replace("\"v1.1.1\"", "\"v9.9.9\"").replace("\"draft\": false", "\"draft\": true")

        assertTrue(UpdateChecker.parseReleases("[$draft]").isEmpty())
    }

    @Test
    fun `selection is by version, not by the order the list came in`() {
        // A newer-by-date release that is older by version must not win.
        val older = v111.replace("\"v1.1.1\"", "\"v1.0.9\"")
            .replace("\"2026-10-11T07:45:41Z\"", "\"2026-12-31T00:00:00Z\"")

        val picked = UpdateChecker.parseReleases("[$older,$v111]").first()

        assertEquals("1.1.1", picked.version)
    }

    @Test
    fun `a malformed body yields nothing rather than throwing`() {
        // Expected whenever the tunnel is down mid-answer. The caller turns an
        // empty list into "no update known", which is the silent path.
        assertTrue(UpdateChecker.parseReleases("not json at all").isEmpty())
        assertTrue(UpdateChecker.parseReleases("{}").isEmpty())
        assertTrue(UpdateChecker.parseReleases("[]").isEmpty())
        assertTrue(UpdateChecker.parseReleases("[{\"no_tag\":true}]").isEmpty())
    }

    @Test
    fun `the release list is queried, not the latest endpoint`() {
        // The pin that keeps this from regressing: /releases/latest 404s on a
        // repository whose releases are all prereleases, and here it would have
        // answered with the AAR-only stable tag even if it had not.
        assertTrue(
            "must read the release list",
            UpdateChecker.RELEASES_URL.contains("/releases?"),
        )
        assertTrue(
            "must not read the latest endpoint",
            !UpdateChecker.RELEASES_URL.contains("/releases/latest"),
        )
    }

    @Test
    fun `an unknown version still reports nothing instead of inventing an update`() {
        val picked = UpdateChecker.parseReleases("[$aarOnly]").firstOrNull()

        assertNull("an AAR-only repository has no app update to offer", picked)
    }
}