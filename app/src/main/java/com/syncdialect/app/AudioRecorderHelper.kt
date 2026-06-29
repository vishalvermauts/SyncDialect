package com.syncdialect.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

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
            val buffer = ByteArray(minBufferSize)
            var chunkStream = ByteArrayOutputStream()
            var silenceFrames = 0
            var maxRmsInChunk = 0.0
            var speechFrames = 0
            val silenceThresholdRMS = 2000.0
            val speechThresholdRMS = 3000.0
            val minChunkBytes = 16000

            while (isRecording) {
                try {
                    val record = audioRecord ?: break
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0 && isRecording) {
                        if (isMuted) {
                            chunkStream.reset()
                            silenceFrames = 0
                            maxRmsInChunk = 0.0
                            continue
                        }
                        chunkStream.write(buffer, 0, read)
                        
                        // Calculate RMS energy for VAD
                        var sumSquare = 0.0
                        for (i in 0 until read step 2) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            sumSquare += (sample * sample).toDouble()
                        }
                        val rms = sqrt(sumSquare / (read / 2))
                        
                        Log.d("AudioRecorder", "RMS: $rms, SilenceFrames: $silenceFrames")

                        if (rms > maxRmsInChunk) {
                            maxRmsInChunk = rms
                        }
                        if (rms > speechThresholdRMS) {
                            speechFrames++
                        }

                        if (rms < silenceThresholdRMS) {
                            silenceFrames++
                        } else {
                            silenceFrames = 0
                        }

                        // If enough silence and we have some audio, yield the chunk
                        if (silenceFrames >= prefs.vadWaitFrames && chunkStream.size() >= 8000) {
                            if (speechFrames >= 3) { // Require at least 3 frames of actual speech
                                var chunkData = chunkStream.toByteArray()
                                // Pad up to the model minimum only; avoid inflating
                                // short utterances all the way to 1 second.
                                if (chunkData.size < minChunkBytes) {
                                    val paddedData = ByteArray(minChunkBytes)
                                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                                    chunkData = paddedData
                                }
                                onChunkReady(chunkData)
                            } else {
                                Log.d("AudioRecorder", "Chunk dropped due to low max RMS: $maxRmsInChunk")
                            }
                            chunkStream = ByteArrayOutputStream() // reset for next chunk
                            silenceFrames = 0
                            speechFrames = 0
                            maxRmsInChunk = 0.0
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioRecorder", "Read error: ${e.message}")
                    break
                }
            }
            
            // Flush any remaining audio in the buffer when recording stops
            if (chunkStream.size() > 0 && maxRmsInChunk > speechThresholdRMS) {
                var chunkData = chunkStream.toByteArray()
                if (chunkData.size < minChunkBytes) {
                    val paddedData = ByteArray(minChunkBytes)
                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                    chunkData = paddedData
                }
                onChunkReady(chunkData)
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
