/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException

interface RootablePath {
    /** [noFollowLinks]: the operation acts on a symlink itself (lstat, unlink, rename, readlink), not its target. */
    fun isRootRequired(isAttributeAccess: Boolean, noFollowLinks: Boolean): Boolean
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
            if (path1.isRootRequired(isAttributeAccess, noFollowLinks)
                || path2.isRootRequired(isAttributeAccess, noFollowLinks)) {
                rootObject.block()
            } else {
                localObject.block()
            }
        RootStrategy.ALWAYS ->
            rootObject.block()
    }
}
