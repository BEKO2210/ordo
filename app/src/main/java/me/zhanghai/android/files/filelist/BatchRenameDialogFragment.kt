/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.BatchRenameDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.hideTextInputLayoutErrorOnTextChange
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class BatchRenameDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    // The caller passes the files in list order, so the numbers follow what the user sees.
    private val files: List<FileItem>
        get() = args.files.toList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = BatchRenameDialogBinding.inflate(layoutInflater)
        val files = files
        fun newNames() = BatchRename.newNames(
            files.map { it.name to it.attributes.isDirectory }, binding.patternEdit.text.toString()
        )
        fun updatePreview() {
            val preview = files.zip(newNames()).take(PREVIEW_COUNT)
                .joinToString("\n") { (file, newName) -> "${file.name} → $newName" }
            binding.previewText.text = if (files.size > PREVIEW_COUNT) "$preview\n…" else preview
        }
        if (savedInstanceState == null) {
            binding.patternEdit.setText(getString(R.string.batch_rename_default_pattern))
            binding.patternEdit.selectAll()
        }
        binding.patternEdit.doAfterTextChanged { updatePreview() }
        updatePreview()
        binding.patternEdit.hideTextInputLayoutErrorOnTextChange(binding.patternLayout)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(getString(R.string.batch_rename_title_format, files.size))
            .setView(binding.root)
            .setPositiveButton(R.string.rename, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                // Override the listener here so that the dialog stays open when a name is rejected.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (binding.patternEdit.text.isNullOrBlank()) {
                            return@setOnClickListener
                        }
                        val renames = files.zip(newNames())
                        val conflict = BatchRename.conflict(
                            renames.map { (file, newName) -> file.name to newName },
                            listener::hasFileWithName
                        )
                        if (conflict != null) {
                            binding.patternLayout.error = getString(
                                if (conflict == BatchRename.Conflict.INVALID) {
                                    R.string.file_name_error_invalid
                                } else {
                                    R.string.file_name_error_already_exists
                                }
                            )
                            return@setOnClickListener
                        }
                        listener.renameFiles(renames)
                        dismiss()
                    }
                }
            }
    }

    companion object {
        private const val PREVIEW_COUNT = 3

        fun show(files: FileItemSet, fragment: Fragment) {
            BatchRenameDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    interface Listener {
        fun renameFiles(renames: List<Pair<FileItem, String>>)

        fun hasFileWithName(name: String): Boolean
    }
}
