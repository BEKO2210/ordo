/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filejob

class TransferEstimate(val bytesPerSecond: Long, val remainingSeconds: Long)

// Average over the whole job: steadier than an instant rate for a progress notification.
fun estimateTransfer(transferredSize: Long, size: Long, elapsedMillis: Long): TransferEstimate? {
    if (elapsedMillis < 1000 || transferredSize <= 0) {
        return null
    }
    val bytesPerSecond = transferredSize * 1000 / elapsedMillis
    if (bytesPerSecond <= 0) {
        return null
    }
    val remaining = (size - transferredSize).coerceAtLeast(0)
    return TransferEstimate(bytesPerSecond, (remaining + bytesPerSecond - 1) / bytesPerSecond)
}
