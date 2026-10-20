package com.syncdialect.app.benchmark

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Production implementation of [AudioSource] delegating directly to Android's hardware [AudioRecord].
 */
class MicAudioSource(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) : AudioSource {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRunning = false
    private var totalBytes: Long = 0L

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isRunning) return
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 4
        )
        audioRecord = record

        if (record.state == AudioRecord.STATE_INITIALIZED) {
            record.startRecording()
            isRunning = true
            totalBytes = 0L
            Log.d("MicAudioSource", "Hardware AudioRecord recording started (sr=$sampleRate, buffer=${minBufferSize * 4})")
        } else {
            Log.e("MicAudioSource", "AudioRecord failed to initialize. Check RECORD_AUDIO permission.")
            isRunning = false
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val record = audioRecord ?: return -1
        if (!isRunning) return -1
        val bytesRead = record.read(buffer, offset, length)
        if (bytesRead > 0) {
            totalBytes += bytesRead
        }
        return bytesRead
    }

    override fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w("MicAudioSource", "stop() error: ${e.message}")
        }
    }

    override fun release() {
        isRunning = false
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("MicAudioSource", "release() error: ${e.message}")
        }
        audioRecord = null
    }

    override fun getStats(): AudioSourceStats {
        return AudioSourceStats(
            maxSchedulingDriftNanos = 0L,
            avgSchedulingDriftNanos = 0L,
            audioUnderruns = 0,
            framesPaced = if (sampleRate > 0) totalBytes / (320 * 2) else 0L,
            totalBytesRead = totalBytes
        )
    }
}
