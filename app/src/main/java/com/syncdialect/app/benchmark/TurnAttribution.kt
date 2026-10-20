package com.syncdialect.app.benchmark

import java.io.Serializable

/**
 * Immutable context identifying an individual speech segment across the pipeline.
 * Carried explicitly through audio queueing, inference worker, streaming callbacks,
 * and TTS utterance lifecycles to prevent mutable state contamination.
 */
data class TurnAttribution(
    val attemptId: String,
    val recordingId: String,
    val turnId: String,
    val segmentId: String,
    val inferenceId: String
) : Serializable
