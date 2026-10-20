package com.syncdialect.app.benchmark

/**
 * Common abstraction for audio capture to allow replacing hardware AudioRecord
 * with prerecorded PCM for reproducible, automated latency benchmarking.
 */
interface AudioSource : AutoCloseable {
    /**
     * Initializes and starts the audio source.
     */
    fun start()

    /**
     * Reads up to [length] bytes of 16-bit mono 16kHz PCM audio into [buffer] starting at [offset].
     * Returns the number of bytes read, or negative on error / EOS.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Stops audio capture or injection.
     */
    fun stop()

    /**
     * Releases all associated hardware handles or in-memory resources.
     */
    fun release()

    /**
     * Returns audio pacing, drift, and underrun statistics.
     */
    fun getStats(): AudioSourceStats = AudioSourceStats()

    override fun close() {
        stop()
        release()
    }
}

/**
 * Real-time audio delivery telemetry.
 *
 * @param maxSchedulingDriftNanos Maximum positive difference between actual frame release time
 *                                and monotonic target frame time.
 * @param avgSchedulingDriftNanos Average scheduling drift per frame in nanoseconds.
 * @param audioUnderruns Number of times the consumer requested frames when none were ready.
 * @param framesPaced Total number of 20ms audio frames delivered to the pipeline.
 * @param totalBytesRead Total bytes consumed.
 */
data class AudioSourceStats(
    val maxSchedulingDriftNanos: Long = 0L,
    val avgSchedulingDriftNanos: Long = 0L,
    val audioUnderruns: Int = 0,
    val framesPaced: Long = 0L,
    val totalBytesRead: Long = 0L
)
