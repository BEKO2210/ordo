/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux

import java.io.IOException

internal object LinuxExclusivePublish {
    @Throws(IOException::class)
    fun publish(exclusiveRename: () -> Unit, deleteTemp: () -> Unit) {
        try {
            exclusiveRename()
        } catch (e: Exception) {
            try {
                deleteTemp()
            } catch (e2: Exception) {
                e.addSuppressed(e2)
            }
            throw e
        }
    }

    fun mustNotOverwrite(replaceExisting: Boolean, targetExistsAtPublish: Boolean): Boolean =
        !replaceExisting && targetExistsAtPublish
}
