/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Size
import com.caverock.androidsvg.SVG
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import me.zhanghai.android.files.coil.fadeIn
import me.zhanghai.android.files.databinding.ImageViewerItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.shortAnimTime
import kotlin.math.max
import kotlin.math.roundToInt

class ImageViewerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val listener: (View) -> Unit
) : SimpleAdapter<Path, ImageViewerAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ImageViewerItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = getItem(position)
        val binding = holder.binding
        holder.loadJob?.cancel()
        // The path the view is showing. A late read must not paint the previous file into this holder.
        binding.root.tag = path
        binding.image.setOnPhotoTapListener { view, _, _ -> listener(view) }
        binding.largeImage.setOnClickListener(listener)
        loadImage(holder, path)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        holder.loadJob?.cancel()
        holder.loadJob = null
        val binding = holder.binding
        binding.root.tag = null
        binding.image.dispose()
        binding.largeImage.recycle()
    }

    private fun loadImage(holder: ViewHolder, path: Path) {
        val binding = holder.binding
        binding.progress.fadeInUnsafe(true)
        binding.errorText.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
        holder.loadJob = lifecycleOwner.lifecycleScope.launch {
            val imageInfo = try {
                withContext(Dispatchers.IO) { path.loadImageInfo() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                if (binding.root.tag == path) showError(binding, e)
                return@launch
            }
            if (binding.root.tag != path) return@launch
            loadImageWithInfo(binding, path, imageInfo)
        }
    }

    private fun Path.loadImageInfo(): ImageInfo {
        val attributes = readAttributes(BasicFileAttributes::class.java)
        val mimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
        if (mimeType == MimeType.IMAGE_SVG_XML) {
            val (width, height) = svgBounds()
            return ImageInfo(attributes, width, height, mimeType)
        }
        val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        newInputStream().use { BitmapFactory.decodeStream(it, null, bitmapOptions) }
        return ImageInfo(
            attributes, bitmapOptions.outWidth, bitmapOptions.outHeight,
            bitmapOptions.outMimeType?.asMimeTypeOrNull() ?: mimeType
        )
    }

    /** BitmapFactory does not report SVG size. Read it so a small icon is not scaled up to the cap. */
    private fun Path.svgBounds(): Pair<Int, Int> = try {
        val svg = newInputStream().source().buffer().use { SVG.getFromInputStream(it.inputStream()) }
        val width = svg.documentWidth
        val height = svg.documentHeight
        if (width > 0f && height > 0f) {
            width.roundToInt() to height.roundToInt()
        } else {
            val box = svg.documentViewBox
            if (box != null && box.width() > 0f && box.height() > 0f) {
                box.width().roundToInt() to box.height().roundToInt()
            } else {
                0 to 0
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        0 to 0
    }

    private fun loadImageWithInfo(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        if (!imageInfo.shouldUseLargeImageView) {
            binding.image.apply {
                isVisible = true
                load(path to imageInfo.attributes) {
                    val cap = coilEdgeCapPx(imageInfo.width, imageInfo.height, imageInfo.mimeType.value)
                    size(if (cap == null) Size.ORIGINAL else Size(cap, cap))
                    fadeIn(context.shortAnimTime)
                    listener(
                        onSuccess = { _, _ ->
                            if (binding.root.tag == path) binding.progress.fadeOutUnsafe()
                        },
                        onError = { _, result ->
                            if (binding.root.tag == path) showError(binding, result.throwable)
                        }
                    )
                }
            }
        } else {
            binding.largeImage.apply {
                setDoubleTapZoomDuration(300)
                orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
                // Otherwise OnImageEventListener.onReady() is never called.
                isVisible = true
                alpha = 0f
                setOnImageEventListener(object : DefaultOnImageEventListener() {
                    override fun onReady() {
                        if (binding.root.tag != path) return
                        setDoubleTapZoomScale(binding.largeImage.cropScale)
                        binding.progress.fadeOutUnsafe()
                        binding.largeImage.fadeInUnsafe(true)
                    }

                    override fun onImageLoadError(e: Exception) {
                        if (binding.root.tag != path) return
                        e.printStackTrace()
                        showError(binding, e)
                    }
                })
                setImageRestoringSavedState(ImageSource.uri(path.fileProviderUri))
            }
        }
    }

    private val ImageInfo.shouldUseLargeImageView: Boolean
        get() = shouldUseLargeImageView(width, height, mimeType.value)

    private val SubsamplingScaleImageView.cropScale: Float
        get() {
            val viewWidth = (width - paddingLeft - paddingRight)
            val viewHeight = (height - paddingTop - paddingBottom)
            val orientation = appliedOrientation
            val rotated90Or270 = orientation == SubsamplingScaleImageView.ORIENTATION_90
                || orientation == SubsamplingScaleImageView.ORIENTATION_270
            val imageWidth = if (rotated90Or270) sHeight else sWidth
            val imageHeight = if (rotated90Or270) sWidth else sHeight
            return max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        }

    private fun showError(binding: ImageViewerItemBinding, throwable: Throwable) {
        binding.progress.fadeOutUnsafe()
        binding.errorText.text = throwable.toString()
        binding.errorText.fadeInUnsafe(true)
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
    }

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
    }

    private class ImageInfo(
        val attributes: BasicFileAttributes,
        val width: Int,
        val height: Int,
        val mimeType: MimeType
    )
}

// @see android.graphics.RecordingCanvas#MAX_BITMAP_SIZE
internal const val MAX_BITMAP_BYTES = 100 * 1024 * 1024

/** SVG and images with no measured size are decoded at most this long on each edge, not at Size.ORIGINAL. */
internal const val UNKNOWN_IMAGE_EDGE_CAP_PX = 4096

internal fun shouldUseLargeImageView(width: Int, height: Int, mimeType: String): Boolean {
    // See BitmapFactory.cpp encodedFormatToString(). GIF stays in Coil. SVG has no tiled decoder.
    if (mimeType == "image/gif" || mimeType == "image/svg+xml") return false
    if (width <= 0 || height <= 0) return false
    // 4 bytes per pixel for ARGB_8888. Int overflows around 23 170 px square and then looks small.
    if (width.toLong() * height * 4 > MAX_BITMAP_BYTES) return true
    if (width > 2048 || height > 2048) {
        val ratio = width.toFloat() / height
        if (ratio < 0.5f || ratio > 2f) return true
    }
    return false
}

/**
 * Null means Coil's Size.ORIGINAL. SVG is capped only when an edge is already larger than the cap;
 * a smaller target would scale the icon up (Coil's SvgDecoder uses the requested size as the bitmap).
 */
internal fun coilEdgeCapPx(width: Int, height: Int, mimeType: String): Int? {
    if (mimeType == "image/svg+xml") {
        val longEdge = max(width, height)
        return if (longEdge > UNKNOWN_IMAGE_EDGE_CAP_PX) UNKNOWN_IMAGE_EDGE_CAP_PX else null
    }
    return if (width <= 0 || height <= 0) UNKNOWN_IMAGE_EDGE_CAP_PX else null
}
