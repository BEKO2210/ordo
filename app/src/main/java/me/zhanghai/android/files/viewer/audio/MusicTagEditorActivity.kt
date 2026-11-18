/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.MusicTagEditorActivityBinding
import me.zhanghai.android.files.ui.Motion

/**
 * Ordo Music's tag editor for one song or all songs of a page. Only the fields the user changed are written, a
 * preview names them before anything is saved, and the files are replaced only after the new tags read back right
 * ([SongTagIo]). The caller offers undo through [TagEditUndo].
 */
class MusicTagEditorActivity : AppActivity() {
    private lateinit var binding: MusicTagEditorActivityBinding
    private val files by lazy { intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty().map(::File) }
    private val inputs = linkedMapOf<TagField, TextInputLayout>()
    private var original: Map<TagField, String?> = emptyMap()
    private var originalCover: Cover? = null
    private var coverChange: CoverChange = CoverChange.Keep
    private var writable = false

    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadCover(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MusicTagEditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (files.isEmpty()) {
            finish()
            return
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.actionSave) save()
            true
        }
        if (files.size > 1) binding.toolbar.title = getString(R.string.music_edit_tags_all)
        binding.fileInfo.text = if (files.size == 1) {
            files.single().path.removePrefix(storageRoot() + "/")
        } else {
            resources.getQuantityString(R.plurals.music_song_count, files.size, files.size)
        }
        // The page's content at most 610 dp wide and centred on tablets.
        binding.scrollView.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val max = resources.getDimensionPixelSize(R.dimen.phi_content_max)
            val width = minOf(right - left, max)
            if (binding.content.layoutParams.width != width) {
                view.post { binding.content.layoutParams = binding.content.layoutParams.apply { this.width = width } }
            }
        }
        // On a phone the cover stays at 144 dp so the fields start on the first screen; tablets show it at 233 dp.
        val coverSize = resources.getDimensionPixelSize(
            if (resources.configuration.screenWidthDp >= 600) R.dimen.phi_233 else R.dimen.phi_144
        )
        binding.art.root.layoutParams = (binding.art.root.layoutParams as LinearLayout.LayoutParams).apply {
            width = coverSize
            height = coverSize
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        binding.art.root.radius = resources.getDimension(R.dimen.phi_21)
        createFields()
        binding.changeCover.setOnClickListener { pickCover.launch("image/*") }
        binding.removeCover.setOnClickListener {
            coverChange = CoverChange.Remove
            showCover()
        }
        binding.editCopy.setOnClickListener { editCopy() }
        load()
    }

    private fun createFields() {
        addField(binding.groupSong, TagField.TITLE, R.string.music_tag_title, R.drawable.audio_icon_white_24dp)
        addField(binding.groupSong, TagField.ARTIST, R.string.music_tag_artist, R.drawable.person_icon_white_24dp)
        addField(binding.groupSong, TagField.ALBUM, R.string.music_tag_album, R.drawable.album_icon_white_24dp)
        addField(
            binding.groupSong, TagField.ALBUM_ARTIST, R.string.music_tag_album_artist, R.drawable.person_icon_white_24dp
        )
        val trackRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        binding.groupNumbers.addView(trackRow)
        addField(trackRow, TagField.TRACK, R.string.music_tag_track, R.drawable.tag_numbers_icon_white_24dp, 1f)
        addField(trackRow, TagField.TRACK_TOTAL, R.string.music_tag_track_total, R.drawable.tag_numbers_icon_white_24dp, 0.618f)
        val discRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        binding.groupNumbers.addView(discRow)
        addField(discRow, TagField.DISC, R.string.music_tag_disc, R.drawable.album_icon_white_24dp, 1f)
        addField(discRow, TagField.YEAR, R.string.music_tag_year, R.drawable.tag_year_icon_white_24dp, 1f)
        addField(binding.groupNumbers, TagField.GENRE, R.string.music_tag_genre, R.drawable.tag_genre_icon_white_24dp)
        addField(binding.groupLyrics, TagField.LYRICS, R.string.music_tag_lyrics, R.drawable.tag_lyrics_icon_white_24dp)
    }

    private fun addField(
        parent: ViewGroup,
        field: TagField,
        @StringRes label: Int,
        @DrawableRes icon: Int?,
        weight: Float = 0f
    ) {
        val layout = TextInputLayout(this).apply {
            hint = getString(label)
            if (icon != null) setStartIconDrawable(icon)
            setStartIconContentDescription(label)
            // An empty field shows its name in the readable secondary colour, clearly not a value.
            defaultHintTextColor = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant,
                    com.google.android.material.color.MaterialColors.getColor(binding.root, android.R.attr.textColorSecondary)
                )
            )
            id = View.generateViewId()
            tag = field.name
        }
        val edit = TextInputEditText(layout.context).apply {
            // A stable resource-free id for device tests: the field's name.
            contentDescription = getString(label)
            inputType = when {
                field.numeric || field == TagField.YEAR -> InputType.TYPE_CLASS_NUMBER
                field == TagField.LYRICS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            if (field == TagField.LYRICS) minLines = 3
            doAfterTextChanged { layout.error = null }
        }
        layout.addView(edit)
        val params = if (weight > 0f) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        params.topMargin = resources.getDimensionPixelSize(R.dimen.phi_5)
        if (weight > 0f && parent.childCount > 0) params.marginStart = resources.getDimensionPixelSize(R.dimen.phi_8)
        parent.addView(layout, params)
        inputs[field] = layout
    }

    private fun load() {
        lifecycleScope.launch {
            val block = withContext(Dispatchers.IO) {
                TagEditRules.editBlock(
                    Build.VERSION.SDK_INT, files.map { it.extension },
                    files.all { it.canWrite() && it.parentFile?.canWrite() == true }
                )
            }
            // Android 7 never touches jaudiotagger (it needs java.nio.file): the note, nothing else.
            if (block == EditBlock.ANDROID) {
                showBlock(block)
                showCover()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                TagEditUndo.cleanUp(this@MusicTagEditorActivity)
                runCatching { files.map { SongTagIo.read(it) } }
            }
            val tags = result.getOrNull()
            if (tags == null) {
                // An Opus file can't even be read by jaudiotagger: the format note says why.
                if (block == EditBlock.FORMAT) showBlock(block)
                else showNotice(getString(R.string.music_tag_read_failed), copy = false)
                setEditable(false)
                showCover()
                return@launch
            }
            original = SongTagIo.merge(tags)
            originalCover = tags.map { it.cover }.distinctBy { it?.data?.contentHashCode() }.singleOrNull()
            for ((field, layout) in inputs) {
                val value = original[field]
                layout.editText!!.setText(value.orEmpty())
                if (value == null) layout.placeholderText = getString(R.string.music_tag_several)
                if (value == null) layout.helperText = getString(R.string.music_tag_several)
            }
            showCover()
            showBlock(block)
        }
    }

    private fun showBlock(block: EditBlock) {
        when (block) {
            EditBlock.NONE -> {
                setEditable(true)
                return
            }
            EditBlock.ANDROID -> showNotice(getString(R.string.music_tag_readonly_android), copy = false)
            EditBlock.FORMAT -> showNotice(
                getString(
                    R.string.music_tag_readonly_format,
                    files.first { !TagEditRules.isEditableFormat(it.extension) }.extension.uppercase()
                ),
                // A copy would be the same format: no "Edit a copy" here.
                copy = false
            )
            EditBlock.STORAGE -> showNotice(getString(R.string.music_tag_readonly_storage), copy = files.size == 1)
        }
        setEditable(false)
    }

    private fun showNotice(text: String, copy: Boolean) {
        binding.notice.isVisible = true
        binding.noticeText.text = text
        binding.editCopy.isVisible = copy
    }

    private fun setEditable(editable: Boolean) {
        writable = editable
        inputs.values.forEach { it.isEnabled = editable }
        binding.changeCover.isEnabled = editable
        binding.removeCover.isEnabled = editable && currentCover() != null
        binding.toolbar.menu.findItem(R.id.actionSave).isVisible = editable
    }

    private fun currentCover(): Cover? = when (val change = coverChange) {
        CoverChange.Keep -> originalCover
        CoverChange.Remove -> null
        is CoverChange.Set -> change.cover
    }

    private fun showCover() {
        val cover = currentCover()
        val name = original[TagField.ALBUM]
        binding.art.collage.isVisible = false
        val placeholder = MusicPlaceholderDrawable(this, name, R.drawable.album_icon_white_24dp)
        if (cover == null) {
            binding.art.cover.setImageDrawable(placeholder)
        } else {
            binding.art.cover.setImageDrawable(placeholder)
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this).data(cover.data).target(binding.art.cover)
                    .placeholder(placeholder).error(placeholder).crossfade(Motion.any).build()
            )
        }
        binding.removeCover.isEnabled = writable && cover != null
    }

    private fun loadCover(uri: Uri) {
        lifecycleScope.launch {
            val cover = withContext(Dispatchers.IO) {
                runCatching {
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size > MAX_COVER_BYTES) return@runCatching null
                    val data = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (data.size > MAX_COVER_BYTES) null
                    else Cover(data, contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg")
                }.getOrNull()
            }
            if (cover == null) {
                Snackbar.make(binding.root, R.string.music_tag_cover_too_large, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            coverChange = CoverChange.Set(cover)
            showCover()
        }
    }

    private fun edited(): Map<TagField, String> = inputs.mapValues { it.value.editText!!.text.toString() }

    private fun save() {
        if (!writable) return
        val edited = edited()
        val problems = SongTagIo.validate(edited)
        for ((field, problem) in problems) {
            inputs[field]!!.error = getString(
                when (problem) {
                    TagProblem.YEAR -> R.string.music_tag_error_year
                    TagProblem.NUMBER -> R.string.music_tag_error_number
                    TagProblem.TRACK_ABOVE_TOTAL -> R.string.music_tag_error_track
                }
            )
        }
        if (problems.isNotEmpty()) {
            inputs[problems.keys.first()]!!.requestFocus()
            return
        }
        val changes = SongTagIo.changes(original, edited, coverChange)
        if (changes.isEmpty) {
            Snackbar.make(binding.root, R.string.music_tag_nothing_changed, Snackbar.LENGTH_SHORT).show()
            return
        }
        // Preview: every changed field with its old and new value, then save.
        val lines = changes.fields.map { (field, value) ->
            getString(
                R.string.music_tag_preview_change, inputs[field]!!.hint,
                original[field]?.ifEmpty { null } ?: if (original[field] == null) getString(R.string.music_tag_several)
                else getString(R.string.music_tag_preview_empty),
                value.ifEmpty { getString(R.string.music_tag_preview_empty) }
            )
        }.toMutableList()
        when (coverChange) {
            is CoverChange.Set -> lines += getString(R.string.music_tag_preview_cover_new)
            CoverChange.Remove -> lines += getString(R.string.music_tag_preview_cover_removed)
            CoverChange.Keep -> {}
        }
        if (files.size > 1) lines.add(0, getString(R.string.music_tag_preview_files, files.size))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.music_tag_preview_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.music_tag_save) { _, _ -> write(changes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun write(changes: TagChanges) {
        binding.toolbar.menu.findItem(R.id.actionSave).isEnabled = false
        val saving = Snackbar.make(binding.root, R.string.music_tag_saving, Snackbar.LENGTH_INDEFINITE)
        saving.show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val backupDir = TagEditUndo.backupDir(this@MusicTagEditorActivity)
                val done = mutableListOf<Pair<File, File>>()
                try {
                    for (file in files) done += file to SongTagIo.write(file, changes, backupDir)
                    Result.success(done)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // All or nothing: the songs already written get their originals back.
                    done.forEach { (file, backup) -> runCatching { SongTagIo.restore(file, backup) }; backup.delete() }
                    Result.failure(e)
                }
            }
            saving.dismiss()
            result.onFailure {
                binding.toolbar.menu.findItem(R.id.actionSave).isEnabled = true
                Snackbar.make(
                    binding.root, getString(R.string.music_tag_failed, it.message ?: it.javaClass.simpleName),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            result.onSuccess { done ->
                scan(this@MusicTagEditorActivity, files)
                setResult(Activity.RESULT_OK, TagEditUndo.resultIntent(done))
                finish()
            }
        }
    }

    /** A read-only file: a copy in the Music folder, then the editor on that copy. */
    private fun editCopy() {
        val source = files.single()
        lifecycleScope.launch {
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    music.mkdirs()
                    var target = File(music, source.name)
                    var index = 2
                    while (target.exists()) target = File(music, "${source.nameWithoutExtension} ($index).${source.extension}").also { index++ }
                    source.copyTo(target)
                }.getOrNull()
            }
            if (copy == null) {
                Toast.makeText(this@MusicTagEditorActivity, R.string.music_tag_read_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            scan(this@MusicTagEditorActivity, listOf(copy))
            startActivity(createIntent(this@MusicTagEditorActivity, listOf(copy.path)))
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun storageRoot(): String = Environment.getExternalStorageDirectory().path

    companion object {
        private const val EXTRA_PATHS = "me.zhanghai.android.files.viewer.audio.extra.TAG_PATHS"
        private const val MAX_COVER_BYTES = 10L * 1024 * 1024

        /** Ordo's own local files only; the activity is not exported. */
        fun createIntent(context: Context, paths: List<String>): Intent =
            Intent(context, MusicTagEditorActivity::class.java).putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))

        /** Asks MediaStore to read the files again and waits (at most 5 s), so the library shows the new info. */
        suspend fun scan(context: Context, files: List<File>) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    var left = files.size
                    MediaScannerConnection.scanFile(
                        context.applicationContext, files.map { it.path }.toTypedArray(), null
                    ) { _, _ ->
                        synchronized(continuation) {
                            left--
                            if (left == 0 && continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            }
        }
    }
}
