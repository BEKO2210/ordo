/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider

/** The app installs its file systems in Application; plain JVM tests have to do it themselves (once per JVM). */
internal fun installTestFileSystem() {
    runCatching { FileSystemProvider.installDefaultProvider(LinuxFileSystemProvider) }
}
