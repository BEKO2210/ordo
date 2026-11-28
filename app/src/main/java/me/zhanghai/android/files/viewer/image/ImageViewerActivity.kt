/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.commit
import java8.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.putArgs

class ImageViewerActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The viewer is exported. Path extras name any file Ordo can read. Only a fresh launch
        // token from this process may carry them. Everyone else gets intent.data, and a private
        // file — file:// under the app data, including through /proc/self/root, or the same path
        // wrapped in our file_provider — closes. A later create keeps the list already on screen.
        if (savedInstanceState == null && !ImageViewerLaunches.acceptsPathExtras(intent)) {
            intent.removeExtra(EXTRA_PATH_URI)
            intent.removeExtra(EXTRA_PATH_URI_LIST)
            val packages = listOf(packageName, BuildConfig.APPLICATION_ID).distinct()
            if (externalImageUriRejected(intent.dataString, privateRoots(), packages)) {
                finish()
                return
            }
        }
        // Same as VaultActivity: screenshots and the recent-apps preview stay empty outside debug
        // builds. Debug builds leave the flag off so the screen can be checked over adb.
        if (shouldSecureViewer(BuildConfig.DEBUG, vaultUris(), packageName)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val intent = intent
            val position = intent.getIntExtra(EXTRA_POSITION, 0)
            val fragment = ImageViewerFragment()
                .putArgs(ImageViewerFragment.Args(intent, position))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isFinishing) ImageViewerLaunches.release(intent)
    }

    private fun privateRoots(): List<String> {
        // Paths from ApplicationInfo only: dataDir/filesDir/createDeviceProtectedStorageContext() touch the disk
        // on the main thread (StrictMode, ~200 ms on a cold Galaxy Tab S9 Ultra).
        val dataDir = applicationInfo.dataDir
        val roots = mutableListOf(dataDir, "$dataDir/files")
        applicationInfo.deviceProtectedDataDir?.let { roots.add(it) }
        // /data/data/<pkg> and /data/user/0/<pkg> are the same directory under different names.
        roots.add("/data/data/$packageName")
        roots.add("/data/user/0/$packageName")
        return roots
    }

    private fun vaultUris(): List<String?> = buildList {
        add(intent.dataString)
        runCatching { intent.extraPathList }.getOrNull()?.map { it.toString() }?.let { addAll(it) }
    }

    companion object {
        private val EXTRA_POSITION = "${ImageViewerActivity::class.java.name}.extra.POSITION"

        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            // All extra put here must be framework classes, or we may crash the resolver activity.
            intent.extraPathList = paths
            intent.putExtra(EXTRA_POSITION, position)
            // The token stays on an explicit viewer intent. An implicit VIEW or a chooser would
            // hand it to another app, which could then send private paths in EXTRA_PATH_URI_LIST.
            if (intent.component?.className == ImageViewerActivity::class.java.name) {
                ImageViewerLaunches.register(intent)
            }
        }
    }
}

private const val EXTRA_PATH_URI = "${BuildConfig.APPLICATION_ID}.extra.PATH_URI"
private const val EXTRA_PATH_URI_LIST = "${BuildConfig.APPLICATION_ID}.extra.PATH_URI_LIST"

/**
 * A token kept in this process. [ImageViewerActivity.putExtras] registers one only after the intent
 * targets this activity. It expires; a foreign app cannot guess it, and after the process is gone
 * it is void.
 */
internal object ImageViewerLaunches {
    private const val EXTRA_INTERNAL_TOKEN =
        "me.zhanghai.android.files.viewer.image.extra.INTERNAL_TOKEN"

    private val tokens = ConcurrentHashMap<String, Long>()

    fun register(intent: Intent, nowMillis: Long = System.currentTimeMillis()) {
        evictExpired(nowMillis)
        val token = UUID.randomUUID().toString()
        tokens[token] = nowMillis + IMAGE_VIEWER_LAUNCH_TOKEN_TTL_MILLIS
        intent.putExtra(EXTRA_INTERNAL_TOKEN, token)
    }

    fun acceptsPathExtras(intent: Intent, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val token = intent.getStringExtra(EXTRA_INTERNAL_TOKEN) ?: return false
        val expiresAt = tokens[token]
        if (!launchTokenValid(expiresAt, nowMillis)) {
            tokens.remove(token)
            return false
        }
        return true
    }

    fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_INTERNAL_TOKEN)?.let(tokens::remove)
    }

    private fun evictExpired(nowMillis: Long) {
        tokens.filterValues { it < nowMillis }.keys.forEach(tokens::remove)
    }
}

/** Long enough for startActivity to reach [ImageViewerActivity.onCreate], not until process death. */
internal const val IMAGE_VIEWER_LAUNCH_TOKEN_TTL_MILLIS = 30_000L

internal fun launchTokenValid(expiresAtMillis: Long?, nowMillis: Long): Boolean =
    expiresAtMillis != null && nowMillis <= expiresAtMillis

/** Same two minutes as the vault screen keeps a viewer open ([me.zhanghai.android.files.vault.VaultViewModel]). */
internal const val VAULT_VIEWER_TIMEOUT_MILLIS = 2 * 60 * 1000L

/** `adb shell setprop debug.ordo.vault.viewer_timeout_ms 1000` on a debug build. Ignored in release. */
internal const val DEBUG_VAULT_VIEWER_TIMEOUT_PROPERTY = "debug.ordo.vault.viewer_timeout_ms"

internal fun shouldSecureViewer(
    debuggable: Boolean,
    uris: Iterable<String?>,
    packageName: String
): Boolean = !debuggable && uris.any { isVaultContentUri(it, packageName) }

internal fun isVaultContentUri(uri: String?, packageName: String): Boolean {
    if (uri.isNullOrEmpty()) return false
    return uri.startsWith("content://$packageName.vault/")
}

internal fun vaultViewerTimedOut(stoppedAtElapsed: Long, nowElapsed: Long, timeoutMillis: Long): Boolean =
    stoppedAtElapsed != 0L && nowElapsed - stoppedAtElapsed > timeoutMillis

/** Saved with the viewer so a config change in the background does not drop the leave time. */
internal const val STATE_VAULT_STOPPED_AT = "vault_stopped_at"

/**
 * Elapsed realtime to keep when the viewer stops. 0 means it is in front.
 * A rotation does not start the clock. A later stop that never came to the front keeps the
 * original leave time, so recreating the screen in the background does not start the two minutes over.
 */
internal fun vaultLeaveOnStop(
    isVault: Boolean,
    finishing: Boolean,
    changingConfigurations: Boolean,
    inFront: Boolean,
    stoppedAt: Long,
    now: Long
): Long {
    if (!isVault || finishing || changingConfigurations) return stoppedAt
    if (!inFront && stoppedAt != 0L) return stoppedAt
    return now
}

internal fun vaultViewerTimeoutMillis(debuggable: Boolean, overrideMs: Long?): Long =
    if (debuggable && overrideMs != null && overrideMs >= 0) overrideMs else VAULT_VIEWER_TIMEOUT_MILLIS

internal fun debugVaultTimeoutOverride(): Long? {
    if (!BuildConfig.DEBUG) return null
    val raw = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, DEBUG_VAULT_VIEWER_TIMEOUT_PROPERTY) as String
    }.getOrNull()
    return raw?.toLongOrNull()
}

/**
 * True when a launch without our token must be closed: a `file://` URI under the app data
 * (symlinks resolved), or our `file_provider` wrapping such a file.
 */
internal fun externalImageUriRejected(
    uri: String?,
    privateRoots: List<String>,
    packageNames: List<String>
): Boolean =
    fileUriTargetsAppData(uri, privateRoots) ||
        ownFileProviderExposesAppData(uri, packageNames, privateRoots)

/** True when a `file://` URI points at [privateRoots] (the app's own data directory or one of its names). */
internal fun fileUriTargetsAppData(uri: String?, privateRoots: List<String>): Boolean {
    val path = fileUriPath(uri) ?: return false
    val normalized = canonicalizeAbsolutePath(path)
    return privateRoots.any { root ->
        val base = canonicalizeAbsolutePath(root)
        normalized == base || normalized.startsWith("$base/")
    }
}

/**
 * `content://<pkg>.file_provider/…` is not exported, but this activity opens it with Ordo's rights.
 * The path inside is the encoded file URI ([me.zhanghai.android.files.file.fileProviderUri]).
 */
