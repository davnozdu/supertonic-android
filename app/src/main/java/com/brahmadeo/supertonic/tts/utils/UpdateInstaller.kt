package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads and verifies a release APK before handing it to Android's installer. */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"

    suspend fun download(context: Context, update: UpdateChecker.Update): File = withContext(Dispatchers.IO) {
        val address = requireNotNull(update.apkUrl) { "Release has no APK" }
        val expected = requireNotNull(update.apkSha256) { "Release APK has no SHA-256 digest" }
        require(URL(address).host == "github.com") { "Unexpected release host" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val name = update.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "Supertonic-$name.apk")
        if (target.exists() && sha256(target).equals(expected, ignoreCase = true)) {
            checkPackage(context, target)
            return@withContext target
        }
        val part = File(directory, "$name.part.apk")
        part.delete()
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == 200) { "APK download HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expected, ignoreCase = true)) { "APK checksum mismatch" }
            checkPackage(context, part)
            check(part.renameTo(target)) { "Could not save downloaded APK" }
            Log.i(TAG, "Verified release ${update.tag}")
            target
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun checkPackage(context: Context, file: File) {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        check(info?.packageName == context.packageName) { "Release APK package mismatch" }
    }
}
