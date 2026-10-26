package com.forge.android.tools

import java.util.concurrent.atomic.AtomicBoolean

/** A transport cancellation is not evidence that a dispatched input was undone. */
class DeviceCallState(private val write: Boolean) {
    private val dispatched = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    fun markDispatched() { dispatched.set(true) }
    fun failureState() = if (write && dispatched.get()) "unknown" else "not_started"
    fun claimReceipt() = reported.compareAndSet(false, true)
}
