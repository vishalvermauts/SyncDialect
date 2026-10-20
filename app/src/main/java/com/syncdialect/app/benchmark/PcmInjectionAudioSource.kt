package com.syncdialect.app.benchmark

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-only implementation of [AudioSource] that feeds pre-recorded PCM frames
 * at their strict original real-time rate (20ms per 320-sample frame at 16kHz)
 * using the Android monotonic clock ([SystemClock.elapsedRealtimeNanos]).
 *
 * Measures scheduling drift and underruns, and maps audio sample positions
 * directly to the [SystemClock.elapsedRealtimeNanos] monotonic clock domain.
 */
class PcmInjectionAudioSource(
    private val pcmBytes: ByteArray,
    val sampleRate: Int = 16000,
    private val frameSizeSamples: Int = 320, // 20ms at 16kHz
    private val appendTrailingSilenceMs: Int = 1200, // Provides trailing silence for production VAD endpointing
    private val onFrameInjected: ((frameIndex: Long, monotonicNano: Long, sampleOffset: Long) -> Unit)? = null
) : AudioSource {

    private val bytesPerSample = 2 // 16-bit PCM
    private val frameSizeBytes = frameSizeSamples * bytesPerSample // 640 bytes
    private val frameIntervalNanos = (frameSizeSamples.toLong() * 1_000_000_000L) / sampleRate // 20_000_000 ns

    @Volatile var injectionStartRealtimeNanos: Long = 0L
        private set

    private val isRunning = AtomicBoolean(false)
    private var pcmOffset = 0
    private var trailingSilenceRemainingBytes = 0
    private var framesDelivered = 0L

    // Telemetry
    private var maxDriftNanos = 0L
    private var totalAbsDriftNanos = 0L
    private var underrunCount = 0
    private var totalBytesRead = 0L

    override fun start() {
        if (isRunning.getAndSet(true)) return
        injectionStartRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        pcmOffset = 0
        trailingSilenceRemainingBytes = (appendTrailingSilenceMs * sampleRate * bytesPerSample) / 1000
        framesDelivered = 0L
        maxDriftNanos = 0L
        totalAbsDriftNanos = 0L
        underrunCount = 0
        totalBytesRead = 0L

        Log.i(
            "PcmInjectionAudioSource",
            "Started injection: ${pcmBytes.size} bytes ($sampleRate Hz), " +
                    "startNanos=$injectionStartRealtimeNanos, trailingSilence=${appendTrailingSilenceMs}ms"
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isRunning.get()) return -1

        // Strict real-time pacing using Android monotonic clock
        val targetMonotonicNano = injectionStartRealtimeNanos + (framesDelivered * frameIntervalNanos)
        val currentMonotonicNano = SystemClock.elapsedRealtimeNanos()

        if (currentMonotonicNano < targetMonotonicNano) {
            val waitNanos = targetMonotonicNano - currentMonotonicNano
            val waitMillis = waitNanos / 1_000_000L
            val waitNanosRem = (waitNanos % 1_000_000L).toInt()
            if (waitMillis > 0 || waitNanosRem > 0) {
                try {
                    Thread.sleep(waitMillis, waitNanosRem)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
        }

        // Measure scheduling drift after waking
        val actualReleaseNano = SystemClock.elapsedRealtimeNanos()
        val driftNanos = actualReleaseNano - targetMonotonicNano
        if (driftNanos > maxDriftNanos) {
            maxDriftNanos = driftNanos
        }
        totalAbsDriftNanos += kotlin.math.abs(driftNanos)

        // Read from recorded PCM first, then synthesize trailing silence to allow natural endpointing
        val bytesAvailableFromPcm = pcmBytes.size - pcmOffset
        val bytesToCopy = kotlin.math.min(length, frameSizeBytes)

        val bytesDeliveredThisCall: Int
        if (bytesAvailableFromPcm >= bytesToCopy) {
            System.arraycopy(pcmBytes, pcmOffset, buffer, offset, bytesToCopy)
            pcmOffset += bytesToCopy
            bytesDeliveredThisCall = bytesToCopy
        } else if (bytesAvailableFromPcm > 0) {
            // Partial PCM remainder + partial silence pad
            System.arraycopy(pcmBytes, pcmOffset, buffer, offset, bytesAvailableFromPcm)
            java.util.Arrays.fill(buffer, offset + bytesAvailableFromPcm, offset + bytesToCopy, 0.toByte())
            pcmOffset += bytesAvailableFromPcm
            bytesDeliveredThisCall = bytesToCopy
        } else if (trailingSilenceRemainingBytes > 0) {
            // Trailing silence period
            val silenceBytes = kotlin.math.min(bytesToCopy, trailingSilenceRemainingBytes)
            java.util.Arrays.fill(buffer, offset, offset + silenceBytes, 0.toByte())
            trailingSilenceRemainingBytes -= silenceBytes
            bytesDeliveredThisCall = silenceBytes
        } else {
            // Exhausted both PCM and trailing silence. If pipeline continues reading without stopping, record underrun.
            underrunCount++
            return 0 // End of stream for this recording
        }

        val currentSampleOffset = (framesDelivered * frameSizeSamples)
        onFrameInjected?.invoke(framesDelivered, actualReleaseNano, currentSampleOffset)

        framesDelivered++
        totalBytesRead += bytesDeliveredThisCall
        return bytesDeliveredThisCall
    }

    override fun stop() {
        isRunning.set(false)
        Log.i(
            "PcmInjectionAudioSource",
            "Stopped injection. Stats: frames=$framesDelivered, maxDrift=${maxDriftNanos / 1_000_000.0}ms, " +
                    "avgDrift=${if (framesDelivered > 0) (totalAbsDriftNanos / framesDelivered) / 1000.0 else 0.0}us, " +
                    "underruns=$underrunCount"
        )
    }

    override fun release() {
        isRunning.set(false)
    }

    override fun getStats(): AudioSourceStats {
        return AudioSourceStats(
            maxSchedulingDriftNanos = maxDriftNanos,
            avgSchedulingDriftNanos = if (framesDelivered > 0) totalAbsDriftNanos / framesDelivered else 0L,
            audioUnderruns = underrunCount,
            framesPaced = framesDelivered,
            totalBytesRead = totalBytesRead
        )
    }

    /**
     * Maps a sample offset within this recording directly into the
     * [SystemClock.elapsedRealtimeNanos] monotonic clock domain.
     */
    fun sampleToMonotonicNanos(sampleOffset: Long): Long {
        if (injectionStartRealtimeNanos <= 0L) {
            throw IllegalStateException("Injection has not started yet")
        }
        return injectionStartRealtimeNanos + ((sampleOffset * 1_000_000_000L) / sampleRate)
    }
}
