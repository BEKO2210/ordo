/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class ConfirmDeleteFilesDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val files = args.files
        val toTrash = Trash.isEnabled && files.all { Trash.canTrash(it.path) }
        if (toTrash) {
            val message = if (files.size == 1) {
                getString(R.string.trash_move_message_one_format, files.single().name)
            } else {
                getQuantityString(R.plurals.trash_move_message_multiple_format, files.size, files.size)
            }
            return MaterialAlertDialogBuilder(requireContext(), theme)
                .setMessage(message)
                .setPositiveButton(R.string.trash_move_action) { _, _ ->
                    listener.deleteFiles(files, false)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.trash_delete_permanently_action) { _, _ ->
                    listener.deleteFiles(files, true)
                }
                .create()
        }
        var message = if (files.size == 1) {
            val file = files.single()
            val messageRes = if (file.attributesNoFollowLinks.isDirectory) {
                R.string.file_delete_message_directory_format
            } else {
                R.string.file_delete_message_file_format
            }
            getString(messageRes, file.name)
        } else {
            val allDirectories = files.all { it.attributesNoFollowLinks.isDirectory }
            val allFiles = files.none { it.attributesNoFollowLinks.isDirectory }
            val messageRes = when {
                allDirectories -> R.plurals.file_delete_message_multiple_directories_format
                allFiles -> R.plurals.file_delete_message_multiple_files_format
                else -> R.plurals.file_delete_message_multiple_mixed_format
            }
            getQuantityString(messageRes, files.size, files.size)
        }
        if (Trash.isEnabled) {
            message += "\n\n" + getString(R.string.trash_not_supported_message)
        }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> listener.deleteFiles(files, true) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        fun show(files: FileItemSet, fragment: Fragment) {
            ConfirmDeleteFilesDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    interface Listener {
        fun deleteFiles(files: FileItemSet, permanently: Boolean)
    }
}
