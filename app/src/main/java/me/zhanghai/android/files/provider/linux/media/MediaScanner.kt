/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux.media

import android.content.Intent
import android.media.MediaScannerConnection
import android.mtp.MtpConstants
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat
import java8.nio.channels.FileChannel
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.app.storageManager
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.storageVolumesCompat
import me.zhanghai.android.files.hiddenapi.RestrictedHiddenApi
import me.zhanghai.android.files.provider.common.DelegateFileChannel
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.util.lazyReflectedMethod
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/*
 * @see com.android.internal.content.FileSystemProvider
 * @see com.android.providers.media.scan.ModernMediaScanner.java
 */
object MediaScanner {
    private const val TAG = "MediaScanner"
    // MediaStore.SCAN_FILE_CALL (hidden): the call MediaScannerConnection makes for each path.
    private const val SCAN_FILE_CALL = "scan_file"

    private val scanExecutor = Executors.newSingleThreadExecutor { Thread(it, "MediaScan") }

    /**
     * Asks MediaStore to read [paths] again (a missing one drops its row); [onScanned] runs on a background thread.
     * What MediaScannerConnection.scanFile does on Android 11+, but on Ordo's own thread: there, an exception from
     * releasing the media provider ("stableCount < 0", Galaxy A15, Android 16) killed the app; here it is caught.
     */
    fun scanPaths(paths: List<String>, onScanned: ((String, Uri?) -> Unit)? = null) {
        // Android 10's provider wants the file as a URI in the extras; the path argument came with Android 11.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            MediaScannerConnection.scanFile(application, paths.toTypedArray(), null) { path, uri ->
                onScanned?.invoke(path, uri)
            }
            return
        }
        scanExecutor.execute {
            val volumeRoots = try {
                storageManager.storageVolumesCompat.mapNotNull { it.directoryCompat?.path }
            } catch (e: Exception) {
                Log.w(TAG, "Listing storage volumes failed", e)
                emptyList()
            }
            for (path in paths) {
                // The provider is privileged; MediaScannerConnection used to vet the path, now we have to.
                val scanPath = scannablePath(path, volumeRoots)
                if (scanPath == null) {
                    Log.w(TAG, "Not scanning $path: outside mounted storage volumes")
                    onScanned?.invoke(path, null)
                    continue
                }
                val uri = try {
                    contentResolver.call(MediaStore.AUTHORITY, SCAN_FILE_CALL, scanPath, null)
                        ?.let { BundleCompat.getParcelable(it, Intent.EXTRA_STREAM, Uri::class.java) }
                } catch (e: Exception) {
                    Log.w(TAG, "Scanning $path failed", e)
                    null
                }
                onScanned?.invoke(path, uri)
            }
        }
    }

    /**
     * The canonical form of [path] if it lies inside one of [volumeRoots] (mounted storage volume directories),
     * else null: `..`, symlinks out of the volume, app-internal data, /data, /proc and other roots are refused.
     */
    internal fun scannablePath(path: String, volumeRoots: List<String>): String? {
        val canonicalPath = try {
            File(path).canonicalPath
        } catch (e: IOException) {
            return null
        }
        return canonicalPath.takeIf { candidate ->
            volumeRoots.any { root ->
                val canonicalRoot = try {
                    File(root).canonicalPath
                } catch (e: IOException) {
                    return@any false
                }
                candidate == canonicalRoot ||
                    candidate.startsWith(canonicalRoot + File.separator)
            }
        }
    }

    fun scan(file: File, isDeleted: Boolean = false) {
        if (isRunningAsRoot) {
            return
        }
        scanPaths(listOf(file.path)) { _, _ ->
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && isDeleted) {
                // ModernMediaScanner has a bug on Android 10 that may prevent it from removing
                // certain files after their deletion. This has been fixed on Android 11 by
                // https://android.googlesource.com/platform/packages/providers/MediaProvider/+/637d133d90f49dd18bda5de219184bfa9d6c2deb
                // , but we still have to work around it for Android 10 by always trying to delete
                // the MediaStore entry ourselves.
                deleteMediaStoreEntryAsync(file)
            }
        }
    }

    @get:RequiresApi(Build.VERSION_CODES.Q)
    private val deleteMediaStoreEntryHandler by lazy {
        val thread = HandlerThread("DeleteMediaStoreEntry")
        thread.start()
        Handler(thread.looper)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteMediaStoreEntryAsync(file: File) {
        deleteMediaStoreEntryHandler.post {
            try {
                deleteMediaStoreEntrySync(file)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @RestrictedHiddenApi
    @get:RequiresApi(Build.VERSION_CODES.Q)
    private val mediaStoreGetVolumeName by lazyReflectedMethod(
        MediaStore::class.java, "getVolumeName", File::class.java
    )

    // @see com.android.providers.media.scan.ModernMediaScanner.reconcileAndClean
    // @see https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android10-release/src/com/android/providers/media/scan/ModernMediaScanner.java
    // @see https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android11-release/src/com/android/providers/media/scan/ModernMediaScanner.java
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteMediaStoreEntrySync(file: File) {
        val file = file.canonicalFile
        val volumeName = mediaStoreGetVolumeName.invoke(null, file) as String
        val uri = MediaStore.Files.getContentUri(volumeName)
            .buildUpon()
            .appendQueryParameter("includePending", "1")
            .appendQueryParameter("deletedata", "false")
            .build()
        @Suppress("DEPRECATION")
        val where = "ifnull(format, ${MtpConstants.FORMAT_UNDEFINED}) != ${
            MtpConstants.FORMAT_ABSTRACT_AV_PLAYLIST} AND ${MediaStore.Files.FileColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        contentResolver.delete(uri, where, selectionArgs)
    }

    fun createScanOnCloseFileChannel(fileChannel: FileChannel, file: File): FileChannel =
        if (isRunningAsRoot) {
            fileChannel
        } else {
            object : DelegateFileChannel(fileChannel) {
                @Throws(IOException::class)
                override fun implCloseChannel() {
                    super.implCloseChannel()

                    scan(file)
                }
            }
        }
}
