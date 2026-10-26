package com.forge.android.tools

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class DeviceCallStateTest {
    @Test fun cancelledApprovalDidNotStartInput() {
        assertEquals("not_started", DeviceCallState(true).failureState())
    }
    @Test fun dispatchedInputWithoutReceiptIsUnknown() {
        val state=DeviceCallState(true)
        state.markDispatched()
        assertEquals("unknown",state.failureState())
    }
    @Test fun readCancellationDoesNotClaimInputSideEffects() {
        val state=DeviceCallState(false)
        state.markDispatched()
        assertEquals("not_started",state.failureState())
    }
    @Test fun bodyAndCompletionCanReportOnlyOnce() {
        val state=DeviceCallState(true)
        val pool=Executors.newFixedThreadPool(4)
        try {
            val results=pool.invokeAll((1..16).map { Callable { state.claimReceipt() } })
            assertEquals(1,results.count { it.get() })
        } finally {pool.shutdownNow()}
    }
}
