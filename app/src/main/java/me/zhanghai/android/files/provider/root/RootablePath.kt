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

/**
 * Whether a two-path operation (copy, move, link) needs root. When only one side does, root also acts on the other,
 * so a symlink planted there would lead root anywhere (a confused deputy): refused instead.
 */
@Throws(IOException::class)
internal fun isRootRequired(
    path1: RootablePath,
    path2: RootablePath,
    isAttributeAccess: Boolean,
    noFollowLinks: Boolean,
    noFollowLinks2: Boolean
): Boolean {
    val root1 = path1.isRootRequired(isAttributeAccess, noFollowLinks)
    val root2 = path2.isRootRequired(isAttributeAccess, noFollowLinks2)
    if (root1 != root2) {
        val (other, otherNoFollowLinks) = if (root1) path2 to noFollowLinks2 else path1 to noFollowLinks
        if (other.followsSharedSymlink(otherNoFollowLinks)) {
            throw AccessDeniedException(
                other.toString(), null, "Leads through a symbolic link in a shared folder: not followed with root"
            )
        }
    }
    return root1 || root2
}

private val rootStrategy: RootStrategy
    get() = if (isRunningAsRoot) RootStrategy.NEVER else Settings.ROOT_STRATEGY.valueCompat

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
    return when (rootStrategy) {
        RootStrategy.NEVER -> localObject.block()
        RootStrategy.AUTOMATIC ->
            if (path.isRootRequired(isAttributeAccess, noFollowLinks)) {
                rootObject.block()
            } else {
                localObject.block()
            }
        RootStrategy.ALWAYS -> rootObject.block()
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
    return when (rootStrategy) {
        RootStrategy.NEVER ->
            localObject.block()
        RootStrategy.AUTOMATIC ->
            if (isRootRequired(path1, path2, isAttributeAccess, noFollowLinks, noFollowLinks2)) {
                rootObject.block()
            } else {
                localObject.block()
            }
        RootStrategy.ALWAYS ->
            rootObject.block()
    }
}
