package com.aras.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickConnectWorkflowTest {
    @Test
    fun `selects fastest positive member after sort`() {
        val result = selectFastestReachableServer(listOf("slow", "fast", "failed")) { guid ->
            when (guid) {
                "slow" -> 220L
                "fast" -> 45L
                else -> -1L
            }
        }

        assertEquals("fast", result)
    }

    @Test
    fun `excludes candidates outside captured membership`() {
        val members = listOf("first", "second")
        val delays = mapOf("first" to 90L, "outside" to 10L, "second" to 120L)

        val result = selectFastestReachableServer(members) { delays[it] }

        assertEquals("first", result)
    }

    @Test
    fun `returns null when no member has a successful delay`() {
        val result = selectFastestReachableServer(listOf("one", "two")) {
            if (it == "one") 0L else -1L
        }

        assertNull(result)
    }
}