internal fun ownFileProviderExposesAppData(
    uri: String?,
    packageNames: List<String>,
    privateRoots: List<String>
): Boolean {
    val host = contentUriHost(uri) ?: return false
    if (packageNames.none { host.equals("$it.file_provider", ignoreCase = true) }) return false
    val embedded = fileProviderEmbeddedUri(uri!!) ?: return false
    if (fileUriTargetsAppData(embedded, privateRoots)) return true
    if (!embedded.contains(':') && embedded.startsWith("/")) {
        return fileUriTargetsAppData("file://$embedded", privateRoots)
    }
    return false
}

internal fun fileUriPath(uri: String?): String? {
    if (uri.isNullOrEmpty()) return null
    val schemeEnd = uri.indexOf(':')
    if (schemeEnd <= 0) return null
    if (!uri.substring(0, schemeEnd).equals("file", ignoreCase = true)) return null
    var rest = uri.substring(schemeEnd + 1)
    if (rest.startsWith("//")) {
        rest = rest.substring(2)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        rest = rest.substring(slash)
    }
    val cut = rest.indexOfAny(charArrayOf('?', '#'))
    if (cut >= 0) rest = rest.substring(0, cut)
    return decodePercent(rest)
}

internal fun normalizeAbsolutePath(path: String): String {
    val parts = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return "/" + parts.joinToString("/")
}

/** `..` and `.` collapsed, then symlinks, including `/proc/self/root` when the OS does not resolve it. */
internal fun canonicalizeAbsolutePath(path: String): String {
    val normalized = stripProcRoot(normalizeAbsolutePath(path))
    val resolved = try {
        java.io.File(normalized).canonicalPath
    } catch (_: Exception) {
        normalized
    }
    return stripProcRoot(normalizeAbsolutePath(resolved))
}

/** `/proc/self/root`, `/proc/thread-self/root` and `/proc/<pid>/root` are symlinks to `/`. */
internal fun stripProcRoot(path: String): String {
    var current = path
    while (true) {
        val rest = procRootRemainder(current) ?: return current.ifEmpty { "/" }
        current = rest
    }
}

private fun procRootRemainder(path: String): String? {
    if (!path.startsWith("/proc/")) return null
    val body = path.substring("/proc/".length)
    val slash = body.indexOf('/')
    if (slash <= 0) return null
    val name = body.substring(0, slash)
    val procName = name == "self" || name == "thread-self" || name.all { it.isDigit() }
    if (!procName) return null
    val afterName = body.substring(slash)
    if (afterName == "/root") return "/"
    if (!afterName.startsWith("/root/")) return null
    return afterName.removePrefix("/root")
}

internal fun contentUriHost(uri: String?): String? {
    if (uri.isNullOrEmpty()) return null
    val schemeEnd = uri.indexOf(':')
    if (schemeEnd <= 0) return null
    if (!uri.regionMatches(0, "content", 0, schemeEnd, ignoreCase = true)) return null
    var rest = uri.substring(schemeEnd + 1)
    if (!rest.startsWith("//")) return null
    rest = rest.substring(2)
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest.substringBefore('?').substringBefore('#')
    if (authority.isEmpty()) return null
    val host = authority.substringAfterLast('@').substringBefore(':')
    return host.takeIf { it.isNotEmpty() }
}

/** The file or path URI stored in a `content://…file_provider/<encoded>` URI, or null. */
internal fun fileProviderEmbeddedUri(uri: String): String? {
    val schemeEnd = uri.indexOf(':')
    if (schemeEnd < 0) return null
    var rest = uri.substring(schemeEnd + 1)
    if (!rest.startsWith("//")) return null
    rest = rest.substring(2)
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    var path = rest.substring(slash + 1)
    val cut = path.indexOfAny(charArrayOf('?', '#'))
    if (cut >= 0) path = path.substring(0, cut)
    if (path.isEmpty()) return null
    var decoded = decodePercent(path)
    if (decoded.startsWith("/")) decoded = decoded.substring(1)
    return decoded.takeIf { it.isNotEmpty() }
}

private fun decodePercent(value: String): String {
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                index += 3
                continue
            }
        }
        out.append(char)
        index++
    }
    return out.toString()
}
