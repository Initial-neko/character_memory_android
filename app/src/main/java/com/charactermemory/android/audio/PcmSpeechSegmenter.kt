package com.charactermemory.android.audio

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Sample-clock VAD matching Core batch-call defaults, with 200 ms pre-roll and a 12 s bound. */
class PcmSpeechSegmenter {
    private val pcm = ByteArrayOutputStream()
    private val preRoll = ArrayDeque<Short>()
    private var speechSamples = 0
    private var silentSamples = 0
    private var started = false
    fun accept(samples: ShortArray, count: Int): ByteArray? {
        require(count in 1..samples.size)
        val voiced = sqrt((0 until count).sumOf { val x = samples[it].toDouble() / 32768; x * x } / count) >= 0.025
        if (!started && !voiced) {
            repeat(count) { preRoll.addLast(samples[it]); if (preRoll.size > 3200) preRoll.removeFirst() }
            return null
        }
        if (!started) { started = true; preRoll.forEach(::write); preRoll.clear() }
        repeat(count) { if (pcm.size() < 384000) write(samples[it]) }
        if (voiced) { speechSamples += count; silentSamples = 0 } else silentSamples += count
        if (silentSamples >= 14400 || pcm.size() >= 384000) {
            if (speechSamples >= 4000) return pcm.toByteArray()
            pcm.reset(); started = false; speechSamples = 0; silentSamples = 0
        }
        return null
    }
    fun finish(): ByteArray = if (speechSamples >= 4000) pcm.toByteArray() else byteArrayOf()
    private fun write(sample: Short) { pcm.write(sample.toInt() and 255); pcm.write((sample.toInt() ushr 8) and 255) }
}
