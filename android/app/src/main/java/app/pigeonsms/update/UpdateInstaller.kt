package app.pigeonsms.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import app.pigeonsms.network.ReleaseDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK to the cache dir (exposed via the app FileProvider) and
 * hands it to the system package installer. On Android 8+ we must hold the user's
 * "install unknown apps" consent — if we don't, we bounce them to that settings
 * screen instead of firing an intent that would silently fail.
 *
 * Fire-and-forget: [downloadAndInstall] launches its own coroutine so callers can
 * invoke it straight from an onClick. Progress is surfaced via the optional
 * [onProgress] callback (0f..1f, or null when the total size is unknown).
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Kick off download + install for [release]. Runs off the main thread. The
     * [onProgress] callback (if given) is invoked on the download thread with the
     * fraction complete, or null while the total length is unknown.
     */
    fun downloadAndInstall(
        context: Context,
        release: ReleaseDto,
        onProgress: ((Float?) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val apk = download(appContext, release.url, onProgress)
                launchInstaller(appContext, apk)
            } catch (e: Exception) {
                Log.e(TAG, "update download/install failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "couldn't download the update", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Streams [url] into cache/updates/, reporting progress. Returns the written file. */
    private fun download(context: Context, url: String, onProgress: ((Float?) -> Unit)?): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Fresh file each time; stale partials from a killed download are harmless but wasteful.
        val out = File(dir, "pigeonsms-update.apk")
        if (out.exists()) out.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("download http ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            onProgress?.invoke(if (total > 0) 0f else null)
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress?.invoke((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }
            onProgress?.invoke(1f)
        } finally {
            conn.disconnect()
        }
        return out
    }

    /**
     * Fire the package-installer intent for [apk] via the FileProvider. On O+ we
     * first check [canInstall]; if the user hasn't granted "install unknown apps"
     * we route them to that settings prompt (scoped to our package) and bail — the
     * caller can retry once permission is granted.
     */
    private suspend fun launchInstaller(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstall(context)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "allow installing apps, then tap update again", Toast.LENGTH_LONG).show()
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(settings) }
            }
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            runCatching { context.startActivity(install) }.onFailure {
                Log.e(TAG, "install intent failed", it)
                Toast.makeText(context, "couldn't open the installer", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Whether the app may install packages (always true pre-O). */
    private fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
}
