/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun validateAndGetMaximumIrLength(irs: Array<Array<FloatArray>>, blockSize: Int): Int {
    require(blockSize >= 16 && blockSize and (blockSize - 1) == 0) {
        "blockSize must be a power of two >= 16"
    }
    require(irs.isNotEmpty()) { "At least one input is required" }
    var maximumLength = 0
    var channel = 0
    while (channel < irs.size) {
        require(irs[channel].size == 2) { "Each input must have exactly two impulse responses" }
        var ear = 0
        while (ear < 2) {
            require(irs[channel][ear].isNotEmpty()) { "Impulse responses must not be empty" }
            maximumLength = maxOf(maximumLength, irs[channel][ear].size)
            ++ear
        }
        ++channel
    }
    return maximumLength
}

/**
 * Convolves each mono input with a left and right FIR and sums the results into a stereo output.
 *
 * This uses uniformly partitioned overlap-save (UPOLS) convolution. FIRs are split into [blockSize]-sample
 * partitions and transformed once; completed input blocks are transformed into a frequency-domain delay line, where
 * the partition products are accumulated before one inverse transform per ear. The output has exactly one block of
 * latency. With `P` partitions, its per-block cost is `N` forward FFTs, two inverse FFTs, and `2 * N * P` complex
 * multiply-accumulates, where `N` is the number of inputs.
 *
 * @param irs irs[input][ear] with ear 0 = left, 1 = right; any lengths >= 1 (zero-padded to whole partitions)
 * @param blockSize partition and hop size, a power of two >= 16
 */
class PartitionedConvolver(irs: Array<Array<FloatArray>>, val blockSize: Int) {
    private val maximumIrLength = validateAndGetMaximumIrLength(irs, blockSize)

    val inputs: Int = irs.size

    /** Output lags input by exactly this many frames (the block being collected). */
    val latencyFrames: Int = blockSize

    /** Results of the last [processFrame] call. */
    var outLeft: Float = 0f
        private set
    var outRight: Float = 0f
        private set

    private val fftSize = 2 * blockSize
    private val bins = blockSize + 1
    private val partitionCount = (maximumIrLength + blockSize - 1) / blockSize

    private val bitReversal = IntArray(fftSize)
    private val twiddleReal = FloatArray(fftSize / 2)
    private val twiddleImaginary = FloatArray(fftSize / 2)

    // Layout is channel, then partition, then bin. Real and imaginary parts are separate primitive arrays.
    private val irReal = Array(inputs * 2) { FloatArray(partitionCount * bins) }
    private val irImaginary = Array(inputs * 2) { FloatArray(partitionCount * bins) }
    private val delayReal = Array(inputs) { FloatArray(partitionCount * bins) }
    private val delayImaginary = Array(inputs) { FloatArray(partitionCount * bins) }

    private val previousInput = Array(inputs) { FloatArray(blockSize) }
    private val currentInput = Array(inputs) { FloatArray(blockSize) }
    private val outputLeft = FloatArray(blockSize)
    private val outputRight = FloatArray(blockSize)

    private val accumulatedReal = FloatArray(bins)
    private val accumulatedImaginary = FloatArray(bins)
    private val scratchReal = FloatArray(fftSize)
    private val scratchImaginary = FloatArray(fftSize)

    private var position = 0
    private var delayHead = 0

    init {
        prepareFftTables()
        prepareImpulseResponses(irs)
    }

    /** One frame: [inputs][0 until this.inputs] are the input samples; result in [outLeft]/[outRight]. */
    fun processFrame(inputs: FloatArray) {
        if (inputs.size < this.inputs) throw IllegalArgumentException("Too few input samples")

        var channel = 0
        while (channel < this.inputs) {
            currentInput[channel][position] = inputs[channel]
            ++channel
        }
        outLeft = outputLeft[position]
        outRight = outputRight[position]

        ++position
        if (position == blockSize) {
            processBlock()
            position = 0
        }
    }

    /** Forgets all history (silence in, silence out). */
    fun reset() {
        var channel = 0
        while (channel < inputs) {
            previousInput[channel].fill(0f)
            currentInput[channel].fill(0f)
            delayReal[channel].fill(0f)
            delayImaginary[channel].fill(0f)
            ++channel
        }
        outputLeft.fill(0f)
        outputRight.fill(0f)
        accumulatedReal.fill(0f)
        accumulatedImaginary.fill(0f)
        scratchReal.fill(0f)
        scratchImaginary.fill(0f)
        position = 0
        delayHead = 0
        outLeft = 0f
        outRight = 0f
    }

    private fun processBlock() {
        var channel = 0
        while (channel < inputs) {
            var sample = 0
            while (sample < blockSize) {
                scratchReal[sample] = previousInput[channel][sample]
                scratchReal[blockSize + sample] = currentInput[channel][sample]
                scratchImaginary[sample] = 0f
                scratchImaginary[blockSize + sample] = 0f
                previousInput[channel][sample] = currentInput[channel][sample]
                ++sample
            }
            fft(scratchReal, scratchImaginary, inverse = false)

            val delayOffset = delayHead * bins
            var bin = 0
            while (bin < bins) {
                delayReal[channel][delayOffset + bin] = scratchReal[bin]
                delayImaginary[channel][delayOffset + bin] = scratchImaginary[bin]
                ++bin
            }
            ++channel
        }

        accumulateEar(0, outputLeft)
        accumulateEar(1, outputRight)
        ++delayHead
        if (delayHead == partitionCount) delayHead = 0
    }

