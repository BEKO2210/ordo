/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.FileAlreadyExistsException

internal object PartReplace {
    fun <T> replace(
        temp: T,
        target: T,
        replaceExisting: Boolean,
        targetExists: Boolean,
        backups: Sequence<T>,
        move: (T, T) -> Unit,
        delete: (T) -> Unit
    ) {
        if (!replaceExisting) {
            try {
                move(temp, target)
            } catch (e: Exception) {
                delete(temp)
                throw e
            }
            return
        }
        var createdBackup: T? = null
        if (targetExists) {
            var lastExists: FileAlreadyExistsException? = null
            for (backup in backups.take(8)) {
                try {
                    move(target, backup)
                    createdBackup = backup
                    lastExists = null
                    break
                } catch (e: FileAlreadyExistsException) {
                    lastExists = e
                } catch (e: Exception) {
                    delete(temp)
                    throw e
                }
            }
            lastExists?.let {
                delete(temp)
                throw it
            }
        }
        try {
            move(temp, target)
        } catch (e: Exception) {
            if (createdBackup != null) {
                try {
                    move(createdBackup, target)
                } catch (e2: Exception) {
                    e.addSuppressed(e2)
                    throw e
                }
            }
            delete(temp)
            throw e
        }
        createdBackup?.let { delete(it) }
    }
}
