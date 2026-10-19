package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException

class ImagePanelActionResultTest {
    @Test
    fun `history permission denial cannot fail or repeat an already submitted send`() {
        var sendCount = 0
        var historyCount = 0
        var dismissCount = 0
        var reported: Throwable? = null
        val denied = FileNotFoundException(".usage.json: EACCES (Permission denied)")
        val result = performImagePanelAction(
            send = { sendCount++; Result.success(Unit) },
            recordUsage = { historyCount++; throw denied },
            onUsageFailure = { reported = it },
        ).onSuccess { dismissCount++ }
        assertTrue(result.isSuccess)
        assertEquals(1, sendCount)
        assertEquals(1, historyCount)
        assertEquals(1, dismissCount)
        assertSame(denied, reported)
    }

    @Test
    fun `send failure does not record usage`() {
        val failure = IllegalStateException("聊天会话不可用")
        var recorded = false
        val result = performImagePanelAction(
            send = { Result.failure(failure) },
            recordUsage = { recorded = true },
            onUsageFailure = { fail("No history write expected") },
        )
        assertSame(failure, result.exceptionOrNull())
        assertFalse(recorded)
    }

    @Test
    fun `synchronous sender exception follows the normal failure path`() {
        val failure = IllegalStateException("发送接口异常")
        var recorded = false
        val result = performImagePanelAction(
            send = { throw failure },
            recordUsage = { recorded = true },
            onUsageFailure = { fail("No history write expected") },
        )
        assertSame(failure, result.exceptionOrNull())
        assertFalse(recorded)
    }
}
