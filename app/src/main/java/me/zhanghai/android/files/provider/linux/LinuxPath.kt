/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.app.AppOpsManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import kotlin.concurrent.Volatile
import me.zhanghai.android.files.app.appOpsManager
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.AppOpsManagerCompat
import me.zhanghai.android.files.compat.checkOpRawNoThrowCompat
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.opPackageNameCompat
import me.zhanghai.android.files.compat.pathFileCompat
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.root.RootablePath
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.util.readParcelable
import me.zhanghai.android.files.util.valueCompat

internal class LinuxPath : ByteStringListPath<LinuxPath>, RootablePath {
    private val fileSystem: LinuxFileSystem

    constructor(fileSystem: LinuxFileSystem, path: ByteString) : super(
        LinuxFileSystem.SEPARATOR, path
    ) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: LinuxFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(LinuxFileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == LinuxFileSystem.SEPARATOR

    override fun createPath(path: ByteString): LinuxPath = LinuxPath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): LinuxPath =
        LinuxPath(fileSystem, absolute, segments)

    override val defaultDirectory: LinuxPath
        get() = fileSystem.defaultDirectory

    override fun getFileSystem(): LinuxFileSystem = fileSystem

    override fun getRoot(): LinuxPath? = if (isAbsolute) fileSystem.rootDirectory else null

    @Throws(IOException::class)
    override fun toRealPath(vararg options: LinkOption): LinuxPath {
        throw UnsupportedOperationException()
    }

    override fun toFile(): File = File(toString())

    @Throws(IOException::class)
    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey {
        if (watcher !is LocalLinuxWatchService) {
            throw ProviderMismatchException(watcher.toString())
        }
        return watcher.register(this, events, *modifiers)
    }

    // Ordo: judged by where the path leads (see rootCheck), so /sdcard (a symlink to the primary volume) needs no root,
    // and no "..", or symlink a user can write into a volume, makes root reach for something else.
    override fun isRootRequired(isAttributeAccess: Boolean, noFollowLinks: Boolean): Boolean =
        rootCheck(toFile(), noFollowLinks, { isRootRequired(it, isAttributeAccess) }) == RootCheck.ROOT

    override fun followsSharedSymlink(noFollowLinks: Boolean): Boolean =
        rootCheck(toFile(), noFollowLinks, { isRootRequired(it, false) }) == RootCheck.SHARED_SYMLINK

    private fun isRootRequired(file: File, isAttributeAccess: Boolean): Boolean {
        // Ordo: the app always reaches its own data, e.g. files opened from the vault.
        if (file.startsWith(application.dataDir) || file.startsWith(canonicalDataDir)) {
            return false
        }
        return StorageVolumeListLiveData.valueCompat.none {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !it.isPrimaryCompat) {
                return@none false
            }
            val storageVolumeDirectory = it.pathFileCompat
            if (!file.startsWith(storageVolumeDirectory)) {
                return@none false
            }
            return@none file.isAccessibleInStorageVolume(storageVolumeDirectory, isAttributeAccess)
        }
    }

    private fun File.isAccessibleInStorageVolume(
        storageVolumeDirectory: File,
        isAttributeAccess: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val parentDirectory = parentFile
            val androidDataDirectory = storageVolumeDirectory.resolve(FILE_ANDROID_DATA)
            val isInAndroidDataDirectory = if (isAttributeAccess && parentDirectory != null) {
                parentDirectory.startsWith(androidDataDirectory)
            } else {
                startsWith(androidDataDirectory)
            }
            val appPackageName = application.packageName
            if (isInAndroidDataDirectory) {
                val appDataDirectory = androidDataDirectory.resolve(appPackageName)
                return startsWith(appDataDirectory)
            }
            val androidObbDirectory = storageVolumeDirectory.resolve(FILE_ANDROID_OBB)
            val isInAndroidObbDirectory = if (isAttributeAccess && parentDirectory != null) {
                parentDirectory.startsWith(androidObbDirectory)
            } else {
                startsWith(androidObbDirectory)
            }
            if (isInAndroidObbDirectory) {
                // Note that StorageManagerService won't automatically kill and restart our process
                // when we are granted REQUEST_INSTALL_PACKAGES for us to get access to Android/obb
                // immediately since S, similar to it not automatically killing and restarting our
                // process when we are granted MANAGE_EXTERNAL_STORAGE for us to get access to
                // external storage volumes immediately. But we aren't handling the latter anyway,
                // so let's not handle the former here either.
                if (isRequestInstallPackagesAllowed()) {
                    return true
                }
                val appObbDirectory = androidObbDirectory.resolve(appPackageName)
                return startsWith(appObbDirectory)
            }
        }
        return true
    }

    private constructor(source: Parcel) : super(source) {
        fileSystem = source.readParcelable()!!
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)

        dest.writeParcelable(fileSystem, flags)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<LinuxPath> {
            override fun createFromParcel(source: Parcel): LinuxPath = LinuxPath(source)

            override fun newArray(size: Int): Array<LinuxPath?> = arrayOfNulls(size)
        }

        private val FILE_ANDROID_DATA = File("Android/data")
        private val FILE_ANDROID_OBB = File("Android/obb")

        // IPC for checking the app op is expensive, and we'll be killed by StorageManagerService
        // when losing the app op, so let's just cache the result if it was ever allowed.
        @Volatile
        private var wasRequestInstallPackagesAllowed = false
        private fun isRequestInstallPackagesAllowed(): Boolean {
            if (wasRequestInstallPackagesAllowed) {
                return true
            }
            // We'll never have the signature|appop permission itself, so we only need to check the
            // app op against MODE_ALLOWED.
            return (
                appOpsManager.checkOpRawNoThrowCompat(
                    AppOpsManagerCompat.OPSTR_REQUEST_INSTALL_PACKAGES,
                    Process.myUid(),
                    application.opPackageNameCompat,
                    null
                ) == AppOpsManager.MODE_ALLOWED
            )
                .also { wasRequestInstallPackagesAllowed = it }
        }
    }
}

