package com.syncdialect.app.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syncdialect.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unattended Android Instrumentation Benchmark Test for SyncDialect.
 * Runs on-device via:
 *   adb shell am instrument -w -r \
 *     -e manifest_path /sdcard/Download/bench/manifest.json \
 *     -e audio_dir /sdcard/Download/bench/wavs \
 *     -e output_dir /sdcard/Download/bench/results \
 *     -e run_id run_test_001 \
 *     -e target_backend GPU \
 *     com.syncdialect.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SyncDialectBenchmarkTest {

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var outputDir: File
    private lateinit var attemptsFile: File
    private lateinit var eventsFile: File
    private lateinit var attemptsWriter: FileWriter

    private var runId: String = ""
    private var warmupCount: Int = 2
    private var repetitions: Int = 3
    private var timeoutMs: Long = 15000L
    private var cooldownMs: Long = 2000L
    private var targetBackend: String = "GPU"
    private var resume: Boolean = false

    private val completedAttemptIds = mutableSetOf<String>()

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        runId = args.getString("run_id") ?: ("run_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6))
        warmupCount = args.getString("warmup_count")?.toIntOrNull() ?: 2
        repetitions = args.getString("repetitions")?.toIntOrNull() ?: 3
        timeoutMs = args.getString("timeout_ms")?.toLongOrNull() ?: 15000L
        cooldownMs = args.getString("cooldown_ms")?.toLongOrNull() ?: 2000L
        targetBackend = args.getString("target_backend") ?: "GPU"
        resume = args.getString("resume")?.toBoolean() ?: false

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val defaultOut = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmark_runs/$runId")
        val outPathStr = args.getString("output_dir")
        val requestedDir = if (outPathStr != null) File(outPathStr) else defaultOut
        outputDir = if (requestedDir.exists() || requestedDir.mkdirs()) requestedDir else defaultOut
        outputDir.mkdirs()

        eventsFile = File(outputDir, "events.jsonl")
        attemptsFile = File(outputDir, "attempts.jsonl")

        // Load already completed attempts if resuming
        if (resume && attemptsFile.exists()) {
            attemptsFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        val obj = JSONObject(trimmed)
                        val attId = obj.optString("attempt_id")
                        val status = obj.optString("status")
                        if (attId.isNotEmpty() && status.isNotEmpty() && status != "INCOMPLETE") {
                            completedAttemptIds.add(attId)
                        }
                    } catch (e: Exception) {}
                }
            }
            Log.i("SyncDialectBench", "Resuming run $runId: found ${completedAttemptIds.size} existing attempts")
        }

        attemptsWriter = FileWriter(attemptsFile, true)
        BenchmarkEventLogger.startSession(runId, eventsFile)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        
        // Wait up to 30 seconds for model engine readiness
        var modelReady = false
        val waitDeadline = SystemClock.elapsedRealtime() + 30000L
        while (SystemClock.elapsedRealtime() < waitDeadline && !modelReady) {
            scenario.onActivity { act ->
                modelReady = act.isModelReadyState()
            }
            if (!modelReady) Thread.sleep(500)
        }
        Log.i("SyncDialectBench", "MainActivity launched. Model ready: $modelReady")
    }

    @After
    fun tearDown() {
        AudioSourceProvider.reset()
        BenchmarkEventLogger.flushAndClose()
        if (::attemptsWriter.isInitialized) {
            try { attemptsWriter.flush(); attemptsWriter.close() } catch (ignored: Exception) {}
        }
        if (::scenario.isInitialized) {
            try { scenario.close() } catch (ignored: Exception) {}
        }
    }

    @Test
    fun executeBenchmarkSuite() {
        val args = InstrumentationRegistry.getArguments()
        val manifestPath = args.getString("manifest_path")
        val audioDirPath = args.getString("audio_dir")

        val manifestFile = if (manifestPath != null) File(manifestPath) else File(outputDir, "manifest.json")
        val audioDir = if (audioDirPath != null) File(audioDirPath) else File(manifestFile.parentFile, "wavs")

        if (!manifestFile.exists()) {
            Log.w("SyncDialectBench", "Manifest file not found at ${manifestFile.absolutePath}. Generating mock/fallback run.")
            runFallbackVerification()
            return
        }

        val manifestJson = JSONObject(manifestFile.readText())
        val recordings = manifestJson.getJSONArray("recordings")
        Log.i("SyncDialectBench", "Loaded manifest: ${recordings.length()} recordings, hash=${manifestJson.optString("manifest_sha256")}")

        var scheduledIndex = 0
        val totalRecordings = recordings.length()

        for (r in 0 until totalRecordings) {
            val rec = recordings.getJSONObject(r)
            val recId = rec.getString("recording_id")
            val audioFileName = rec.getString("audio_file")
            val sourceLang = rec.optString("source_language", "English")
            val targetLang = rec.optString("target_language", "Spanish")
            val targetTag = rec.optString("target_language_tag", "es")
            val category = rec.optString("category", "short_turn")
            val isPureSilence = category == "pure_silence" || category == "pure_noise"

            val segmentsList = mutableListOf<PhraseSegment>()
            if (rec.has("segments")) {
                val arr = rec.getJSONArray("segments")
                for (s in 0 until arr.length()) {
                    val sObj = arr.getJSONObject(s)
                    segmentsList.add(
                        PhraseSegment(
                            segmentIndex = sObj.optInt("segment_index", s),
                            speechStartSample = sObj.optLong("speech_start_sample", 0L),
                            speechEndSample = sObj.optLong("speech_end_sample", 32000L),
                            transcript = sObj.optString("transcript", ""),
                            referenceTranslation = sObj.optString("reference_translation", "")
                        )
                    )
                }
            } else if (!isPureSilence) {
                segmentsList.add(
                    PhraseSegment(
                        segmentIndex = 0,
                        speechStartSample = rec.optLong("speech_start_sample", 0L),
                        speechEndSample = rec.optLong("speech_end_sample", 32000L),
                        transcript = rec.optString("source_transcript", ""),
                        referenceTranslation = rec.optString("reference_translation", "")
                    )
                )
            }

            val audioFile = File(audioDir, audioFileName)
            if (!audioFile.exists()) {
                Log.e("SyncDialectBench", "Audio file missing: ${audioFile.absolutePath}")
                recordFailedAttempt(
                    attemptId = "att_${recId}_rep0",
                    recordingId = recId,
                    category = category,
                    status = "INFRASTRUCTURE_ERROR",
                    failureReason = "Audio file missing on device: ${audioFile.name}"
                )
                continue
            }

            val pcmBytes = readPcmFromWav(audioFile)

            // Warmup iterations
            if (warmupCount > 0 && scheduledIndex == 0) {
                Log.i("SyncDialectBench", "Executing $warmupCount warmup cycles...")
                for (w in 0 until warmupCount) {
                    runSingleAttempt(
                        attemptId = "warmup_${w}_${recId}",
                        recordingId = recId,
                        pcmBytes = pcmBytes,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        targetTag = targetTag,
                        segments = segmentsList,
                        category = "warmup",
                        isWarmup = true,
                        isSilenceOrNoise = isPureSilence
                    )
                }
            }

            // Benchmark Repetitions
            for (rep in 0 until repetitions) {
                scheduledIndex++
                val attemptId = "att_${recId}_rep${rep}"
                if (completedAttemptIds.contains(attemptId)) {
                    Log.i("SyncDialectBench", "Skipping already completed attempt: $attemptId")
                    continue
                }

                runSingleAttempt(
                    attemptId = attemptId,
                    recordingId = recId,
                    pcmBytes = pcmBytes,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    targetTag = targetTag,
                    segments = segmentsList,
                    category = category,
                    isWarmup = false,
                    isSilenceOrNoise = isPureSilence
                )

                // Inter-turn thermal cooldown
                if (cooldownMs > 0) {
                    Thread.sleep(cooldownMs)
                }
            }
        }

        Log.i("SyncDialectBench", "Benchmark suite execution completed. Total attempts evaluated: $scheduledIndex")
    }

    private fun runSingleAttempt(
        attemptId: String,
        recordingId: String,
        pcmBytes: ByteArray,
        sourceLang: String,
        targetLang: String,
        targetTag: String,
        segments: List<PhraseSegment>,
        category: String,
        isWarmup: Boolean,
        isSilenceOrNoise: Boolean
    ) {
        val attemptStartNano = SystemClock.elapsedRealtimeNanos()
        val expectedSegmentCount = if (isSilenceOrNoise) 0 else segments.size.coerceAtLeast(1)
        val latch = CountDownLatch(if (isSilenceOrNoise) 1 else expectedSegmentCount)
        val emittedChunksCount = java.util.concurrent.atomic.AtomicInteger(0)

        val segmentTrackers = java.util.concurrent.ConcurrentHashMap<String, SegmentTracker>()
        for (i in 0 until expectedSegmentCount) {
            val segId = "seg_$i"
            val phraseSeg = segments.getOrNull(i)
            segmentTrackers[segId] = SegmentTracker(i, phraseSeg)
        }

        BenchmarkEventLogger.setContext(
            attemptId = attemptId,
            recordingId = recordingId,
            turnId = "turn_0",
            segmentId = "seg_0",
            inferenceId = "inf_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        )

        val listener: (JSONObject) -> Unit = { event ->
            val eventAttemptId = event.optString("attempt_id")
            if (eventAttemptId.isEmpty() || eventAttemptId == attemptId) {
                val eventType = event.optString("event_type")
                val ts = event.optLong("timestamp_nanos")
                val rawSeg = event.optString("segment_id")
                val segId = if (rawSeg.isNotEmpty()) rawSeg else "seg_0"
                val tracker = segmentTrackers.computeIfAbsent(segId) {
                    val idx = segId.removePrefix("seg_").toIntOrNull() ?: 0
                    SegmentTracker(idx, segments.getOrNull(idx))
                }

                when (eventType) {
                    "VAD_SPEECH_DETECTED" -> if (tracker.vadSpeechDetectedNano == null) tracker.vadSpeechDetectedNano = ts
                    "ENDPOINT_DECISION" -> if (tracker.endpointDecisionNano == null) tracker.endpointDecisionNano = ts
                    "PREPROCESSING_START" -> if (tracker.prepStartNano == null) tracker.prepStartNano = ts
                    "PREPROCESSING_COMPLETE" -> if (tracker.prepCompleteNano == null) tracker.prepCompleteNano = ts
                    "QUEUE_ENTRY" -> if (tracker.queueEntryNano == null) tracker.queueEntryNano = ts
                    "QUEUE_DEQUEUE" -> if (tracker.queueDequeueNano == null) tracker.queueDequeueNano = ts
                    "INFERENCE_REQUEST_SUBMITTED" -> if (tracker.reqSubmittedNano == null) tracker.reqSubmittedNano = ts
                    "INFERENCE_EXECUTION_START" -> if (tracker.infStartNano == null) tracker.infStartNano = ts
                    "FIRST_RAW_MODEL_OUTPUT" -> if (tracker.firstRawModelOutputNano == null) tracker.firstRawModelOutputNano = ts
                    "FIRST_VALID_TRANSLATED_TEXT" -> if (tracker.firstValidTextNano == null) tracker.firstValidTextNano = ts
                    "FIRST_SPEAKABLE_PHRASE_READY" -> if (tracker.firstSpeakableReadyNano == null) tracker.firstSpeakableReadyNano = ts
                    "FIRST_SPEAKABLE_PHRASE_SUBMITTED" -> if (tracker.firstSpeakableSubmittedNano == null) tracker.firstSpeakableSubmittedNano = ts
                    "TTS_ON_START" -> if (tracker.ttsOnStartNano == null) tracker.ttsOnStartNano = ts
                    "FINAL_RELEVANT_TTS_ON_DONE", "FINAL_PLAYBACK_COMPLETION" -> {
                        if (tracker.finalTtsOnDoneNano == null) tracker.finalTtsOnDoneNano = ts
                        if (!tracker.isDone) {
                            tracker.isDone = true
                            latch.countDown()
                        }
                    }
                    "INFERENCE_COMPLETION" -> {
                        tracker.infCompleteNano = ts
                        tracker.tokenCount = event.optInt("token_count", 0)
                        tracker.tokensPerSec = event.optDouble("tokens_per_sec", 0.0)
                        if (tracker.tokenCount == 0 && !isSilenceOrNoise && tracker.failureClassification == null) {
                            tracker.failureClassification = "no_output"
                            if (!tracker.isDone) {
                                tracker.isDone = true
                                latch.countDown()
                            }
                        }
                    }
                    "DROPPED_AUDIO" -> {
                        if (tracker.failureClassification == null) tracker.failureClassification = "dropped_segment"
                        if (!tracker.isDone) {
                            tracker.isDone = true
                            latch.countDown()
                        }
                    }
                    "DROPPED_CHUNK_VAD" -> {
                        // Non-speech frames filtered by VAD; not a failure of the speech turn
                    }
                    "INFERENCE_ERROR" -> {
                        if (tracker.failureClassification == null) tracker.failureClassification = "inference"
                        if (!tracker.isDone) {
                            tracker.isDone = true
                            latch.countDown()
                        }
                    }
                    "TTS_ERROR" -> {
                        if (tracker.failureClassification == null) tracker.failureClassification = "tts"
                        if (!tracker.isDone) {
                            tracker.isDone = true
                            latch.countDown()
                        }
                    }
                    "TRUNCATION_LIMIT_REACHED" -> {
                        tracker.failureClassification = "truncation"
                    }
                }
            }
        }

        BenchmarkEventLogger.onEventListener = listener

        var injectionSource: PcmInjectionAudioSource? = null
        scenario.onActivity { activity ->
            activity.setLanguagesForBenchmark(sourceLang, targetLang, targetTag)

            injectionSource = PcmInjectionAudioSource(
                pcmBytes = pcmBytes,
                sampleRate = 16000,
                frameSizeSamples = 320,
                appendTrailingSilenceMs = 1200
            )

            AudioSourceProvider.setTestAudioSourceFactory { injectionSource!! }
            injectionSource!!.start()

            BenchmarkEventLogger.logEvent(
                eventType = "RECORDING_INJECTION_START",
                details = mapOf("total_pcm_bytes" to pcmBytes.size),
                timestampNanos = injectionSource!!.injectionStartRealtimeNanos
            )

            for (seg in segments) {
                val startNano = injectionSource!!.sampleToMonotonicNanos(seg.speechStartSample)
                val endNano = injectionSource!!.sampleToMonotonicNanos(seg.speechEndSample)
                val segId = "seg_${seg.segmentIndex}"
                BenchmarkEventLogger.logEvent(
                    eventType = "SOURCE_SPEECH_START",
                    details = mapOf("sample_offset" to seg.speechStartSample, "segment_id" to segId),
                    timestampNanos = startNano,
                    segmentIdOverride = segId
                )
                BenchmarkEventLogger.logEvent(
                    eventType = "SOURCE_SPEECH_END",
                    details = mapOf("sample_offset" to seg.speechEndSample, "segment_id" to segId),
                    timestampNanos = endNano,
                    segmentIdOverride = segId
                )
            }

            // Start continuous recording with production AudioRecorderHelper and route through MainActivity bounded queue
            activity.getAudioRecorderHelperInstance().startContinuousRecording { chunkData ->
                val chunkIdx = emittedChunksCount.getAndIncrement()
                val segId = "seg_$chunkIdx"
                val tracker = segmentTrackers.computeIfAbsent(segId) {
                    SegmentTracker(chunkIdx, segments.getOrNull(chunkIdx))
                }

                val turnAttemptId = "${attemptId}_$segId"
                val attribution = TurnAttribution(
                    attemptId = attemptId,
                    recordingId = recordingId,
                    turnId = turnAttemptId,
                    segmentId = segId,
                    inferenceId = "inf_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                )

                val enqueued = activity.submitTurnForProcessing(
                    audioData = chunkData,
                    turnId = turnAttemptId,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    targetTag = targetTag,
                    segmentIdOverride = segId,
                    attribution = attribution
                )

                if (!enqueued) {
                    tracker.failureClassification = "queue_overflow"
                    if (!tracker.isDone) {
                        tracker.isDone = true
                        latch.countDown()
                    }
                }
            }
        }

        // Await completion or timeout
        val audioDurationMs = (pcmBytes.size * 1000L) / (16000 * 2) + 1200L
        val waitDurationMs = if (isSilenceOrNoise) {
            audioDurationMs + 800L
        } else {
            audioDurationMs + (expectedSegmentCount * timeoutMs)
        }
        val completedInTime = latch.await(waitDurationMs, TimeUnit.MILLISECONDS)
        val attemptEndNano = SystemClock.elapsedRealtimeNanos()

        scenario.onActivity { activity ->
            activity.getAudioRecorderHelperInstance().stopRecording()
            injectionSource?.stop()
            val allMessages = activity.getMessagesList()
            for ((sId, trk) in segmentTrackers) {
                val tId = "${attemptId}_$sId"
                val found = allMessages.find { it.id == tId }
                if (found != null && found.text.isNotEmpty()) {
                    trk.capturedText.setLength(0)
                    trk.capturedText.append(found.text)
                }
            }
        }

        BenchmarkEventLogger.onEventListener = null

        val stats = injectionSource?.getStats()

        if (isSilenceOrNoise) {
            val allCaptured = segmentTrackers.values.joinToString(" ") { it.capturedText.toString() }.trim()
            val anyTtsStarted = segmentTrackers.values.any { it.ttsOnStartNano != null }
            val abstentionSuccess = allCaptured.isEmpty() && !anyTtsStarted
            val status = if (abstentionSuccess) "ABSTENTION_SUCCESS" else "UNWANTED_GENERATION"

            val record = JSONObject().apply {
                put("run_id", runId)
                put("attempt_id", attemptId)
                put("recording_id", recordingId)
                put("segment_id", "seg_0")
                put("category", category)
                put("is_warmup", isWarmup)
                put("status", status)
                put("failure_classification", if (abstentionSuccess) JSONObject.NULL else "unwanted_generation")
                put("software_playback_start_latency_ms", JSONObject.NULL)
                put("speech_detection_delay_ms", JSONObject.NULL)
                put("endpoint_delay_ms", JSONObject.NULL)
                put("preprocessing_delay_ms", JSONObject.NULL)
                put("queue_wait_ms", JSONObject.NULL)
                put("scheduling_overhead_ms", JSONObject.NULL)
                put("ttft_ms", JSONObject.NULL)
                put("first_token_to_valid_text_ms", JSONObject.NULL)
                put("tts_buffering_ms", JSONObject.NULL)
                put("tts_submission_to_onstart_ms", JSONObject.NULL)
                put("source_speech_end_to_final_playback_ms", JSONObject.NULL)
                put("total_generation_duration_ms", JSONObject.NULL)
                put("tokens_generated", 0)
                put("decoding_speed_tps", 0.0)
                put("source_speech_start_nano", JSONObject.NULL)
                put("source_speech_end_nano", JSONObject.NULL)
                put("tts_on_start_nano", JSONObject.NULL)
                put("final_tts_on_done_nano", JSONObject.NULL)
                put("max_scheduling_drift_nanos", stats?.maxSchedulingDriftNanos ?: 0L)
                put("audio_underruns", stats?.audioUnderruns ?: 0)
                put("generated_text", allCaptured)
                put("elapsed_attempt_ms", (attemptEndNano - attemptStartNano) / 1_000_000.0)
            }
            synchronized(this) {
                attemptsWriter.write(record.toString() + "\n")
                attemptsWriter.flush()
            }
            completedAttemptIds.add(attemptId)
            Log.i("SyncDialectBench", "Silence/Noise Attempt $attemptId: status=$status")
            return
        }

        // Speech segments processing
        val trackersToRecord = if (segmentTrackers.isEmpty()) {
            listOf(SegmentTracker(0, segments.getOrNull(0)).apply {
                if (!completedInTime) failureClassification = "timeout"
            })
        } else {
            segmentTrackers.values.sortedBy { it.segmentIndex }
        }

        for (tracker in trackersToRecord) {
            val segIdx = tracker.segmentIndex
            val segAttemptId = if (trackersToRecord.size > 1) "${attemptId}_seg$segIdx" else attemptId
            val phraseSeg = tracker.phraseSegment ?: segments.getOrNull(segIdx)

            val sourceStartSample = phraseSeg?.speechStartSample ?: 0L
            val sourceEndSample = phraseSeg?.speechEndSample ?: 32000L
            val sourceSpeechStartNano = injectionSource?.sampleToMonotonicNanos(sourceStartSample) ?: 0L
            val sourceSpeechEndNano = injectionSource?.sampleToMonotonicNanos(sourceEndSample) ?: 0L

            val status: String
            if (!completedInTime && !tracker.isDone) {
                status = "TIMEOUT"
                tracker.failureClassification = tracker.failureClassification ?: "timeout"
            } else if (tracker.failureClassification != null) {
                status = "FAILED"
            } else if (tracker.ttsOnStartNano != null) {
                status = "COMPLETED"
            } else {
                status = "MISSING_COMPLETION"
                tracker.failureClassification = "infrastructure"
            }

            val speechDetectionDelayMs = if (tracker.vadSpeechDetectedNano != null && sourceSpeechStartNano > 0L) {
                (tracker.vadSpeechDetectedNano!! - sourceSpeechStartNano) / 1_000_000.0
            } else null

            val endpointDelayMs = if (tracker.endpointDecisionNano != null && sourceSpeechEndNano > 0L) {
                (tracker.endpointDecisionNano!! - sourceSpeechEndNano) / 1_000_000.0
            } else null

            val prepDelayMs = if (tracker.prepCompleteNano != null && tracker.prepStartNano != null) {
                (tracker.prepCompleteNano!! - tracker.prepStartNano!!) / 1_000_000.0
            } else null

            val queueWaitMs = if (tracker.queueDequeueNano != null && tracker.queueEntryNano != null) {
                (tracker.queueDequeueNano!! - tracker.queueEntryNano!!) / 1_000_000.0
            } else null

            val schedulingOverheadMs = if (tracker.infStartNano != null && tracker.reqSubmittedNano != null) {
                (tracker.infStartNano!! - tracker.reqSubmittedNano!!) / 1_000_000.0
            } else null

            val ttftMs = if (tracker.firstRawModelOutputNano != null && tracker.infStartNano != null) {
                (tracker.firstRawModelOutputNano!! - tracker.infStartNano!!) / 1_000_000.0
            } else null

            val firstTokenToValidMs = if (tracker.firstValidTextNano != null && tracker.firstRawModelOutputNano != null) {
                (tracker.firstValidTextNano!! - tracker.firstRawModelOutputNano!!) / 1_000_000.0
            } else null

            val ttsBufferingMs = if (tracker.firstSpeakableSubmittedNano != null && tracker.firstValidTextNano != null) {
                (tracker.firstSpeakableSubmittedNano!! - tracker.firstValidTextNano!!) / 1_000_000.0
            } else null

            val ttsSubmissionToOnStartMs = if (tracker.ttsOnStartNano != null && tracker.firstSpeakableSubmittedNano != null) {
                (tracker.ttsOnStartNano!! - tracker.firstSpeakableSubmittedNano!!) / 1_000_000.0
            } else null

            val primaryLatencyMs = if (tracker.ttsOnStartNano != null && sourceSpeechEndNano > 0L) {
                (tracker.ttsOnStartNano!! - sourceSpeechEndNano) / 1_000_000.0
            } else null

            val totalPlaybackDurationMs = if (tracker.finalTtsOnDoneNano != null && sourceSpeechEndNano > 0L) {
                (tracker.finalTtsOnDoneNano!! - sourceSpeechEndNano) / 1_000_000.0
            } else null

            val genDurationMs = if (tracker.infCompleteNano != null && tracker.infStartNano != null) {
                (tracker.infCompleteNano!! - tracker.infStartNano!!) / 1_000_000.0
            } else null

            val attemptRecord = JSONObject().apply {
                put("run_id", runId)
                put("attempt_id", segAttemptId)
                put("recording_id", recordingId)
                put("turn_id", "turn_$segIdx")
                put("segment_id", "seg_$segIdx")
                put("category", category)
                put("is_warmup", isWarmup)
                put("status", status)
                put("failure_classification", tracker.failureClassification ?: JSONObject.NULL)
                put("software_playback_start_latency_ms", primaryLatencyMs ?: JSONObject.NULL)
                put("speech_detection_delay_ms", speechDetectionDelayMs ?: JSONObject.NULL)
                put("endpoint_delay_ms", endpointDelayMs ?: JSONObject.NULL)
                put("preprocessing_delay_ms", prepDelayMs ?: JSONObject.NULL)
                put("queue_wait_ms", queueWaitMs ?: JSONObject.NULL)
                put("scheduling_overhead_ms", schedulingOverheadMs ?: JSONObject.NULL)
                put("ttft_ms", ttftMs ?: JSONObject.NULL)
                put("first_token_to_valid_text_ms", firstTokenToValidMs ?: JSONObject.NULL)
                put("tts_buffering_ms", ttsBufferingMs ?: JSONObject.NULL)
                put("tts_submission_to_onstart_ms", ttsSubmissionToOnStartMs ?: JSONObject.NULL)
                put("source_speech_end_to_final_playback_ms", totalPlaybackDurationMs ?: JSONObject.NULL)
                put("total_generation_duration_ms", genDurationMs ?: JSONObject.NULL)
                put("tokens_generated", tracker.tokenCount)
                put("decoding_speed_tps", tracker.tokensPerSec)
                put("source_speech_start_nano", sourceSpeechStartNano)
                put("source_speech_end_nano", sourceSpeechEndNano)
                put("tts_on_start_nano", tracker.ttsOnStartNano ?: JSONObject.NULL)
                put("final_tts_on_done_nano", tracker.finalTtsOnDoneNano ?: JSONObject.NULL)
                put("max_scheduling_drift_nanos", stats?.maxSchedulingDriftNanos ?: 0L)
                put("audio_underruns", stats?.audioUnderruns ?: 0)
                put("generated_text", tracker.capturedText.toString().trim())
                put("reference_translation", phraseSeg?.referenceTranslation ?: "")
                put("source_transcript", phraseSeg?.transcript ?: "")
                put("elapsed_attempt_ms", (attemptEndNano - attemptStartNano) / 1_000_000.0)
            }

            synchronized(this) {
                attemptsWriter.write(attemptRecord.toString() + "\n")
                attemptsWriter.flush()
            }

            completedAttemptIds.add(segAttemptId)
            Log.i(
                "SyncDialectBench",
                "Attempt $segAttemptId ($category): status=$status, " +
                        "softwarePlaybackStartLatency=${primaryLatencyMs?.let { "%.2f ms".format(it) } ?: "N/A"}, " +
                        "ttft=${ttftMs?.let { "%.2f ms".format(it) } ?: "N/A"}"
            )
        }
        completedAttemptIds.add(attemptId)
    }

    private fun recordFailedAttempt(
        attemptId: String,
        recordingId: String,
        category: String,
        status: String,
        failureReason: String
    ) {
        val record = JSONObject().apply {
            put("run_id", runId)
            put("attempt_id", attemptId)
            put("recording_id", recordingId)
            put("turn_id", "turn_0")
            put("segment_id", "seg_0")
            put("category", category)
            put("status", status)
            put("failure_classification", "infrastructure")
            put("failure_reason", failureReason)
            put("software_playback_start_latency_ms", JSONObject.NULL)
        }
        synchronized(this) {
            attemptsWriter.write(record.toString() + "\n")
            attemptsWriter.flush()
        }
        completedAttemptIds.add(attemptId)
    }

    private fun runFallbackVerification() {
        Log.i("SyncDialectBench", "Running fallback verification test with synthetic PCM...")
        val syntheticPcm = ByteArray(16000 * 2 * 2) // 2 seconds of silence
        runSingleAttempt(
            attemptId = "test_fallback_001",
            recordingId = "rec_fallback_silence",
            pcmBytes = syntheticPcm,
            sourceLang = "English",
            targetLang = "Spanish",
            targetTag = "es",
            segments = listOf(PhraseSegment(0, 0L, 32000L)),
            category = "pure_silence",
            isWarmup = false,
            isSilenceOrNoise = true
        )
    }

    private fun readPcmFromWav(file: File): ByteArray {
        val bytes = file.readBytes()
        // If standard WAV header (starts with RIFF), strip first 44 bytes
        return if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
            bytes.copyOfRange(44, bytes.size)
        } else {
            bytes
        }
    }
}

data class PhraseSegment(
    val segmentIndex: Int,
    val speechStartSample: Long,
    val speechEndSample: Long,
    val transcript: String = "",
    val referenceTranslation: String = ""
)

class SegmentTracker(
    val segmentIndex: Int,
    val phraseSegment: PhraseSegment?,
    var vadSpeechDetectedNano: Long? = null,
    var endpointDecisionNano: Long? = null,
    var prepStartNano: Long? = null,
    var prepCompleteNano: Long? = null,
    var queueEntryNano: Long? = null,
    var queueDequeueNano: Long? = null,
    var reqSubmittedNano: Long? = null,
    var infStartNano: Long? = null,
    var firstRawModelOutputNano: Long? = null,
    var firstValidTextNano: Long? = null,
    var firstSpeakableReadyNano: Long? = null,
    var firstSpeakableSubmittedNano: Long? = null,
    var ttsOnStartNano: Long? = null,
    var finalTtsOnDoneNano: Long? = null,
    var infCompleteNano: Long? = null,
    var tokenCount: Int = 0,
    var tokensPerSec: Double = 0.0,
    val capturedText: StringBuilder = StringBuilder(),
    var failureClassification: String? = null,
    @Volatile var isDone: Boolean = false
)
