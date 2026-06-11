package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub Releases for a newer build and reports back the
 * tag, notes and the right APK URL for the running device's ABI.
 *
 * Uses the public, unauthenticated `releases/latest` endpoint. That endpoint
 * returns the most recent **non-prerelease** release; for a beta channel we
 * fall back to listing releases and taking the newest by published date so
 * pre-releases are still surfaced. Both calls are anonymous (60 req/h/IP),
 * which is plenty for a once-a-day check.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_API =
        "https://api.github.com/repos/davnozdu/supertonic-android/releases"
    private const val PREFS = "SupertonicPrefs"
    private const val KEY_LAST_CHECK = "update_last_check_ms"
    private const val KEY_SKIPPED_TAG = "update_skipped_tag"
    // One check per 24h is enough for a sideloaded app; avoids hammering the
    // anonymous GitHub rate limit when the user reopens the app repeatedly.
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Update(
        val tag: String,
        val name: String,
        val notes: String,
        val htmlUrl: String,
        /** Direct APK matching this device's primary ABI, or null if absent. */
        val apkUrl: String?,
    )

    /**
     * @param force ignore the 24h throttle and the "skip this version" flag
     *              (used by an explicit "Check for updates" menu action).
     */
    suspend fun check(context: Context, force: Boolean = false): Update? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0)
            if (now - last < CHECK_INTERVAL_MS) return@withContext null
        }

        val latest = try {
            fetchLatest()
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed: ${t.message}")
            return@withContext null
        }
        // Record the attempt only on success so a flaky network retries next
        // launch instead of going silent for 24h.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (latest == null) return@withContext null

        if (!isNewer(latest.tag, BuildConfig.VERSION_NAME)) return@withContext null
        if (!force && prefs.getString(KEY_SKIPPED_TAG, null) == latest.tag) return@withContext null

        latest
    }

    fun skipVersion(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIPPED_TAG, tag).apply()
    }

    private fun fetchLatest(): Update? {
        val conn = (URL(RELEASES_API + "?per_page=10").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(body)
            // Releases come newest-first; take the first that isn't a draft.
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                if (rel.optBoolean("draft", false)) continue
                return rel.toUpdate()
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.toUpdate(): Update {
        val tag = optString("tag_name")
        val assets = optJSONArray("assets")
        val abis = android.os.Build.SUPPORTED_ABIS
        var apkUrl: String? = null
        if (assets != null) {
            // Prefer the asset whose name contains this device's top ABI;
            // fall back to the universal APK; finally any .apk at all.
            var universal: String? = null
            var anyApk: String? = null
            outer@ for (abi in abis) {
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    if (!name.endsWith(".apk")) continue
                    val dl = a.optString("browser_download_url")
                    if (anyApk == null) anyApk = dl
                    if (name.contains("universal")) universal = dl
                    if (name.contains(abi)) { apkUrl = dl; break@outer }
                }
            }
            if (apkUrl == null) apkUrl = universal ?: anyApk
        }
        return Update(
            tag = tag,
            name = optString("name").ifEmpty { tag },
            notes = optString("body"),
            htmlUrl = optString("html_url"),
            apkUrl = apkUrl,
        )
    }

    /**
     * Semver-ish comparison that understands the project's `X.Y.Z-betaN`
     * scheme. A release with no pre-release suffix outranks the same X.Y.Z
     * with one (4.0.0 > 4.0.0-beta4 > 4.0.0-beta3). Leading "v" is ignored.
     */
    internal fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val r = parse(remoteTag) ?: return false
        val l = parse(localVersion) ?: return false
        for (k in 0 until 3) {
            if (r.nums[k] != l.nums[k]) return r.nums[k] > l.nums[k]
        }
        // Equal X.Y.Z — compare pre-release. No suffix = final = highest.
        if (r.pre == null && l.pre == null) return false
        if (r.pre == null) return true   // remote final > local pre-release
        if (l.pre == null) return false  // remote pre-release < local final
        return r.pre > l.pre
    }

    private data class Parsed(val nums: IntArray, val pre: Int?)

    private fun parse(version: String): Parsed? {
        val v = version.trim().removePrefix("v").removePrefix("V")
        val dash = v.indexOf('-')
        val core = if (dash >= 0) v.substring(0, dash) else v
        val suffix = if (dash >= 0) v.substring(dash + 1) else null
        val parts = core.split('.')
        if (parts.isEmpty()) return null
        val nums = IntArray(3)
        for (k in 0 until 3) {
            nums[k] = parts.getOrNull(k)?.toIntOrNull() ?: 0
        }
        // Extract the trailing integer of a "betaN"/"rcN" suffix; a suffix
        // with no number (plain "beta") sorts as 0.
        val pre = suffix?.let { s ->
            val digits = s.dropWhile { !it.isDigit() }
            if (digits.isEmpty()) 0 else digits.toIntOrNull() ?: 0
        }
        return Parsed(nums, pre)
    }
}
