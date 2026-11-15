/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import java.io.File

private const val RESTORE_NAME_MAX_LENGTH = 4096
private const val RESTORE_CONFLICT_MAX_ATTEMPTS = 10_000

/**
 * Vault files sit in this folder, and every backup opens with it as an empty folder entry. A user folder of the same
 * name is never written into a backup, so a user folder can never be taken for the vault.
 */
const val BACKUP_VAULT_FOLDER = ".ordo-vault"

/** Backups of Ordo 0.7.0 and 0.7.1 named the vault folder in the app's language, as a user folder can be named. */
val LEGACY_BACKUP_VAULT_FOLDER_NAMES = setOf("Vault", "Tresor")

/** A backup that does not open with this entry is an older one, whose vault folder has a legacy name. */
fun isBackupFormatMarker(name: String, isDirectory: Boolean): Boolean =
    isDirectory && name.removeSuffix("/") == BACKUP_VAULT_FOLDER

data class RestoreEntryPath(val relativePath: String, val isVault: Boolean)

/**
 * Maps one archive entry to shared storage without accepting alternate path syntax. Keeping this
 * function free of Android classes makes the complete zip-slip policy directly unit-testable.
 */
fun restoreEntryPath(name: String, isLegacyBackup: Boolean = false): RestoreEntryPath {
    require(name.isNotEmpty()) { "The backup contains an empty file name." }
    require(name.length <= RESTORE_NAME_MAX_LENGTH) { "A file name in the backup is too long." }
    require(!name.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(name)) {
        "The backup contains an absolute path."
    }
    require('\\' !in name) { "The backup contains a path with backslashes." }
    require(name.none { Character.isISOControl(it) }) {
        "The backup contains a path with control characters."
    }
    val segments = name.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "The backup contains a path that leaves its folder."
    }
    require(!isProtectedAndroidPath(name)) { "The backup contains a protected Android folder." }
    val folder = segments.first()
    return RestoreEntryPath(
        name,
        folder == BACKUP_VAULT_FOLDER || isLegacyBackup && folder in LEGACY_BACKUP_VAULT_FOLDER_NAMES
    )
}

/** Android/data and Android/obb belong to apps: a restore never writes there, so a backup leaves them out. */
fun isProtectedAndroidPath(path: String): Boolean {
    val segments = path.split('/')
    return segments.size >= 2 && segments[0].equals("Android", ignoreCase = true) &&
        (segments[1].equals("data", ignoreCase = true) || segments[1].equals("obb", ignoreCase = true))
}

/** Canonical verification is repeated on-device after parent folders have been created. */
fun resolveRestoreFile(storageRoot: File, relativePath: String): File {
    restoreEntryPath(relativePath)
    val canonicalRoot = storageRoot.canonicalFile
    val target = File(canonicalRoot, relativePath).canonicalFile
    require(target.path.startsWith("${canonicalRoot.path}${File.separator}")) {
        "The backup path leaves shared storage."
    }
    return target
}

sealed class RestoreDestinationState {
    object Missing : RestoreDestinationState()
    data class ExistingFile(val size: Long) : RestoreDestinationState()
    object ExistingOther : RestoreDestinationState()
}

sealed class RestoreDestination {
    object Skip : RestoreDestination()
    data class Write(val relativePath: String) : RestoreDestination()
}

/**
 * Existing files are never overwritten or deleted. The original path, or a " (restored)" name from an earlier
 * restore, is skipped when its size matches; otherwise a free " (restored)" name is chosen before the extension.
 */
fun chooseRestoreDestination(
    relativePath: String,
    expectedSize: Long,
    stateFor: (String) -> RestoreDestinationState
): RestoreDestination {
    require(expectedSize >= 0)
    when (val state = stateFor(relativePath)) {
        RestoreDestinationState.Missing -> return RestoreDestination.Write(relativePath)
        is RestoreDestinationState.ExistingFile -> {
            if (state.size == expectedSize) {
                return RestoreDestination.Skip
            }
        }
        RestoreDestinationState.ExistingOther -> {}
    }
    var number = 1
    while (number <= RESTORE_CONFLICT_MAX_ATTEMPTS) {
        val candidate = restoredName(relativePath, number)
        when (val state = stateFor(candidate)) {
            RestoreDestinationState.Missing -> return RestoreDestination.Write(candidate)
            // An earlier restore already put this file here: restoring twice adds no second copy.
            is RestoreDestinationState.ExistingFile -> if (state.size == expectedSize) return RestoreDestination.Skip
            RestoreDestinationState.ExistingOther -> {}
        }
        ++number
    }
    throw IllegalStateException("Too many conflicting restore names")
}

private fun restoredName(relativePath: String, number: Int): String {
    val fileName = relativePath.substringAfterLast('/')
    val dotIndex = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
    val cut = relativePath.length - fileName.length + dotIndex
    val suffix = if (number == 1) " (restored)" else " (restored $number)"
    return "${relativePath.substring(0, cut)}$suffix${relativePath.substring(cut)}"
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/")
