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

        // The viewer is exported. Path extras name any file Ordo can read, and a file:// URI under
        // the app's own data directory would open it with Ordo's rights. Only a launch from this
        // process may carry those extras; everyone else gets intent.data, and a private file URI closes.
        if (!ImageViewerLaunches.acceptsPathExtras(intent)) {
            intent.removeExtra(EXTRA_PATH_URI)
            intent.removeExtra(EXTRA_PATH_URI_LIST)
            if (fileUriTargetsAppData(intent.dataString, privateRoots())) {
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
        val roots = mutableListOf(dataDir.path, filesDir.path)
        createDeviceProtectedStorageContext().dataDir?.path?.let { roots.add(it) }
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
            ImageViewerLaunches.register(intent)
        }
    }
}

private const val EXTRA_PATH_URI = "${BuildConfig.APPLICATION_ID}.extra.PATH_URI"
private const val EXTRA_PATH_URI_LIST = "${BuildConfig.APPLICATION_ID}.extra.PATH_URI_LIST"

/**
 * A token kept in this process. [ImageViewerActivity.putExtras] registers one; a foreign app cannot
 * guess it, and after the process is gone it is void, same as the AI show list.
 */
internal object ImageViewerLaunches {
    private const val EXTRA_INTERNAL_TOKEN =
        "me.zhanghai.android.files.viewer.image.extra.INTERNAL_TOKEN"

    private val tokens = ConcurrentHashMap.newKeySet<String>()

    fun register(intent: Intent) {
        val token = UUID.randomUUID().toString()
        tokens.add(token)
        intent.putExtra(EXTRA_INTERNAL_TOKEN, token)
    }

    fun acceptsPathExtras(intent: Intent): Boolean =
        intent.getStringExtra(EXTRA_INTERNAL_TOKEN)?.let(tokens::contains) == true

    fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_INTERNAL_TOKEN)?.let(tokens::remove)
    }
}

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

/** True when a `file://` URI points at [privateRoots] (the app's own data directory or one of its names). */
internal fun fileUriTargetsAppData(uri: String?, privateRoots: List<String>): Boolean {
    val path = fileUriPath(uri) ?: return false
    val normalized = normalizeAbsolutePath(path)
    return privateRoots.any { root ->
        val base = normalizeAbsolutePath(root)
        normalized == base || normalized.startsWith("$base/")
    }
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
