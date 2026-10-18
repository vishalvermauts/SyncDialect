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

    @Volatile private var muteTimestamp = 0L
    @Volatile var isMuted: Boolean = false
        set(value) {
            field = value
            if (value) {
                muteTimestamp = System.currentTimeMillis()
            }
        }

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
            val minChunkBytes = 16000 // 0.5 seconds minimum (at 16kHz 16-bit mono: 32000 B/s)
            val maxChunkBytes = 96000  // 3 seconds maximum — targets ~1.5s latency
            val minSpeechFrames = 8    // Require 160ms (8 * 20ms) of VAD-confirmed speech

            // Pre-roll buffer: Keep last 8 frames (~160ms) of audio to prevent clipping onsets
            val preRollCapacity = 8
            val preRollQueue = java.util.ArrayDeque<ByteArray>(preRollCapacity)

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
                        // Safety timeout: If muted for >6 seconds without being cleared, force unmute
                        if (isMuted && System.currentTimeMillis() - muteTimestamp > 6000) {
                            Log.w("AudioRecorder", "Safety auto-unmute triggered after 6s of continuous mute")
                            isMuted = false
                        }

                        if (isMuted) {
                            chunkStream.reset()
                            silenceFrames = 0
                            speechFrames = 0
                            preRollQueue.clear()
                            continue
                        }
                        
                        val isSpeech = vad.isSpeech(buffer)

                        if (isSpeech) {
                            // On transition from silence to speech, prepend pre-roll frames
                            if (speechFrames == 0 && chunkStream.size() == 0) {
                                for (preFrame in preRollQueue) {
                                    chunkStream.write(preFrame, 0, preFrame.size)
                                }
                                preRollQueue.clear()
                            }
                            speechFrames++
                            silenceFrames = 0
                        } else {
                            silenceFrames++
                            // If currently in silence before speech started, maintain circular pre-roll
                            if (speechFrames == 0) {
                                if (preRollQueue.size >= preRollCapacity) {
                                    preRollQueue.pollFirst()
                                }
                                preRollQueue.offerLast(buffer.clone())
                            }
                        }

                        // Collect audio once speech has been initiated
                        if (speechFrames > 0) {
                             chunkStream.write(buffer, 0, frameSizeBytes)
                        }

                        // Auto-flush if chunk exceeds 3 seconds (continuous long speech)
                        val shouldForceFlush = chunkStream.size() >= maxChunkBytes && speechFrames >= minSpeechFrames

                        // Adaptive VAD: For sustained speech (>=1.5s / 75 frames), drop silence threshold to 20 frames (400ms)
                        // For shorter speech, retain default (40 frames = 800ms) to allow natural pauses
                        val requiredSilenceFrames = if (speechFrames >= 75) {
                            (prefs.vadWaitFrames / 2).coerceAtLeast(18) // ~360ms - 400ms
                        } else {
                            prefs.vadWaitFrames // default 40 frames = 800ms
                        }

                        // If enough silence and we have captured audio, yield the chunk
                        if ((silenceFrames >= requiredSilenceFrames && chunkStream.size() >= 8000) || shouldForceFlush) {
                            if (speechFrames >= minSpeechFrames) { // Require at least 160ms of actual speech
                                var chunkData = chunkStream.toByteArray()
                                if (chunkData.size < minChunkBytes) {
                                    val paddedData = ByteArray(minChunkBytes)
                                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                                    chunkData = paddedData
                                }
                                Log.d("AudioRecorder", "Yielding speech chunk: ${chunkData.size} bytes (speechFrames=$speechFrames)")
                                onChunkReady(chunkData)
                            } else {
                                Log.d("AudioRecorder", "Chunk dropped due to insufficient speech frames: $speechFrames (need $minSpeechFrames)")
                            }
                            
                            // Memory Hardening: zero out the accumulated buffer
                            val oldData = chunkStream.toByteArray()
                            Arrays.fill(oldData, 0.toByte())
                            
                            chunkStream = ByteArrayOutputStream() // reset for next chunk
                            silenceFrames = 0
                            speechFrames = 0
                            preRollQueue.clear()
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
