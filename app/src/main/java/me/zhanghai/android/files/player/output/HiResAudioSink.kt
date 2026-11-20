/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.output

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Two sinks behind one: the mixing sink runs Ordo's stages and writes 16-bit, as before. The pure sink (bit-perfect
 * mode, API 34+) has no stages and writes hi-res as float, which [PackingOutputProvider] packs into the DAC's integer
 * format. DefaultAudioSink fixes float output at build time, hence two. The switch happens only in [configure];
 * setters go to both so either is ready.
 */
@UnstableApi
class HiResAudioSink(
    context: Context,
    processors: Array<AudioProcessor>,
    private val output: HiResOutput
) : AudioSink {
    private val mixing: AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(processors).build()
    private val pure: AudioSink? = if (output.allowsBitPerfect && Build.VERSION.SDK_INT >= OutputPolicy.MIN_SDK) {
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setAudioOutputProvider(PackingOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build(), output))
            .build()
    } else null
    private val sinks = listOfNotNull(mixing, pure)
    private var current = mixing
    private var playing = false

    /**
     * The pure sink only for a song the DAC takes bit-perfect; every other song keeps Ordo's stages. Asked first with
     * the PCM a decoder could give (float or 16-bit), so a song of up to 16 bits is decoded to 16 bits and a deeper
     * one to float; then with the decoded PCM.
     */
    private fun wanted(format: Format): AudioSink {
        if (pure == null || !output.bitPerfectMode || MimeTypes.AUDIO_RAW != format.sampleMimeType) return mixing
        val depth = output.sourceFor(format.sampleRate, format.channelCount)
        val writes = OutputPolicy.pureWriteEncoding(
            format.pcmEncoding, Util.isEncodingHighResolutionPcm(format.pcmEncoding)
        )
        if (writes != OutputPolicy.writeEncodingFor(depth)) return mixing
        return if (output.acceptsBitPerfect(TrackFormat(format.sampleRate, writes, format.channelCount), depth)) pure
        else mixing
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val next = wanted(audioSinkConfig.format)
        if (next !== current) {
            current.reset()
            current = next
            if (playing) next.play()
        }
        val format = audioSinkConfig.format
        output.onConfigure(
            if (MimeTypes.AUDIO_RAW == format.sampleMimeType) {
                val writes = if (current === pure && Util.isEncodingHighResolutionPcm(format.pcmEncoding)) {
                    AudioFormat.ENCODING_PCM_FLOAT
                } else AudioFormat.ENCODING_PCM_16BIT
                TrackFormat(format.sampleRate, writes, format.channelCount)
            } else null,
            output.sourceFor(format.sampleRate, format.channelCount)
        )
        current.configure(audioSinkConfig)
    }

    // Asked when a decoder is set up, so it decodes to float exactly when the pure sink will play it.
    override fun supportsFormat(format: Format): Boolean = wanted(format).supportsFormat(format)
    override fun getFormatSupport(format: Format): Int = wanted(format).getFormatSupport(format)
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        wanted(format).getFormatOffloadSupport(format)

    override fun play() {
        playing = true
        current.play()
    }

    override fun pause() {
        playing = false
        current.pause()
    }

    override fun reset() {
        playing = false
        sinks.forEach { it.reset() }
    }

    override fun release() = sinks.forEach { it.release() }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = current.getCurrentPositionUs(sourceEnded)
    override fun handleDiscontinuity() = current.handleDiscontinuity()
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean =
        current.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    override fun playToEndOfStream() = current.playToEndOfStream()
    override fun isEnded(): Boolean = current.isEnded
    override fun hasPendingData(): Boolean = current.hasPendingData()
    override fun getPlaybackParameters(): PlaybackParameters = current.playbackParameters
    override fun getSkipSilenceEnabled(): Boolean = current.skipSilenceEnabled
    override fun getAudioAttributes(): AudioAttributes? = current.audioAttributes
    override fun getAudioTrackBufferSizeUs(): Long = current.audioTrackBufferSizeUs
    override fun getAudioCapabilities(): AudioCapabilities? = current.audioCapabilities
    override fun flush() = current.flush()

    override fun setListener(listener: AudioSink.Listener) = sinks.forEach { it.setListener(listener) }
    override fun setPlayerId(playerId: PlayerId?) = sinks.forEach { it.setPlayerId(playerId) }
    override fun setClock(clock: Clock) = sinks.forEach { it.setClock(clock) }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        sinks.forEach { it.setPlaybackParameters(playbackParameters) }
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        sinks.forEach { it.setSkipSilenceEnabled(skipSilenceEnabled) }
    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        sinks.forEach { it.setAudioAttributes(audioAttributes) }
    override fun setAudioSessionId(audioSessionId: Int) = sinks.forEach { it.setAudioSessionId(audioSessionId) }
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = sinks.forEach { it.setAuxEffectInfo(auxEffectInfo) }
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) =
        sinks.forEach { it.setPreferredDevice(audioDeviceInfo) }
    override fun setVirtualDeviceId(virtualDeviceId: Int) = sinks.forEach { it.setVirtualDeviceId(virtualDeviceId) }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) =
        sinks.forEach { it.setOutputStreamOffsetUs(outputStreamOffsetUs) }
    override fun enableTunnelingV21() = sinks.forEach { it.enableTunnelingV21() }
    override fun disableTunneling() = sinks.forEach { it.disableTunneling() }
    override fun setOffloadMode(offloadMode: Int) = sinks.forEach { it.setOffloadMode(offloadMode) }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        sinks.forEach { it.setOffloadDelayPadding(delayInFrames, paddingInFrames) }
    override fun setVolume(volume: Float) = sinks.forEach { it.setVolume(volume) }
}

