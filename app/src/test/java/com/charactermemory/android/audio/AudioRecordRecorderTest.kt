package com.charactermemory.android.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioRecordRecorderTest {
    @Test
    fun failedInitializationReleasesInputWithoutStartingIt() {
        val input = FakeAudioInput(initialized = false) { _, _, _ -> error("must not read") }
        val recorder = AudioRecordRecorder(AudioInputFactory { input })

        assertThrows<IllegalStateException> { runBlocking { recorder.capture() } }

        assertEquals(0, input.startCalls)
        assertEquals(1, input.releaseCalls)
    }

    @Test
    fun maximumCaptureRequestsOnlyRemainingSamplesAndReturnsLittleEndianPcm() {
        val input = FakeAudioInput { buffer, offset, size ->
            repeat(size) { buffer[offset + it] = 0x1234 }
            size
        }
        val recorder = AudioRecordRecorder(AudioInputFactory { input })
        val durations = mutableListOf<Long>()

        val pcm = runBlocking { recorder.capture { durations += it } }

        assertEquals(480_000, input.readSizes.sum())
        assertEquals(288, input.readSizes.last())
        assertEquals(960_000, pcm.size)
        assertEquals(30_000L, durations.last())
        assertArrayEquals(byteArrayOf(0x34, 0x12), pcm.copyOfRange(0, 2))
        assertEquals(1, input.releaseCalls)
        assertEquals(1, input.stopCalls)
    }

    @Test
    fun explicitStopReturnsCapturedPcmAndRepeatedTerminationIsIdempotent() {
        lateinit var recorder: AudioRecordRecorder
        val input = FakeAudioInput { buffer, offset, _ ->
            buffer[offset] = 0x1234
            buffer[offset + 1] = 0x00AB
            buffer[offset + 2] = 0x7F00
            3
        }
        recorder = AudioRecordRecorder(AudioInputFactory { input })

        val pcm = runBlocking {
            recorder.capture {
                recorder.stop()
                recorder.stop()
            }
        }
        recorder.cancel()

        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xAB.toByte(), 0x00, 0x00, 0x7F), pcm)
        assertEquals(1, input.stopCalls)
        assertEquals(1, input.releaseCalls)
    }

    @Test
    fun cancelUnblocksBlockingReadDiscardsPcmAndReleasesOnce() {
        val input = BlockingAudioInput()
        val recorder = AudioRecordRecorder(AudioInputFactory { input })
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit<ByteArray> { runBlocking { recorder.capture() } }
            assertTrue(input.readEntered.await(2, TimeUnit.SECONDS))

            recorder.cancel()

            val thrown = try {
                result.get(2, TimeUnit.SECONDS)
                fail("cancelled capture must not return PCM")
                null
            } catch (error: ExecutionException) {
                error.cause
            }
            assertTrue(thrown is CancellationException)
            assertEquals(1, input.stopCalls)
            assertEquals(1, input.releaseCalls)
        } finally {
            recorder.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun releaseWaitsUntilConcurrentNativeStopReturns() {
        val input = GatedStopAudioInput()
        val recorder = AudioRecordRecorder(AudioInputFactory { input })
        val captureExecutor = Executors.newSingleThreadExecutor()
        val stopExecutor = Executors.newSingleThreadExecutor()

        try {
            val capture = captureExecutor.submit<ByteArray> { runBlocking { recorder.capture() } }
            assertTrue(input.readEntered.await(2, TimeUnit.SECONDS))
            val cancel = stopExecutor.submit { recorder.cancel() }
            assertTrue(input.stopEntered.await(2, TimeUnit.SECONDS))
            assertTrue(input.readReturned.await(2, TimeUnit.SECONDS))

            try {
                capture.get(250, TimeUnit.MILLISECONDS)
                fail("capture must wait for the in-flight stop before cleanup")
            } catch (_: TimeoutException) {
                // The fake stop is still held open, so capture must still be waiting.
            }
            assertTrue("release must not overlap stop", !input.releaseEntered.await(250, TimeUnit.MILLISECONDS))
            assertEquals(0, input.releaseCalls.get())

            input.allowStopReturn.countDown()
            cancel.get(2, TimeUnit.SECONDS)
            val failure = try {
                capture.get(2, TimeUnit.SECONDS)
                fail("cancelled capture must not return PCM")
                null
            } catch (error: ExecutionException) {
                error.cause
            }
            assertTrue(failure is CancellationException)
            assertEquals(1, input.stopCalls.get())
            assertEquals(1, input.releaseCalls.get())
            assertTrue(!input.releasedBeforeStopReturned.get())
        } finally {
            input.allowStopReturn.countDown()
            recorder.cancel()
            captureExecutor.shutdownNow()
            stopExecutor.shutdownNow()
        }
    }

    @Test
    fun stopUnblocksReadAndKeepsSamplesReturnedByFinalRead() {
        val input = FinalReadAfterStopAudioInput()
        val recorder = AudioRecordRecorder(AudioInputFactory { input })
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit<ByteArray> { runBlocking { recorder.capture() } }
            assertTrue(input.readEntered.await(2, TimeUnit.SECONDS))

            recorder.stop()

            assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03), result.get(2, TimeUnit.SECONDS))
            assertEquals(1, input.stopCalls.get())
            assertEquals(1, input.releaseCalls.get())
        } finally {
            recorder.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun negativeReadFailsAndReleasesInputOnce() {
        val input = FakeAudioInput { _, _, _ -> -1 }
        val recorder = AudioRecordRecorder(AudioInputFactory { input })

        assertThrows<IOException> { runBlocking { recorder.capture() } }

        assertEquals(1, input.stopCalls)
        assertEquals(1, input.releaseCalls)
    }

    @Test
    fun zeroReadFailsInsteadOfReturningEmptyAudioOrSpinning() {
        val input = FakeAudioInput { _, _, _ -> 0 }
        val recorder = AudioRecordRecorder(AudioInputFactory { input })

        assertThrows<IOException> { runBlocking { recorder.capture() } }

        assertEquals(1, input.releaseCalls)
    }

    @Test
    fun thrownReadErrorReleasesInputOnce() {
        val input = FakeAudioInput { _, _, _ -> throw IllegalStateException("read failed") }
        val recorder = AudioRecordRecorder(AudioInputFactory { input })

        assertThrows<IllegalStateException> { runBlocking { recorder.capture() } }

        assertEquals(1, input.stopCalls)
        assertEquals(1, input.releaseCalls)
    }

    @Test
    fun cancelAfterPartialReadThrowsAndDoesNotReturnCapturedBytes() {
        lateinit var recorder: AudioRecordRecorder
        val input = FakeAudioInput { buffer, offset, _ ->
            buffer[offset] = 0x1234
            1
        }
        recorder = AudioRecordRecorder(AudioInputFactory { input })

        assertThrows<CancellationException> {
            runBlocking { recorder.capture { recorder.cancel() } }
        }

        assertEquals(1, input.stopCalls)
        assertEquals(1, input.releaseCalls)
    }

    @Test
    fun factoryFailureDoesNotLeaveCaptureReusableOrCreateAudio() {
        val createCalls = AtomicInteger()
        val recorder = AudioRecordRecorder(AudioInputFactory {
            createCalls.incrementAndGet()
            throw IllegalStateException("unavailable")
        })

        assertThrows<IllegalStateException> { runBlocking { recorder.capture() } }
        assertThrows<IllegalStateException> { runBlocking { recorder.capture() } }

        assertEquals(1, createCalls.get())
    }

    @Test
    fun unavailableAudioInputFailsWithoutReturningEmptyPcm() {
        val recorder = AudioRecordRecorder(AudioInputFactory { null })

        assertThrows<IllegalStateException> { runBlocking { recorder.capture() } }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    private open class FakeAudioInput(
        override val initialized: Boolean = true,
        private val readAction: (ShortArray, Int, Int) -> Int
    ) : AudioInput {
        val readSizes = mutableListOf<Int>()
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0

        override fun start() {
            startCalls++
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            readSizes += size
            return readAction(buffer, offset, size)
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private class BlockingAudioInput : AudioInput {
        override val initialized = true
        val readEntered = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        override fun start() = Unit

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            readEntered.countDown()
            stopped.await(2, TimeUnit.SECONDS)
            return -1
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            stopped.countDown()
        }

        override fun release() {
            releaseCalls.incrementAndGet()
        }
    }

    private class FinalReadAfterStopAudioInput : AudioInput {
        override val initialized = true
        val readEntered = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        override fun start() = Unit

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            readEntered.countDown()
            if (!stopped.await(2, TimeUnit.SECONDS)) return -1
            buffer[offset] = 0x0102
            buffer[offset + 1] = 0x0304
            return 2
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            stopped.countDown()
        }

        override fun release() {
            releaseCalls.incrementAndGet()
        }
    }

    private class GatedStopAudioInput : AudioInput {
        override val initialized = true
        val readEntered = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val allowStopReturn = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        private val wakeRead = CountDownLatch(1)
        private val stopReturned = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val releasedBeforeStopReturned = AtomicBoolean(false)

        override fun start() = Unit

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            readEntered.countDown()
            wakeRead.await(2, TimeUnit.SECONDS)
            readReturned.countDown()
            return -1
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            stopEntered.countDown()
            wakeRead.countDown()
            allowStopReturn.await(2, TimeUnit.SECONDS)
            stopReturned.countDown()
        }

        override fun release() {
            if (stopReturned.count > 0) releasedBeforeStopReturned.set(true)
            releaseCalls.incrementAndGet()
            releaseEntered.countDown()
        }
    }
}
