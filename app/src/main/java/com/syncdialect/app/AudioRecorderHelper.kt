package com.syncdialect.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.syncdialect.app.benchmark.AudioSource
import com.syncdialect.app.benchmark.AudioSourceProvider
import com.syncdialect.app.benchmark.AudioSourceStats
import com.syncdialect.app.benchmark.BenchmarkEventLogger
import com.syncdialect.app.benchmark.MicAudioSource
import java.io.ByteArrayOutputStream
import java.util.Arrays

class AudioRecorderHelper(
    private val prefs: AppPreferences,
    private val audioSourceProvider: () -> AudioSource = { AudioSourceProvider.createAudioSource(16000) }
) {
    private var activeAudioSource: AudioSource? = null
    @Volatile private var isRecording = false
    private val sampleRate = 16000
    private var recordingThread: Thread? = null

    @Volatile private var muteTimestamp = 0L
    @Volatile var isMuted: Boolean = false
        set(value) {
            field = value
            if (value) {
                muteTimestamp = System.currentTimeMillis()
            }
        }

    fun getActiveAudioSourceStats(): AudioSourceStats? = activeAudioSource?.getStats()

    @SuppressLint("MissingPermission")
    fun startContinuousRecording(onChunkReady: (ByteArray) -> Unit) {
        if (isRecording) {
            stopRecording()
        }
        isMuted = false

        val source = audioSourceProvider()
        activeAudioSource = source
        try {
            source.start()
            isRecording = true
            BenchmarkEventLogger.logEvent(
                eventType = "AUDIO_RECORD_START",
                details = mapOf("sample_rate" to sampleRate)
            )
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start audio source: ${e.message}")
            isRecording = false
            return
        }

        val thread = Thread {
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
            var segmentIndex = 0
            var isFirstFrame = true
            var framesRead = 0L
            val minChunkBytes = 16000 // 0.5 seconds minimum (at 16kHz 16-bit mono: 32000 B/s)
            val maxChunkBytes = 480000 // 15 seconds maximum — allows complete natural sentences
            val minSpeechFrames = 8    // Require 160ms (8 * 20ms) of VAD-confirmed speech

            // Pre-roll buffer: Keep last 8 frames (~160ms) of audio to prevent clipping onsets
            val preRollCapacity = 8
            val preRollQueue = java.util.ArrayDeque<ByteArray>(preRollCapacity)

            while (isRecording) {
                try {
                    val currentSource = activeAudioSource ?: break
                    var readSum = 0
                    while (readSum < frameSizeBytes && isRecording) {
                        val read = currentSource.read(buffer, readSum, frameSizeBytes - readSum)
                        if (read < 0) break
                        readSum += read
                    }
                    
                    if (readSum == frameSizeBytes && isRecording) {
                        if (isFirstFrame) {
                            isFirstFrame = false
                            val firstFrameNano = SystemClock.elapsedRealtimeNanos()
                            BenchmarkEventLogger.logEvent(
                                eventType = "AUDIO_FRAME_FIRST_RECEIVED",
                                details = mapOf("frame_size_bytes" to readSum),
                                timestampNanos = firstFrameNano
                            )
                        }
                        framesRead++

                        // Safety timeout: If muted for >6 seconds without being cleared, force unmute
                        if (isMuted && System.currentTimeMillis() - muteTimestamp > 6000) {
                            Log.w("AudioRecorder", "Safety auto-unmute triggered after 6s of continuous mute")
                            isMuted = false
                        }

                        // Muting protects live microphone from speaker feedback; in injection/test mode,
                        // do not discard audio so back-to-back benchmark audio flows continuously.
                        val isInjectionMode = (activeAudioSource != null && activeAudioSource !is MicAudioSource)
                        if (isMuted && !isInjectionMode) {
                            chunkStream.reset()
                            silenceFrames = 0
                            speechFrames = 0
                            preRollQueue.clear()
                            continue
                        }
                        
                        val isSpeech = vad.isSpeech(buffer)

                        if (isSpeech) {
                            if (speechFrames == 0) {
                                val speechDetectNano = SystemClock.elapsedRealtimeNanos()
                                BenchmarkEventLogger.logEvent(
                                    eventType = "VAD_SPEECH_DETECTED",
                                    details = mapOf(
                                        "segment_id" to "seg_$segmentIndex",
                                        "frames_read" to framesRead
                                    ),
                                    timestampNanos = speechDetectNano,
                                    segmentIdOverride = "seg_$segmentIndex"
                                )
                                // On transition from silence to speech, prepend pre-roll frames
                                if (chunkStream.size() == 0) {
                                    for (preFrame in preRollQueue) {
                                        chunkStream.write(preFrame, 0, preFrame.size)
                                    }
                                    preRollQueue.clear()
                                }
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

                        // Hard limit flush if continuous uninterrupted speech reaches 15 seconds
                        val shouldForceFlush = chunkStream.size() >= maxChunkBytes && speechFrames >= minSpeechFrames

                        // Robust pause threshold: Natural breath pauses are 250-450ms.
                        // Require at least 35 frames (700ms) of sustained silence to endpoint,
                        // preventing premature mid-sentence cutting while avoiding excessive delay.
                        val requiredSilenceFrames = prefs.vadWaitFrames.coerceIn(30, 45) // default 35-40 frames = 700-800ms

                        // If enough silence and we have captured audio, yield the complete chunk
                        if ((silenceFrames >= requiredSilenceFrames && chunkStream.size() >= 8000) || shouldForceFlush) {
                            val endpointNano = SystemClock.elapsedRealtimeNanos()
                            val segId = "seg_$segmentIndex"
                            BenchmarkEventLogger.logEvent(
                                eventType = "ENDPOINT_DECISION",
                                details = mapOf(
                                    "segment_id" to segId,
                                    "silence_frames" to silenceFrames,
                                    "required_silence_frames" to requiredSilenceFrames,
                                    "speech_frames" to speechFrames,
                                    "chunk_bytes" to chunkStream.size(),
                                    "force_flush" to shouldForceFlush
                                ),
                                timestampNanos = endpointNano,
                                segmentIdOverride = segId
                            )

                            val prepStartNano = SystemClock.elapsedRealtimeNanos()
                            BenchmarkEventLogger.logEvent(
                                eventType = "PREPROCESSING_START",
                                details = mapOf("segment_id" to segId),
                                timestampNanos = prepStartNano,
                                segmentIdOverride = segId
                            )

                            if (speechFrames >= minSpeechFrames) { // Require at least 160ms of actual speech
                                var chunkData = chunkStream.toByteArray()
                                if (chunkData.size < minChunkBytes) {
                                    val paddedData = ByteArray(minChunkBytes)
                                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                                    chunkData = paddedData
                                }
                                val prepCompleteNano = SystemClock.elapsedRealtimeNanos()
                                BenchmarkEventLogger.logEvent(
                                    eventType = "PREPROCESSING_COMPLETE",
                                    details = mapOf(
                                        "segment_id" to segId,
                                        "chunk_bytes" to chunkData.size
                                    ),
                                    timestampNanos = prepCompleteNano,
                                    segmentIdOverride = segId
                                )

                                Log.d("AudioRecorder", "Yielding speech chunk $segId: ${chunkData.size} bytes (speechFrames=$speechFrames)")


                                segmentIndex++
                                onChunkReady(chunkData)
                            } else {
                                Log.d("AudioRecorder", "Chunk dropped due to insufficient speech frames: $speechFrames (need $minSpeechFrames)")
                                BenchmarkEventLogger.logEvent(
                                    eventType = "DROPPED_CHUNK_VAD",
                                    details = mapOf(
                                        "segment_id" to segId,
                                        "reason" to "insufficient_speech_frames",
                                        "speech_frames" to speechFrames,
                                        "required_min" to minSpeechFrames
                                    ),
                                    segmentIdOverride = segId
                                )
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

            val lastFrameNano = SystemClock.elapsedRealtimeNanos()
            BenchmarkEventLogger.logEvent(
                eventType = "AUDIO_FRAME_LAST_RECEIVED",
                details = mapOf("total_frames_read" to framesRead),
                timestampNanos = lastFrameNano
            )

            // Flush any remaining audio in the buffer when recording stops
            if (chunkStream.size() > 0 && speechFrames >= 15) {
                var chunkData = chunkStream.toByteArray()
                if (chunkData.size < minChunkBytes) {
                    val paddedData = ByteArray(minChunkBytes)
                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                    chunkData = paddedData
                }
                val segId = "seg_$segmentIndex"
                BenchmarkEventLogger.logEvent(
                    eventType = "ENDPOINT_DECISION",
                    details = mapOf(
                        "segment_id" to segId,
                        "flush_on_stop" to true,
                        "speech_frames" to speechFrames,
                        "chunk_bytes" to chunkData.size
                    ),
                    segmentIdOverride = segId
                )
                BenchmarkEventLogger.logEvent(
                    eventType = "QUEUE_ENTRY",
                    details = mapOf(
                        "segment_id" to segId,
                        "flush_on_stop" to true,
                        "chunk_bytes" to chunkData.size
                    ),
                    segmentIdOverride = segId
                )
                segmentIndex++
                onChunkReady(chunkData)
                
                // Memory Hardening
                val oldData = chunkStream.toByteArray()
                Arrays.fill(oldData, 0.toByte())
            }

            // Log final audio scheduling drift and underruns
            val stats = activeAudioSource?.getStats()
            if (stats != null) {
                BenchmarkEventLogger.logEvent(
                    eventType = "AUDIO_SCHEDULING_STATS",
                    details = mapOf(
                        "max_drift_nanos" to stats.maxSchedulingDriftNanos,
                        "avg_drift_nanos" to stats.avgSchedulingDriftNanos,
                        "underruns" to stats.audioUnderruns,
                        "frames_paced" to stats.framesPaced,
                        "total_bytes_read" to stats.totalBytesRead
                    )
                )
            }
        }
        recordingThread = thread
        thread.start()
    }

    fun stopRecording() {
        isRecording = false
        try {
            activeAudioSource?.stop()
        } catch (e: Exception) {
            Log.w("AudioRecorder", "stop() error: ${e.message}")
        }
        try {
            recordingThread?.join(500)
        } catch (ignored: Exception) {}
        recordingThread = null
        try {
            activeAudioSource?.release()
        } catch (e: Exception) {
            Log.w("AudioRecorder", "release() error: ${e.message}")
        }
        activeAudioSource = null
    }
}