/** Opens the AudioTrack in the DAC's integer format when [HiResOutput] chose one, and packs the float into it. */
@UnstableApi
private class PackingOutputProvider(
    delegate: AudioOutputProvider,
    private val output: HiResOutput
) : ForwardingAudioOutputProvider(delegate) {
    // The sink keeps seeing float (its start ramp and frame counts work on float bytes); only the AudioTrack under
    // the wrapper is opened in the DAC's integer format.
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val target = output.packFloatTo
        if (config.encoding != C.ENCODING_PCM_FLOAT || target == null || !PcmPacking.packs(target)) {
            return super.getAudioOutput(config)
        }
        val channels = Integer.bitCount(config.channelMask)
        val frames = config.bufferSize / (Float.SIZE_BYTES * channels)
        val packed = config.buildUpon()
            .setEncoding(target)
            .setBufferSize(frames * PcmPacking.bytesPerSample(target) * channels)
            .build()
        return PackingAudioOutput(super.getAudioOutput(packed), target)
    }
}

/** Float in, packed integers out; the float buffer advances by the frames the track took. */
@UnstableApi
private class PackingAudioOutput(delegate: AudioOutput, private val encoding: Int) : ForwardingAudioOutput(delegate) {
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val samples = buffer.remaining() / Float.SIZE_BYTES
        val bytes = samples * PcmPacking.bytesPerSample(encoding)
        if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        scratch.clear()
        PcmPacking.pack(buffer.duplicate().order(ByteOrder.nativeOrder()), scratch, encoding)
        scratch.flip()
        val handled = super.write(scratch, encodedAccessUnitCount, presentationTimeUs)
        val written = scratch.position() / PcmPacking.bytesPerSample(encoding)
        buffer.position(buffer.position() + written * Float.SIZE_BYTES)
        return handled
    }
}

/** Float PCM to 32-bit or packed 24-bit integers; exact for samples that came from integers of up to 24 bits. */
object PcmPacking {
    fun packs(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_24BIT

    fun bytesPerSample(encoding: Int): Int = if (encoding == C.ENCODING_PCM_24BIT) 3 else 4

    /** Reads [input] to its limit (native-order floats) and appends to [output] in native order. */
    fun pack(input: ByteBuffer, output: ByteBuffer, encoding: Int) {
        val twentyFour = encoding == C.ENCODING_PCM_24BIT
        val scale = if (twentyFour) 8_388_608.0 else 2_147_483_648.0
        val max = scale - 1
        while (input.remaining() >= Float.SIZE_BYTES) {
            val value = Math.rint((input.float * scale).coerceIn(-scale, max)).toInt()
            if (twentyFour) {
                // Little-endian, as every Android ABI is.
                output.put(value.toByte())
                output.put((value shr 8).toByte())
                output.put((value shr 16).toByte())
            } else {
                output.putInt(value)
            }
        }
    }
}