val Path.isLinuxPath: Boolean
    get() = this is LinuxPath

// The check sees resolved paths, and /data/user/0 is a symlink to /data/data on many devices.
private val canonicalDataDir: File by lazy { application.dataDir.canonicalFile }

/**
 * Whether an operation on [file] needs root ([RootCheck.ROOT]), walking it component by component and every symlink
 * hop by hop (readlink), so each target is judged again. Root is only taken when nothing else gets there, never
 * because of a symlink in a place the app can write to: another app could plant it there so root follows it (a
 * confused deputy). Such a link is left to the kernel without root.
 * - A symlink in a directory reachable without root, also one reached through another link: no root
 *   ([RootCheck.SHARED_SYMLINK]).
 * - A symlink elsewhere (e.g. /sdcard in /) continues with its target's components.
 * - "." and ".." count where they lead. [noFollowLinks] (lstat, unlink, rename, readlink) does not follow the path's
 *   own last component.
 * - The end decides by [rootOnly]. Anything that cannot be told (lstat or readlink fails, more than 40 hops) needs
 *   root.
 */
internal fun rootCheck(
    file: File,
    noFollowLinks: Boolean,
    rootOnly: (File) -> Boolean,
    readlink: (File) -> String? = ::lstatReadlink
): RootCheck =
    try {
        // Pairs of a name and whether it is the path's own last component.
        val names = ArrayDeque(file.absolutePath.split('/').filter { it.isNotEmpty() }.map { it to false })
        if (names.isNotEmpty()) names[names.lastIndex] = names.last().first to true
        var current = File("/")
        var hops = 0
        while (names.isNotEmpty()) {
            val (name, isLast) = names.removeFirst()
            if (name == ".") continue
            if (name == "..") {
                current = current.parentFile ?: current
                continue
            }
            val next = File(current, name)
            val target = if (isLast && noFollowLinks) null else readlink(next)
            if (target == null) {
                current = next
                continue
            }
            if (!rootOnly(current)) return RootCheck.SHARED_SYMLINK
            if (++hops > 40) return RootCheck.ROOT
            if (target.startsWith('/')) current = File("/")
            target.split('/').filter { it.isNotEmpty() }.asReversed().forEach { names.addFirst(it to false) }
        }
        if (rootOnly(current)) RootCheck.ROOT else RootCheck.LOCAL
    } catch (e: IOException) {
        RootCheck.ROOT
    }

/** [SHARED_SYMLINK]: through a symlink anyone could have planted; no root for it, the kernel judges. */
internal enum class RootCheck { LOCAL, ROOT, SHARED_SYMLINK }

/** The target of [file] if it is a symlink (lstat), null if it is not one or not there; throws when unknown. */
@Throws(IOException::class)
private fun lstatReadlink(file: File): String? =
    try {
        if (OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)) Os.readlink(file.path) else null
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) null else throw IOException(e)
    }
