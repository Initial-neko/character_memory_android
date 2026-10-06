package com.charactermemory.android.camera

import kotlin.math.max
import kotlin.math.roundToInt

/** Pixels are rotated explicitly, so Core need not interpret camera EXIF. */
object CameraFramePolicy {
    fun rotation(sensor: Int, display: Int, front: Boolean): Int {
        return (sensor - display * (if (front) 1 else -1) + 360) % 360
    }
    /** TextureView already applies sensor orientation; compensate only for the display. */
    fun previewRotation(display: Int): Int = (360 - display) % 360
    fun previewBufferSize(width: Int, height: Int, sensor: Int): Pair<Int, Int> =
        if (sensor % 180 == 0) width to height else height to width
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val ratio = minOf(1.0, 1080.0 / max(width, height))
        return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
    }
    fun choose(sizes: List<Pair<Int, Int>>): Pair<Int, Int> = sizes
        .filter { it.first > 0 && it.second > 0 && it.first.toLong() * it.second <= 4_000_000 }
        .maxByOrNull { it.first.toLong() * it.second } ?: error("摄像头没有可用的受限输出尺寸")
}
