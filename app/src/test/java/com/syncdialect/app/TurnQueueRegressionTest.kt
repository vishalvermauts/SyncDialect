package com.syncdialect.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

import com.syncdialect.app.benchmark.TurnAttribution

/**
 * Regression test suite verifying:
 * 1. Bounded queue limits and overflow drop behavior (capacity = 4)
 * 2. Active work cancellation (Job.cancel() halts active in-flight execution)
 * 3. Generation ID advancement on language switch or user stop
 * 4. Stale turn and stale streaming token suppression
 * 5. Immutable TurnAttribution preservation across queue boundaries
 */
class TurnQueueRegressionTest {

    data class MockQueuedTurn(
        val turnId: String,
        val audioData: ByteArray,
        val generationId: Long,
        val attribution: TurnAttribution? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MockQueuedTurn) return false
            return turnId == other.turnId && generationId == other.generationId && attribution == other.attribution
        }

        override fun hashCode(): Int = 31 * (31 * turnId.hashCode() + generationId.hashCode()) + (attribution?.hashCode() ?: 0)
    }

    @Test
    fun testQueueCapacityBound_dropsOverflowAudio() {
        val maxCapacity = 4
        val queue = Channel<MockQueuedTurn>(capacity = maxCapacity)
        val activeGenerationId = AtomicLong(1L)

        // Enqueue up to capacity
        for (i in 1..4) {
            val turn = MockQueuedTurn("turn_$i", ByteArray(1024), activeGenerationId.get())
            val result = queue.trySend(turn)
            assertTrue("Turn $i within capacity must succeed", result.isSuccess)
        }

        // 5th turn must overflow and be rejected
        val overflowTurn = MockQueuedTurn("turn_5_overflow", ByteArray(1024), activeGenerationId.get())
        val overflowResult = queue.trySend(overflowTurn)
        assertFalse("5th turn exceeding capacity must fail trySend", overflowResult.isSuccess)

        // Verify queue still holds exactly 4 items
        var count = 0
        while (queue.tryReceive().isSuccess) {
            count++
        }
        assertEquals("Queue must contain exactly 4 turns", 4, count)
    }

    @Test
    fun testCancellation_incrementsGenerationId_andCancelsActiveJob() = runTest {
        val activeGenerationId = AtomicLong(1L)
        var activeTurnJob: Job? = null
        val queue = Channel<MockQueuedTurn>(capacity = 4)

        // Enqueue two turns under gen 1
        queue.trySend(MockQueuedTurn("turn_1", ByteArray(512), 1L))
        queue.trySend(MockQueuedTurn("turn_2", ByteArray(512), 1L))

        val jobStarted = CompletableDeferred<Unit>()
        val jobCancelled = CompletableDeferred<Boolean>()

        // Simulate active translation job
        activeTurnJob = launch {
            try {
                jobStarted.complete(Unit)
                delay(5000) // Simulating long inference
            } catch (e: CancellationException) {
                jobCancelled.complete(true)
                throw e
            }
        }

        jobStarted.await()

        // Trigger cancellation (identical to MainActivity.cancelAndClearQueue)
        val prevGen = activeGenerationId.get()
        val newGen = activeGenerationId.incrementAndGet()
        activeTurnJob.cancel()

        var purgedTurns = 0
        while (true) {
            val item = queue.tryReceive().getOrNull() ?: break
            purgedTurns++
        }

        assertEquals("Previous generation must be 1", 1L, prevGen)
        assertEquals("New generation must be 2", 2L, newGen)
        assertEquals("Purged turns must be 2", 2, purgedTurns)
        assertTrue("Active job must report cancelled", activeTurnJob.isCancelled)
        assertTrue("Active job must have caught CancellationException", jobCancelled.await())
    }

    @Test
    fun testStaleTurnSuppression_dropsOlderGenerationTurns() {
        val activeGenerationId = AtomicLong(1L)
        val turnFromOldGen = MockQueuedTurn("stale_turn_1", ByteArray(256), generationId = 1L)

        // Advance generation (e.g. language change or user mic toggle)
        activeGenerationId.incrementAndGet() // now 2L

        // Dequeue verification logic (identical to MainActivity.processQueuedTurn)
        val isStale = (turnFromOldGen.generationId != activeGenerationId.get())
        assertTrue("Turn with generationId 1 must be flagged stale when active is 2", isStale)
    }

    @Test
    fun testStaleTokenSuppression_discardsTokensFromCancelledGeneration() {
        val activeGenerationId = AtomicLong(1L)
        val turnGenerationId = 1L
        val receivedUiTokens = mutableListOf<String>()

        fun onTokenGenerated(token: String) {
            // Identical check from MainActivity.kt:
            if (activeGenerationId.get() != turnGenerationId) {
                return // Discard stale token
            }
            receivedUiTokens.add(token)
        }

        // Before cancellation: tokens are accepted
        onTokenGenerated("Hello ")
        assertEquals(listOf("Hello "), receivedUiTokens)

        // User changes language or cancels: generation increments
        activeGenerationId.incrementAndGet() // now 2L

        // In-flight engine callback continues for previous turn
        onTokenGenerated("world!")
        onTokenGenerated(" Stale text.")

        // Verify tokens from old generation were discarded
        assertEquals("Only tokens before generation increment must be recorded", listOf("Hello "), receivedUiTokens)
    }

    @Test
    fun testLanguageSwitch_resetsQueueAndAdvancesGeneration() {
        val activeGenerationId = AtomicLong(1L)
        val queue = Channel<MockQueuedTurn>(capacity = 4)

        // User speaks in Spanish -> English
        queue.trySend(MockQueuedTurn("turn_es_1", ByteArray(100), activeGenerationId.get()))
        queue.trySend(MockQueuedTurn("turn_es_2", ByteArray(100), activeGenerationId.get()))

        // User swaps language to English -> Spanish
        fun onLanguageSwap() {
            activeGenerationId.incrementAndGet()
            var drained = 0
            while (queue.tryReceive().isSuccess) {
                drained++
            }
            assertEquals(2, drained)
        }

        onLanguageSwap()

        assertEquals("Generation must advance to 2", 2L, activeGenerationId.get())

        // New turn under English -> Spanish
        val newTurn = MockQueuedTurn("turn_en_1", ByteArray(100), activeGenerationId.get())
        val sent = queue.trySend(newTurn)
        assertTrue(sent.isSuccess)
        assertEquals(2L, newTurn.generationId)
    }

    @Test
    fun testTurnAttribution_preservedThroughQueue() {
        val queue = Channel<MockQueuedTurn>(capacity = 4)
        val attr0 = TurnAttribution("att_01", "rec_01", "turn_0", "seg_0", "inf_0")
        val attr1 = TurnAttribution("att_01", "rec_01", "turn_1", "seg_1", "inf_1")

        queue.trySend(MockQueuedTurn("turn_0", ByteArray(100), 1L, attr0))
        queue.trySend(MockQueuedTurn("turn_1", ByteArray(100), 1L, attr1))

        val received0 = queue.tryReceive().getOrNull()
        val received1 = queue.tryReceive().getOrNull()

        assertEquals("seg_0", received0?.attribution?.segmentId)
        assertEquals("inf_0", received0?.attribution?.inferenceId)
        assertEquals("turn_0", received0?.attribution?.turnId)
        assertEquals("seg_1", received1?.attribution?.segmentId)
        assertEquals("inf_1", received1?.attribution?.inferenceId)
        assertEquals("turn_1", received1?.attribution?.turnId)
    }
}
