package com.dshlite.idea.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeBackoffTest {
    @Test fun `failures back off exponentially and success resets the schedule`() {
        val backoff = BridgeBackoff()
        backoff.miss()
        assertFalse(backoff.shouldSkipTick())
        backoff.miss()
        assertTrue(backoff.shouldSkipTick())
        assertFalse(backoff.shouldSkipTick())
        backoff.miss()
        repeat(3) { assertTrue(backoff.shouldSkipTick()) }
        assertFalse(backoff.shouldSkipTick())

        repeat(5) { backoff.miss() }
        repeat(15) { assertTrue(backoff.shouldSkipTick()) }
        assertFalse(backoff.shouldSkipTick())
        backoff.success()
        assertFalse(backoff.shouldSkipTick())
    }
}