    private fun accumulateEar(ear: Int, output: FloatArray) {
        accumulatedReal.fill(0f)
        accumulatedImaginary.fill(0f)

        var channel = 0
        while (channel < inputs) {
            val responseReal = irReal[channel * 2 + ear]
            val responseImaginary = irImaginary[channel * 2 + ear]
            var partition = 0
            var delayPartition = delayHead
            while (partition < partitionCount) {
                val inputOffset = delayPartition * bins
                val responseOffset = partition * bins
                var bin = 0
                while (bin < bins) {
                    val inputReal = delayReal[channel][inputOffset + bin]
                    val inputImaginary = delayImaginary[channel][inputOffset + bin]
                    val filterReal = responseReal[responseOffset + bin]
                    val filterImaginary = responseImaginary[responseOffset + bin]
                    accumulatedReal[bin] += inputReal * filterReal - inputImaginary * filterImaginary
                    accumulatedImaginary[bin] += inputReal * filterImaginary + inputImaginary * filterReal
                    ++bin
                }
                ++partition
                --delayPartition
                if (delayPartition < 0) delayPartition = partitionCount - 1
            }
            ++channel
        }

        scratchReal[0] = accumulatedReal[0]
        scratchImaginary[0] = 0f
        scratchReal[blockSize] = accumulatedReal[blockSize]
        scratchImaginary[blockSize] = 0f
        var bin = 1
        while (bin < blockSize) {
            scratchReal[bin] = accumulatedReal[bin]
            scratchImaginary[bin] = accumulatedImaginary[bin]
            scratchReal[fftSize - bin] = accumulatedReal[bin]
            scratchImaginary[fftSize - bin] = -accumulatedImaginary[bin]
            ++bin
        }
        fft(scratchReal, scratchImaginary, inverse = true)

        var sample = 0
        while (sample < blockSize) {
            output[sample] = scratchReal[blockSize + sample]
            ++sample
        }
    }

    private fun prepareFftTables() {
        val bits = Integer.numberOfTrailingZeros(fftSize)
        var index = 0
        while (index < fftSize) {
            bitReversal[index] = Integer.reverse(index) ushr (32 - bits)
            ++index
        }
        index = 0
        while (index < fftSize / 2) {
            val angle = -2.0 * PI * index / fftSize
            twiddleReal[index] = cos(angle).toFloat()
            twiddleImaginary[index] = sin(angle).toFloat()
            ++index
        }
    }

    private fun prepareImpulseResponses(irs: Array<Array<FloatArray>>) {
        var channel = 0
        while (channel < inputs) {
            var ear = 0
            while (ear < 2) {
                val response = irs[channel][ear]
                val transformedReal = irReal[channel * 2 + ear]
                val transformedImaginary = irImaginary[channel * 2 + ear]
                var partition = 0
                while (partition < partitionCount) {
                    scratchReal.fill(0f)
                    scratchImaginary.fill(0f)
                    val responseStart = partition * blockSize
                    var sample = 0
                    while (sample < blockSize && responseStart + sample < response.size) {
                        scratchReal[sample] = response[responseStart + sample]
                        ++sample
                    }
                    fft(scratchReal, scratchImaginary, inverse = false)
                    val offset = partition * bins
                    var bin = 0
                    while (bin < bins) {
                        transformedReal[offset + bin] = scratchReal[bin]
                        transformedImaginary[offset + bin] = scratchImaginary[bin]
                        ++bin
                    }
                    ++partition
                }
                ++ear
            }
            ++channel
        }
        scratchReal.fill(0f)
        scratchImaginary.fill(0f)
    }

    private fun fft(real: FloatArray, imaginary: FloatArray, inverse: Boolean) {
        var index = 0
        while (index < fftSize) {
            val reversed = bitReversal[index]
            if (reversed > index) {
                var temporary = real[index]
                real[index] = real[reversed]
                real[reversed] = temporary
                temporary = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = temporary
            }
            ++index
        }

        var length = 2
        while (length <= fftSize) {
            val halfLength = length / 2
            val twiddleStep = fftSize / length
            var start = 0
            while (start < fftSize) {
                var offset = 0
                while (offset < halfLength) {
                    val twiddleIndex = offset * twiddleStep
                    val twiddleImaginaryValue =
                        if (inverse) -twiddleImaginary[twiddleIndex] else twiddleImaginary[twiddleIndex]
                    val oddIndex = start + offset + halfLength
                    val oddReal = real[oddIndex]
                    val oddImaginary = imaginary[oddIndex]
                    val productReal =
                        oddReal * twiddleReal[twiddleIndex] - oddImaginary * twiddleImaginaryValue
                    val productImaginary =
                        oddReal * twiddleImaginaryValue + oddImaginary * twiddleReal[twiddleIndex]
                    val evenIndex = start + offset
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]
                    real[evenIndex] = evenReal + productReal
                    imaginary[evenIndex] = evenImaginary + productImaginary
                    real[oddIndex] = evenReal - productReal
                    imaginary[oddIndex] = evenImaginary - productImaginary
                    ++offset
                }
                start += length
            }
            length = length shl 1
        }

        if (inverse) {
            val scale = 1f / fftSize
            index = 0
            while (index < fftSize) {
                real[index] *= scale
                imaginary[index] *= scale
                ++index
            }
        }
    }

}
