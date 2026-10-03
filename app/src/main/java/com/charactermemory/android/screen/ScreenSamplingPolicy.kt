package com.charactermemory.android.screen

/** Cheap change gate on 32x32 luma samples; the server owns paid Vision rate limits. */
object ScreenSamplingPolicy {
    fun significant(previous: IntArray?, current: IntArray, delta: Int = 24, fraction: Double = 0.12): Boolean {
        if (current.isEmpty()) return false
        if (previous == null || previous.size != current.size) return true
        val minimum = kotlin.math.ceil(current.size * fraction.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(1)
        var changed = 0
        for (index in current.indices) {
            if (kotlin.math.abs(current[index] - previous[index]) >= delta) {
                changed++
                if (changed >= minimum) return true
            }
        }
        return false
    }
}
