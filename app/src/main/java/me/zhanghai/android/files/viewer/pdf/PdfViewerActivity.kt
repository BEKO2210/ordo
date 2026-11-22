/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.PdfViewerActivityBinding
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.image.STATE_VAULT_STOPPED_AT
import me.zhanghai.android.files.viewer.image.debugVaultTimeoutOverride
import me.zhanghai.android.files.viewer.image.isVaultContentUri
import me.zhanghai.android.files.viewer.image.shouldSecureViewer
import me.zhanghai.android.files.viewer.image.vaultLeaveOnStop
import me.zhanghai.android.files.viewer.image.vaultViewerTimedOut
import me.zhanghai.android.files.viewer.image.vaultViewerTimeoutMillis

// Built-in PDF viewer on the platform PdfRenderer: swipe between pages, pinch to zoom.
class PdfViewerActivity : AppActivity() {
    private lateinit var binding: PdfViewerActivityBinding

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /** Elapsed realtime when a vault PDF left the screen, or 0 while it is visible. */
    private var vaultStoppedAt = 0L

    /** True after the window was focused. A background recreate never sets this, so its onStop keeps [vaultStoppedAt]. */
    private var vaultInFront = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vaultStoppedAt = savedInstanceState?.getLong(STATE_VAULT_STOPPED_AT) ?: 0L

        // Same as VaultActivity: screenshots stay empty outside debug builds. Set before the open,
        // so a vault PDF that fails to render is still covered.
        if (shouldSecureViewer(BuildConfig.DEBUG, vaultUris(), packageName)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        binding = PdfViewerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = intent.data?.let { displayName(it) }
        val renderer = try {
            fileDescriptor = contentResolver.openFileDescriptor(intent.data!!, "r")
            PdfRenderer(fileDescriptor!!)
        } catch (e: Exception) {
            // Covers missing data, unreadable files, encrypted and broken PDFs.
            e.printStackTrace()
            binding.viewPager.isVisible = false
            binding.errorText.isVisible = true
            return
        }
        this.renderer = renderer
        binding.viewPager.adapter = PageAdapter(renderer)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.toolbar.subtitle =
                    getString(R.string.pdf_viewer_page_format, position + 1, renderer.pageCount)
            }
        })
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) return it.getString(0) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/')
    }

    override fun onStart() {
        super.onStart()

        // Do not clear the leave time here. A recreate in the background also runs onStart, and
        // clearing it would let the following onStop start the two minutes over.
        if (!isVaultDocument()) {
            vaultStoppedAt = 0L
            return
        }
        val timeout = vaultViewerTimeoutMillis(BuildConfig.DEBUG, debugVaultTimeoutOverride())
        if (vaultViewerTimedOut(vaultStoppedAt, SystemClock.elapsedRealtime(), timeout)) {
            vaultStoppedAt = 0L
            finish()
        }
    }

    override fun onResume() {
        super.onResume()

        if (hasWindowFocus()) markVaultInFront()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) markVaultInFront()
    }

    override fun onStop() {
        super.onStop()

        // A rotation must not count as leaving. A debug timeout of 0 would otherwise close on rotate.
        vaultStoppedAt = vaultLeaveOnStop(
            isVault = isVaultDocument(),
            finishing = isFinishing,
            changingConfigurations = isChangingConfigurations,
            inFront = vaultInFront,
            stoppedAt = vaultStoppedAt,
            now = SystemClock.elapsedRealtime()
        )
        vaultInFront = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putLong(STATE_VAULT_STOPPED_AT, vaultStoppedAt)
    }

    private fun markVaultInFront() {
        if (isFinishing) return
        vaultInFront = true
        vaultStoppedAt = 0L
    }

    override fun onDestroy() {
        super.onDestroy()

        renderer?.close()
        fileDescriptor?.close()
    }

    private fun vaultUris(): List<String?> = buildList {
        add(intent.dataString)
        runCatching { intent.extraPath?.toString() }.getOrNull()?.let { add(it) }
    }

    private fun isVaultDocument(): Boolean {
        if (isVaultContentUri(intent.dataString, packageName)) return true
        val path = runCatching { intent.extraPath?.toString() }.getOrNull()
        return isVaultContentUri(path, packageName)
    }

    private inner class PageAdapter(private val renderer: PdfRenderer) :
        RecyclerView.Adapter<PageAdapter.ViewHolder>() {
        override fun getItemCount(): Int = renderer.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                PhotoView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    maximumScale = 6f
                }
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val view = holder.view
            view.setImageDrawable(null)
            view.tag = position
            // Rendered at twice the screen width so zoomed text stays sharp.
            val width = resources.displayMetrics.widthPixels * 2
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { render(position, width) }
                if (view.tag == position) {
                    view.setImageBitmap(bitmap)
                }
            }
        }

        // PdfRenderer allows one open page at a time, so rendering is serialized.
        private fun render(index: Int, width: Int): Bitmap =
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val height = (width.toLong() * page.height / page.width).toInt()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }

        inner class ViewHolder(val view: PhotoView) : RecyclerView.ViewHolder(view)
    }
}
