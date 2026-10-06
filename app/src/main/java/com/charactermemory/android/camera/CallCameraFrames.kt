package com.charactermemory.android.camera

/** One bounded recent frame, owned by a camera epoch and the original call. No image storage. */
internal class CallCameraFrames {
    private data class Frame(val generation: Long, val epoch: Long, val callStart: Long,
        val capturedAt: Long, val bytes: ByteArray)
    private var current: Frame? = null

    @Synchronized fun update(generation: Long, epoch: Long, callStart: Long, now: Long, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024)
        current = Frame(generation, epoch, callStart, now, bytes.copyOf())
    }
    @Synchronized fun latest(generation: Long, epoch: Long, callStart: Long, now: Long): ByteArray? =
        current?.takeIf { it.generation == generation && it.epoch == epoch && it.callStart == callStart &&
            now - it.capturedAt in 0..10_000 }?.bytes?.copyOf()

    @Synchronized fun clear() { current = null }
}
