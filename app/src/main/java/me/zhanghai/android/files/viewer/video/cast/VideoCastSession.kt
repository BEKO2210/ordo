/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.cast

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.android.material.slider.Slider
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.Formatter
import java.util.Locale
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.VideoCastPanelBinding
import me.zhanghai.android.files.player.cast.CastClient
import me.zhanghai.android.files.player.cast.CastDevice
import me.zhanghai.android.files.player.cast.CastDeviceAuth
import me.zhanghai.android.files.player.cast.CastFileServer
import me.zhanghai.android.files.player.cast.CastManager
import me.zhanghai.android.files.player.cast.CastPlayback
import me.zhanghai.android.files.player.cast.CastProtocol
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.viewer.audio.CastChooserSheet

/**
 * One film on a Chromecast or Google TV, driven from Ordo Video's player. Reuses the music's Cast stack (discovery,
 * device auth, [CastClient], [CastFileServer]) but not its session: the film goes to Google's Default Media Receiver
 * with its sidecar subtitles as WebVTT tracks, and the player screen becomes a remote control ([VideoCastPanelBinding])
 * until the user takes the film back; it then continues here, paused, where the TV was.
 */
@UnstableApi
class VideoCastSession(
    private val root: FrameLayout,
    private val rewindTemplate: ImageButton,
    private val forwardTemplate: ImageButton,
    private val jumpMs: () -> Long,
    private val host: Host
) : CastChooserSheet.Controller {
    interface Host {
        /** The film on screen now; null when there is none. */
        fun castMedia(): Media?
        /** The TV plays: the phone pauses. */
        fun onCastStarted()
        /** Back on the phone at [positionMs] (null: unknown, stay where the phone was). */
        fun onCastEnded(positionMs: Long?)
    }

    class Media(val mediaId: String, val title: String, val positionMs: Long, val playWhenReady: Boolean)

    private val context: Context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var client: CastClient? = null
    private var server: CastFileServer? = null
    private var panel: VideoCastPanelBinding? = null
    private var hiddenSiblings = emptyList<Pair<View, Int>>()
    private var status: CastProtocol.MediaStatus? = null
    private var statusAtMs = 0L
    @Volatile private var loadRequest = 0
    private var volume: Double? = null
    private var title = ""
    private var failed = false
    private var seeking = false
    private var attempt = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeBuilder = StringBuilder()
    private val timeFormatter = Formatter(timeBuilder)

    override var phase = CastManager.Phase.IDLE
        private set
    override var device: CastDevice? = null
        private set
    override var confirmation: CastDevice? = null
        private set
    override val privacyText: Int get() = R.string.video_cast_privacy
    override val showsEffectsNote: Boolean get() = false

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    // The music's pins: a name that once was another device is marked here too.
    override fun isUnfamiliar(target: CastDevice): Boolean {
        val remembered = pins().getString(target.name, null) ?: return false
        return !remembered.startsWith(CastDeviceAuth.record(target.id, ""))
    }

    override fun dismissConfirmation() {
        confirmation = null
        setPhase(phase)
    }

    override fun connect(target: CastDevice, confirmed: Boolean) {
        confirmation = null
        // Another TV: the film comes back here first, so the new one starts where the old one was.
        if (phase != CastManager.Phase.IDLE && device?.id != target.id) end(stopReceiver = true)
        if (phase != CastManager.Phase.IDLE) return
        val media = host.castMedia() ?: return
        val name = displayName(media.mediaId)
        val contentType = VideoCastFormats.contentType(name)
        when {
            !target.hasVideo -> return toast(context.getString(R.string.video_cast_no_screen, target.name))
            // Said before anything connects: the Default Media Receiver plays MP4, WebM and (on Google TV) MKV.
            contentType == null -> return toast(context.getString(
                R.string.video_cast_format_unsupported, VideoCastFormats.extension(name).ifEmpty { name }
            ))
        }
        val currentAttempt = ++attempt
        device = target
        title = media.title
        failed = false
        setPhase(CastManager.Phase.CONNECTING)
        // The phone holds still while the TV gets ready, so the TV starts exactly where the phone stopped.
        host.onCastStarted()
        val newClient = CastClient(target.host, target.port, object : CastClient.Listener {
            override fun onReceiverStatus(status: CastProtocol.ReceiverStatus) {
                handler.post {
                    if (attempt != currentAttempt) return@post
                    status.volumeLevel?.let { volume = it }
                    render()
                }
            }

            override fun onMediaStatus(status: CastProtocol.MediaStatus) {
                handler.post { if (attempt == currentAttempt) onStatus(status) }
            }

            override fun onRequestFailed(type: String, requestId: Int) {
                handler.post { if (attempt == currentAttempt && requestId == loadRequest) showFailed() }
            }

            override fun onClosed(error: Throwable?) {
                if (error != null) handler.post { if (attempt == currentAttempt) end(stopReceiver = false, lost = true) }
            }
        })
        client = newClient
        Thread({
            val result = runCatching {
                val pin = newClient.connect().pin
                val known = CastDeviceAuth.compare(pins().getString(target.name, null), target.id, pin)
                if (known == CastDeviceAuth.Known.CHANGED && !confirmed) throw IdentityChanged()
                pins().edit().putString(target.name, CastDeviceAuth.record(target.id, pin)).apply()
                val receiver = newClient.launch(CastProtocol.DEFAULT_MEDIA_RECEIVER)
                val newServer = CastFileServer(newClient.localAddress, InetAddress.getByName(target.host))
                try {
                    val subtitles = subtitles(media.mediaId, name)
                    val urls = newServer.serveAll(listOf(source(media.mediaId, checkNotNull(contentType))) +
                        subtitles.map { it.second })
                    val tracks = subtitles.mapIndexed { index, (subtitle, _) ->
                        VideoCastProtocol.TextTrack(urls[index + 1], subtitle.language?.let(::languageName)
                            ?: context.getString(R.string.video_cast_subtitles), subtitle.language)
                    }
                    loadRequest = newClient.load { id ->
                        VideoCastProtocol.load(
                            id, urls[0], contentType, media.title, tracks, media.positionMs / 1000.0, media.playWhenReady
                        )
                    }
                } catch (e: Exception) {
                    newServer.close()
                    throw e
                }
                newServer to receiver
            }
            handler.post {
                if (attempt != currentAttempt) {
                    result.getOrNull()?.first?.close()
                    return@post
                }
                result.onSuccess { (newServer, receiver) ->
                    server = newServer
                    volume = receiver.volumeLevel
                    // The TV starts where the phone was; until its first status says otherwise, that is the place.
                    status = CastProtocol.MediaStatus(0, "LOADING", null, media.positionMs / 1000.0, null, 0)
                    statusAtMs = System.currentTimeMillis()
                    acquireLocks()
                    setPhase(CastManager.Phase.CASTING)
                    showPanel()
                }.onFailure {
                    newClient.close()
                    client = null
                    device = null
                    if (it is IdentityChanged) confirmation = target
                    setPhase(CastManager.Phase.IDLE)
                    when (it) {
                        is IdentityChanged -> {}
                        is java.security.cert.CertificateException ->
                            toast(context.getString(R.string.cast_error_identity, target.name))
                        else -> toast(context.getString(R.string.cast_error_connect, target.name))
                    }
                }
            }
        }, "video-cast-connect").start()
    }

    override fun disconnect() = end(stopReceiver = true)

    /** The player screen goes away: the TV stops too (nothing would be left to control it). */
    fun release() {
        end(stopReceiver = true, handBack = false)
        handler.removeCallbacksAndMessages(null)
    }

    val isCasting: Boolean get() = phase == CastManager.Phase.CASTING

    /** Volume keys while casting set the TV's volume, in the music's 20 steps. */
    fun onVolumeKey(up: Boolean): Boolean {
        if (!isCasting) return false
        changeVolume(if (up) 1 else -1)
        return true
    }

    private fun onStatus(newStatus: CastProtocol.MediaStatus) {
        // The receiver answers LOAD with a bare IDLE first (17.11.2026); only one with a reason means something.
        if (newStatus.playerState == "IDLE" && newStatus.idleReason == null) return
        status = newStatus
        statusAtMs = System.currentTimeMillis()
        if (CastPlayback.isError(newStatus)) return showFailed()
        failed = false
        // Watched to the end: back to the phone at the end (its "Watch again"), nothing left running for the TV.
        if (newStatus.playerState == "IDLE" && newStatus.idleReason == "FINISHED") return end(stopReceiver = true)
        render()
    }

    private fun showFailed() {
        failed = true
        render()
    }

    private fun positionMs(): Long? = status?.let { CastPlayback.positionMs(it, statusAtMs, System.currentTimeMillis()) }

    private fun durationMs(): Long = status?.durationSeconds?.let { (it * 1000).toLong() } ?: 0L

    private fun end(stopReceiver: Boolean, lost: Boolean = false, handBack: Boolean = true) {
        if (phase == CastManager.Phase.IDLE) return
        attempt++
        val wasCasting = phase == CastManager.Phase.CASTING
        val finished = status?.playerState == "IDLE" && status?.idleReason == "FINISHED"
        val position = if (finished) durationMs().takeIf { it > 0 } else positionMs()
        val lostDevice = device
        val oldClient = client
        val oldServer = server
        client = null
        server = null
        Thread({
            if (stopReceiver && oldClient != null) {
                oldClient.stopApp()
                oldClient.flush()
            }
            oldClient?.close()
            oldServer?.close()
        }, "video-cast-end").start()
        releaseLocks()
        hidePanel()
        status = null
        device = null
        setPhase(CastManager.Phase.IDLE)
        if (wasCasting && handBack) host.onCastEnded(position)
        if (lost && lostDevice != null) toast(context.getString(R.string.video_cast_error_lost, lostDevice.name))
    }

    private fun setPhase(newPhase: CastManager.Phase) {
        phase = newPhase
        listeners.toList().forEach { it() }
    }

    private fun showPanel() {
        val binding = panel ?: VideoCastPanelBinding.inflate(LayoutInflater.from(context), root, false).also { binding ->
            panel = binding
            root.addView(binding.root)
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            binding.castPlayPause.setOnClickListener {
                val client = client ?: return@setOnClickListener
                if (status?.playerState == "PLAYING" || status?.playerState == "BUFFERING") client.pause() else client.play()
            }
            binding.castRewind.setOnClickListener { seekBy(-jumpMs()) }
            binding.castForward.setOnClickListener { seekBy(jumpMs()) }
            binding.castVolumeDown.setOnClickListener { changeVolume(-1) }
            binding.castVolumeUp.setOnClickListener { changeVolume(1) }
            binding.castStop.setOnClickListener { disconnect() }
            binding.castSeekBar.addOnChangeListener { _, value, fromUser ->
                // TalkBack and keys step the bar without a touch: those seek at once.
                if (fromUser && !seeking) seekTo((durationMs() * value / SEEK_MAX).toLong())
                if (fromUser) binding.castElapsed.text = formatTime((durationMs() * value / SEEK_MAX).toLong())
            }
            binding.castSeekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    seeking = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    seeking = false
                    seekTo((durationMs() * slider.value / SEEK_MAX).toLong())
                }
            })
        }
        // The same jump as on the phone: its icon, mirroring and words.
        listOf(binding.castRewind to rewindTemplate, binding.castForward to forwardTemplate).forEach { (button, template) ->
            button.setImageDrawable(template.drawable?.constantState?.newDrawable()?.mutate())
            button.scaleX = template.scaleX
            button.contentDescription = template.contentDescription
        }
        binding.root.isVisible = true
        ViewCompat.requestApplyInsets(binding.root)
        // TalkBack reads only the remote control while it covers the player.
        hiddenSiblings = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== binding.root }
            .map { it to it.importantForAccessibility }
        hiddenSiblings.forEach { (view, _) ->
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        render()
        binding.castTitle.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun hidePanel() {
        handler.removeCallbacks(ticker)
        panel?.root?.isVisible = false
        hiddenSiblings.forEach { (view, importance) -> view.importantForAccessibility = importance }
        hiddenSiblings = emptyList()
    }

    private val ticker = Runnable { render() }

    private fun render() {
        val binding = panel ?: return
        if (!isCasting) return
        handler.removeCallbacks(ticker)
        val status = status
        val playing = status?.playerState == "PLAYING" || status?.playerState == "BUFFERING" ||
            status?.playerState == "LOADING"
        binding.castDevice.text = context.getString(R.string.cast_chip, device?.name.orEmpty())
        binding.castTitle.text = title
        binding.castStatus.text = when {
            failed -> context.getString(R.string.video_cast_error_format)
            status?.playerState == "BUFFERING" || status?.playerState == "LOADING" ->
                context.getString(R.string.video_cast_loading)
            status?.playerState == "PAUSED" -> context.getString(R.string.video_cast_paused)
            else -> ""
        }
        binding.castPlayPause.setImageResource(if (playing) R.drawable.pause_icon_24dp else R.drawable.play_icon_24dp)
        binding.castPlayPause.contentDescription =
            context.getString(if (playing) R.string.video_player_pause else R.string.video_player_play)
        val duration = durationMs()
        val position = (positionMs() ?: 0L).coerceIn(0L, duration.coerceAtLeast(0L).takeIf { it > 0 } ?: Long.MAX_VALUE)
        binding.castSeekBar.isEnabled = duration > 0 && !failed
        if (!seeking) {
            binding.castSeekBar.value = if (duration > 0) (position * SEEK_MAX / duration).toFloat().coerceIn(0f, SEEK_MAX) else 0f
            binding.castElapsed.text = formatTime(position)
        }
        binding.castDuration.text = if (duration > 0) formatTime(duration) else ""
        ViewCompat.setStateDescription(binding.castSeekBar, context.getString(
            R.string.video_player_progress_format, formatTime(position), formatTime(duration)
        ))
        val volume = volume
        binding.castVolume.text = volume?.let {
            context.getString(R.string.video_cast_volume_format, (it * 100).toInt())
        }.orEmpty()
        binding.castVolumeDown.isEnabled = volume != null
        binding.castVolumeUp.isEnabled = volume != null
        if (status?.playerState == "PLAYING") handler.postDelayed(ticker, TICK_MS)
    }

    private fun seekBy(deltaMs: Long) {
        val position = positionMs() ?: return
        seekTo(position + deltaMs)
    }

    private fun seekTo(positionMs: Long) {
        val duration = durationMs()
        val target = positionMs.coerceAtLeast(0L).let { if (duration > 0) it.coerceAtMost(duration) else it }
        client?.seek(target / 1000.0)
        // Shown at once; the TV's next status confirms it.
        status = status?.copy(currentTimeSeconds = target / 1000.0)
        statusAtMs = System.currentTimeMillis()
        render()
    }

    private fun changeVolume(steps: Int) {
        val client = client ?: return
        val level = CastPlayback.level(CastPlayback.deviceVolume(volume ?: return) + steps)
        volume = level
        client.setVolume(level)
        render()
    }

    private fun formatTime(milliseconds: Long): String =
        Util.getStringForTime(timeBuilder, timeFormatter, milliseconds.coerceAtLeast(0L))

    private fun pins() = context.getSharedPreferences(PINS, Context.MODE_PRIVATE)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun displayName(mediaId: String): String =
        if (mediaId.startsWith("${ContentResolver.SCHEME_CONTENT}:")) {
            runCatching {
                context.contentResolver.query(Uri.parse(mediaId), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: Uri.parse(mediaId).lastPathSegment.orEmpty()
        } else {
            runCatching { Paths.get(URI.create(mediaId)).fileName?.toString() }.getOrNull().orEmpty()
        }

    /** Background thread: the film as a servable file, from any Ordo path or a content:// of another app. */
    private fun source(mediaId: String, contentType: String): CastFileServer.Source {
        if (mediaId.startsWith("${ContentResolver.SCHEME_CONTENT}:")) {
            val uri = Uri.parse(mediaId)
            val resolver = context.contentResolver
            // The TV seeks with ranges, which need the size up front.
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0 }
                ?: throw java.io.IOException("Unknown file size")
            return CastFileServer.Source(size, contentType) { offset ->
                val descriptor = checkNotNull(resolver.openAssetFileDescriptor(uri, "r"))
                descriptor.createInputStream().also { it.channel.position(descriptor.startOffset + offset) }
            }
        }
        val path = Paths.get(URI.create(mediaId))
        return CastFileServer.Source(path.readAttributes(BasicFileAttributes::class.java).size(), contentType) { offset ->
            ChannelInputStream(path.newByteChannel(setOf(StandardOpenOption.READ)).apply { position(offset) })
        }
    }

    /** Background thread: SRT/VTT files beside the film as WebVTT, the device's language first. */
    private fun subtitles(mediaId: String, name: String): List<Pair<VideoCastFormats.Subtitle, CastFileServer.Source>> {
        if (mediaId.startsWith("${ContentResolver.SCHEME_CONTENT}:")) return emptyList()
        return runCatching {
            val folder = Paths.get(URI.create(mediaId)).parent ?: return emptyList()
            val siblings = folder.newDirectoryStream().use { stream -> stream.mapNotNull { it.fileName?.toString() } }
            VideoCastFormats.sidecarSubtitles(name, siblings, Locale.getDefault().language).mapNotNull { subtitle ->
                runCatching {
                    val file = folder.resolve(subtitle.fileName)
                    val bytes = readCapped(file) ?: return@runCatching null
                    val text = VideoCastFormats.decode(bytes)
                    val vtt = if (subtitle.fileName.endsWith(".srt", ignoreCase = true)) {
                        VideoCastFormats.srtToVtt(text)
                    } else {
                        text
                    }.toByteArray(Charsets.UTF_8)
                    subtitle to CastFileServer.Source(vtt.size.toLong(), "text/vtt; charset=utf-8") { offset ->
                        java.io.ByteArrayInputStream(vtt, offset.toInt(), vtt.size - offset.toInt())
                    }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** A subtitle file is small; anything over 4 MiB is not one. */
    private fun readCapped(path: Path): ByteArray? {
        path.newByteChannel(setOf(StandardOpenOption.READ)).use { channel ->
            val size = channel.size()
            if (size > MAX_SUBTITLE_BYTES) return null
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break
            return buffer.array().copyOf(buffer.position())
        }
    }

    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifEmpty { code }

    // As for music: the phone serves the film over Wi-Fi with the screen possibly off.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        @Suppress("DEPRECATION") // FULL_HIGH_PERF keeps Wi-Fi out of power save; its successor needs API 29.
        wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ordo:videocast")
            ?.apply { setReferenceCounted(false); acquire() }
        wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ordo:videocast")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock = null
    }

    private class ChannelInputStream(private val channel: SeekableByteChannel) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else channel.read(ByteBuffer.wrap(b, off, len))

        override fun close() = channel.close()
    }

    private class IdentityChanged : Exception()

    companion object {
        private const val PINS = "cast_device_pins"
        private const val SEEK_MAX = 1000f
        private const val TICK_MS = 500L
        private const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024
    }
}
