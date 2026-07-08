package com.syncdialect.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.ByteArrayOutputStream
import java.util.Arrays

class AudioRecorderHelper(private val prefs: AppPreferences) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private val sampleRate = 16000

    @Volatile var isMuted = false

    @SuppressLint("MissingPermission")
    fun startContinuousRecording(onChunkReady: (ByteArray) -> Unit) {
        if (isRecording) {
            stopRecording()
        }
        isMuted = false

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4
        )

        isRecording = true
        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord?.startRecording()
        } else {
            Log.e("AudioRecorder", "AudioRecord failed to initialize. Check permissions.")
            isRecording = false
            return
        }

        Thread {
            val vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_320)
                .setMode(Mode.VERY_AGGRESSIVE)
                .build()

            val frameSizeSamples = 320 // 20ms at 16kHz
            val frameSizeBytes = frameSizeSamples * 2 // 16-bit PCM = 2 bytes per sample
            val buffer = ByteArray(frameSizeBytes)
            var chunkStream = ByteArrayOutputStream()
            var silenceFrames = 0
            var speechFrames = 0
            val minChunkBytes = 16000 // 1 second minimum
            val maxChunkBytes = 96000  // 3 seconds maximum — targets ~1.5s latency
            val minSpeechFrames = 10   // Require 200ms (10 * 20ms) of VAD-confirmed speech

            while (isRecording) {
                try {
                    val record = audioRecord ?: break
                    var readSum = 0
                    while (readSum < frameSizeBytes && isRecording) {
                        val read = record.read(buffer, readSum, frameSizeBytes - readSum)
                        if (read < 0) break
                        readSum += read
                    }
                    
                    if (readSum == frameSizeBytes && isRecording) {
                        if (isMuted) {
                            chunkStream.reset()
                            silenceFrames = 0
                            speechFrames = 0
                            continue
                        }
                        
                        val isSpeech = vad.isSpeech(buffer)

                        if (isSpeech) {
                            speechFrames++
                            silenceFrames = 0
                        } else {
                            silenceFrames++
                        }

                        // Collect audio if speech has been detected
                        if (speechFrames > 0 || isSpeech) {
                             chunkStream.write(buffer, 0, frameSizeBytes)
                        }

                        // Auto-flush if chunk exceeds 10 seconds (continuous long speech)
                        val shouldForceFlush = chunkStream.size() >= maxChunkBytes && speechFrames >= minSpeechFrames

                        // If enough silence and we have some audio, yield the chunk
                        if ((silenceFrames >= prefs.vadWaitFrames && chunkStream.size() >= 8000) || shouldForceFlush) {
                            if (speechFrames >= minSpeechFrames) { // Require at least 300ms of actual speech
                                var chunkData = chunkStream.toByteArray()
                                // Energy gate: compute RMS; skip if audio is too quiet (near-silence)
                                val rms = chunkData.foldIndexed(0L) { i, acc, _ ->
                                    if (i % 2 == 0) {
                                        val sample = (chunkData[i].toInt() and 0xFF) or (chunkData[i + 1].toInt() shl 8)
                                        acc + sample.toLong() * sample.toLong()
                                    } else acc
                                } / (chunkData.size / 2)
                                if (rms > 500000L) { // ~707 RMS threshold (out of 32768 max)
                                    if (chunkData.size < minChunkBytes) {
                                        val paddedData = ByteArray(minChunkBytes)
                                        System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                                        chunkData = paddedData
                                    }
                                    onChunkReady(chunkData)
                                } else {
                                    Log.d("AudioRecorder", "Chunk skipped: RMS too low ($rms) — near-silence")
                                }
                            } else {
                                Log.d("AudioRecorder", "Chunk dropped due to insufficient speech frames: $speechFrames (need $minSpeechFrames)")
                            }
                            
                            // Memory Hardening: zero out the accumulated buffer
                            val oldData = chunkStream.toByteArray()
                            Arrays.fill(oldData, 0.toByte())
                            
                            chunkStream = ByteArrayOutputStream() // reset for next chunk
                            silenceFrames = 0
                            speechFrames = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioRecorder", "Read error: ${e.message}")
                    break
                }
            }
            
            try { vad.close() } catch (e: Exception) {} // Optional cleanup

            // Flush any remaining audio in the buffer when recording stops
            if (chunkStream.size() > 0 && speechFrames >= 15) {
                var chunkData = chunkStream.toByteArray()
                if (chunkData.size < minChunkBytes) {
                    val paddedData = ByteArray(minChunkBytes)
                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                    chunkData = paddedData
                }
                onChunkReady(chunkData)
                
                // Memory Hardening
                val oldData = chunkStream.toByteArray()
                Arrays.fill(oldData, 0.toByte())
            }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w("AudioRecorder", "stop() error: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("AudioRecorder", "release() error: ${e.message}")
        }
        audioRecord = null
    }
}
