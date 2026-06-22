package com.example.auravoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioRecorderHelper {
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
        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(minBufferSize)
            var chunkStream = ByteArrayOutputStream()
            var silenceFrames = 0
            var maxRmsInChunk = 0.0
            val silenceThresholdRMS = 600.0  // Adjusted for typical background noise
            val speechThresholdRMS = 1000.0  // Minimum RMS to be considered speech
            val maxSilenceFrames = 15 // ~500ms depending on buffer size

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

                        if (rms < silenceThresholdRMS) {
                            silenceFrames++
                        } else {
                            silenceFrames = 0
                        }

                        // If enough silence and we have some audio, yield the chunk
                        if (silenceFrames >= maxSilenceFrames && chunkStream.size() >= 8000) {
                            if (maxRmsInChunk > speechThresholdRMS) {
                                var chunkData = chunkStream.toByteArray()
                                // Pad to 1 second (32000 bytes) if needed to satisfy model requirements
                                if (chunkData.size < 32000) {
                                    val paddedData = ByteArray(32000)
                                    System.arraycopy(chunkData, 0, paddedData, 0, chunkData.size)
                                    chunkData = paddedData
                                }
                                onChunkReady(chunkData)
                            } else {
                                Log.d("AudioRecorder", "Chunk dropped due to low max RMS: $maxRmsInChunk")
                            }
                            chunkStream = ByteArrayOutputStream() // reset for next chunk
                            silenceFrames = 0
                            maxRmsInChunk = 0.0
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioRecorder", "Read error: ${e.message}")
                    break
                }
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

