package com.forge.android.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/** A single run owns the engine until its job (including cleanup) has completed. */
internal class AgentRunCoordinator(private val scope: CoroutineScope) {
    private val lock = Any()
    private var job: Job? = null
    private var session: String? = null

    val busy: Boolean get() = synchronized(lock) { job != null }

    fun stop(notifyTools: () -> Unit = {}) = synchronized(lock) {
        scope.cancel()
        notifyTools()
    }

    fun start(session: String, block: suspend () -> Unit): Boolean = synchronized(lock) {
        if (job != null || !scope.isActive) return false
        val next = scope.launch(start = CoroutineStart.LAZY) { block() }
        job = next
        this.session = session
        next.invokeOnCompletion {
            synchronized(lock) {
                if (job === next) {
                    job = null
                    this.session = null
                }
            }
        }
        next.start()
        true
    }

    fun cancel(session: String = "", notifyTools: () -> Unit = {}) = synchronized(lock) {
        if (session.isNotBlank() && session != this.session) return
        // Cancel first: a receipt arriving during notification must not finish the run.
        job?.cancel(CancellationException("cancelled"))
        notifyTools()
    }
}
