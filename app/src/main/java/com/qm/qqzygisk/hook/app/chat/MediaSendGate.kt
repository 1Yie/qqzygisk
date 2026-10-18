package com.qm.qqzygisk.hook.app.chat

import java.util.concurrent.atomic.AtomicBoolean

/** Recheck the captured recipient after upload, and never submit twice on repeated callbacks. */
internal class MediaSendGate(
    private val expectedConversation: String,
    private val currentConversation: () -> String,
    private val active: () -> Boolean,
    private val complete: (Result<Unit>) -> Unit,
) {
    private val submitted = AtomicBoolean()

    fun submit(send: (String) -> Unit) {
        if (!active() || !submitted.compareAndSet(false, true)) return
        complete(runCatching {
            check(currentConversation() == expectedConversation) { "聊天会话已切换，请重新打开面板" }
            send(expectedConversation)
        })
    }
}
