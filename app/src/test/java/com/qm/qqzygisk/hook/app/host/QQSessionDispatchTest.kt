package com.qm.qqzygisk.hook.app.host

import org.junit.Assert.*
import org.junit.Test

class QQSessionDispatchTest {
    private class LiveSession(var target: SessionTarget) : ChatSessionRef {
        var reads = 0
        override fun resolve(): SessionTarget { reads++; return target }
    }

    @Test
    fun `valid QQ contact survives the panel lifetime wrapper`() {
        val contact = Any()
        val session = LiveSession(SessionTarget.Ready(contact))
        val guarded = session.withPanelGuard { true }
        assertSame(contact, QQHostBridge.contactFor(guarded))
        assertEquals(1, session.reads)
    }

    @Test
    fun `multiple wrappers preserve interface based session resolution`() {
        val contact = Any()
        val session = LiveSession(SessionTarget.Ready(contact))
        val guarded = session.withPanelGuard { true }.withPanelGuard { true }
        assertSame(contact, QQHostBridge.contactFor(guarded))
        assertEquals(1, session.reads)
    }

    @Test
    fun `a fresh Contact instance in the same bound session is resolved for each send`() {
        val first = Any()
        val latest = Any()
        val session = LiveSession(SessionTarget.Ready(first))
        val guarded = session.withPanelGuard { true }
        assertSame(first, QQHostBridge.contactFor(guarded))
        session.target = SessionTarget.Ready(latest)
        assertSame(latest, QQHostBridge.contactFor(guarded))
        assertEquals(2, session.reads)
    }

    @Test
    fun `switching chats after opening the panel still refuses the send`() {
        val session = LiveSession(SessionTarget.Ready(Any()))
        val guarded = session.withPanelGuard { true }
        QQHostBridge.contactFor(guarded)
        session.target = SessionTarget.Changed
        val error = assertThrows(IllegalStateException::class.java) { QQHostBridge.contactFor(guarded) }
        assertTrue(error.message!!.contains("聊天会话已切换"))
    }

    @Test
    fun `closed panel refuses to resolve the underlying contact`() {
        val session = LiveSession(SessionTarget.Ready(Any()))
        val guarded = session.withPanelGuard { false }
        assertThrows(IllegalStateException::class.java) { QQHostBridge.contactFor(guarded) }
        assertEquals(0, session.reads)
    }

    @Test
    fun `a genuinely unavailable session remains unavailable`() {
        assertThrows(IllegalStateException::class.java) { QQHostBridge.contactFor(null) }
        val session = LiveSession(SessionTarget.Unavailable)
        assertThrows(IllegalStateException::class.java) { QQHostBridge.contactFor(session.withPanelGuard { true }) }
    }
}
