package com.forge.android.bridge

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentRunCoordinatorTest {
    @Test fun cancellationKeepsOwnershipUntilCleanupAndDoesNotCancelAnotherSession() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val runs = AgentRunCoordinator(scope)
                val started = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                assertTrue(runs.start("first") {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                })
                started.await()
                runs.cancel("other")
                assertFalse(cleaning.isCompleted)
                assertFalse(runs.start("second") {})
                runs.cancel("first")
                cleaning.await()
                assertTrue(runs.busy)
                assertFalse(runs.start("second") {})
                release.complete(Unit)
                while (runs.busy) yield()
                assertTrue(runs.start("second") {})
            } finally { scope.cancel() }
        }
    }

    @Test fun immediateCompletionDoesNotLeaveEngineBusy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val runs = AgentRunCoordinator(scope)
            repeat(100) {
                assertTrue(runs.start("fast") {})
                assertFalse(runs.busy)
            }
            runs.stop()
            assertFalse(runs.start("stopped") {})
        } finally { scope.cancel() }
    }

    @Test fun cancelledQueuedRunReleasesSlotEvenWhenBodyNeverStarts() {
        val queue = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val runs = AgentRunCoordinator(scope)
            var executed = false
            assertTrue(runs.start("queued") { executed = true })
            runs.cancel("queued")
            while (queue.isNotEmpty()) queue.removeAt(0).run()
            assertFalse(executed)
            assertFalse(runs.busy)
            assertTrue(runs.start("next") {})
        } finally {
            scope.cancel()
            while (queue.isNotEmpty()) queue.removeAt(0).run()
        }
    }
}
