package com.qm.qqzygisk.hook.app.host

/** Preserve the host session interface while making a closed panel's handle unusable. */
internal fun ChatSessionRef.withPanelGuard(active: () -> Boolean): ChatSessionRef {
    val original = this
    return object : ChatSessionRef {
        override fun resolve(): SessionTarget =
            if (active()) original.resolve() else SessionTarget.Unavailable
    }
}
