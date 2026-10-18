package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Test

class MediaSendGateTest {
    @Test
    fun `uploaded media is submitted only to the original chat`() {
        var recipient: String? = null
        var result: Result<Unit>? = null
        val gate = MediaSendGate("original", { "original" }, { true }) { result = it }
        gate.submit { recipient = it }
        assertEquals("original", recipient)
        assertTrue(result!!.isSuccess)
    }

    @Test
    fun `switching chats during upload aborts the send`() {
        var sent = false
        var result: Result<Unit>? = null
        val gate = MediaSendGate("original", { "different" }, { true }) { result = it }
        gate.submit { sent = true }
        assertFalse(sent)
        assertTrue(result!!.isFailure)
    }

    @Test
    fun `closing the panel or timing out prevents a late upload callback from sending`() {
        var active = true
        var sent = false
        var completed = false
        val gate = MediaSendGate("original", { "original" }, { active }) { completed = true }
        active = false
        gate.submit { sent = true }
        assertFalse(sent)
        assertFalse(completed)
    }

    @Test
    fun `duplicate native completion events never send twice`() {
        var sends = 0
        var completions = 0
        val gate = MediaSendGate("original", { "original" }, { true }) { completions++ }
        repeat(3) { gate.submit { sends++ } }
        assertEquals(1, sends)
        assertEquals(1, completions)
    }

    @Test
    fun `unavailable session and native errors follow the normal failure path`() {
        var result: Result<Unit>? = null
        val gate = MediaSendGate("original", { error("session gone") }, { true }) { result = it }
        gate.submit { fail("Should not send") }
        assertTrue(result!!.isFailure)
        val native = MediaSendGate("original", { "original" }, { true }) { result = it }
        native.submit { error("native failed") }
        assertTrue(result!!.isFailure)
    }
}
