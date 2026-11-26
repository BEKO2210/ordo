/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import java8.nio.file.AccessDeniedException
import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException

interface RootablePath {
    /** [noFollowLinks]: the operation acts on a symlink itself (lstat, unlink, rename, readlink), not its target. */
    fun isRootRequired(isAttributeAccess: Boolean, noFollowLinks: Boolean): Boolean

    /** Whether the path leads through a symlink in a place reachable without root, which anyone could have planted. */
    fun followsSharedSymlink(noFollowLinks: Boolean): Boolean = false
}

private val rootStrategy: RootStrategy
    get() = if (isRunningAsRoot) RootStrategy.NEVER else Settings.ROOT_STRATEGY.valueCompat

private const val SHARED_SYMLINK_REASON = "Leads through a symbolic link in a shared folder: not followed with root"

/** Whether [path] is reached with root. Even "always root" never follows a symlink in a shared folder. */
internal fun usesRoot(
    strategy: RootStrategy,
    path: RootablePath,
    isAttributeAccess: Boolean,
    noFollowLinks: Boolean
): Boolean =
    when (strategy) {
        RootStrategy.NEVER -> false
        RootStrategy.AUTOMATIC -> path.isRootRequired(isAttributeAccess, noFollowLinks)
        RootStrategy.ALWAYS -> !path.followsSharedSymlink(noFollowLinks)
    }

/**
 * Whether a two-path operation (copy, move, link) runs with root, which then acts on both paths. A symlink planted on
 * a side that needs no root would lead root anywhere (a confused deputy): refused instead, also with "always root".
 */
@Throws(IOException::class)
internal fun usesRoot(
    strategy: RootStrategy,
    path1: RootablePath,
    path2: RootablePath,
    isAttributeAccess: Boolean,
    noFollowLinks: Boolean,
    noFollowLinks2: Boolean
): Boolean {
    val root1: Boolean
    val root2: Boolean
    when (strategy) {
        RootStrategy.NEVER -> return false
        RootStrategy.AUTOMATIC -> {
            root1 = path1.isRootRequired(isAttributeAccess, noFollowLinks)
            root2 = path2.isRootRequired(isAttributeAccess, noFollowLinks2)
            if (!root1 && !root2) return false
        }
        RootStrategy.ALWAYS -> {
            root1 = false
            root2 = false
        }
    }
    if (!root1 && path1.followsSharedSymlink(noFollowLinks)) {
        throw AccessDeniedException(path1.toString(), null, SHARED_SYMLINK_REASON)
    }
    if (!root2 && path2.followsSharedSymlink(noFollowLinks2)) {
        throw AccessDeniedException(path2.toString(), null, SHARED_SYMLINK_REASON)
    }
    return true
}

@Throws(IOException::class)
fun <T, R> callRootable(
    path: Path,
    isAttributeAccess: Boolean,
    localObject: T,
    rootObject: T, block: T.() -> R
): R = callRootable(path, isAttributeAccess, false, localObject, rootObject, block)

@Throws(IOException::class)
fun <T, R> callRootable(
    path: Path,
    isAttributeAccess: Boolean,
    noFollowLinks: Boolean,
    localObject: T,
    rootObject: T, block: T.() -> R
): R {
    path as? RootablePath ?: throw IllegalArgumentException("$path is not a RootablePath")
    return if (usesRoot(rootStrategy, path, isAttributeAccess, noFollowLinks)) {
        rootObject.block()
    } else {
        localObject.block()
    }
}

@Throws(IOException::class)
fun <T, R> callRootable(
    path1: Path,
    path2: Path,
    isAttributeAccess: Boolean,
    noFollowLinks: Boolean,
    noFollowLinks2: Boolean,
    localObject: T,
    rootObject: T,
    block: T.() -> R
): R {
    path1 as? RootablePath ?: throw IllegalArgumentException("$path1 is not a RootablePath")
    path2 as? RootablePath ?: throw IllegalArgumentException("$path2 is not a RootablePath")
    return if (usesRoot(rootStrategy, path1, path2, isAttributeAccess, noFollowLinks, noFollowLinks2)) {
        rootObject.block()
    } else {
        localObject.block()
    }
}
