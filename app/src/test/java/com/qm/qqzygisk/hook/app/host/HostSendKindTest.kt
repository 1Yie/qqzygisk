package com.qm.qqzygisk.hook.app.host

import org.junit.Assert.*
import org.junit.Test

class HostSendKindTest {
    @Test
    fun `WeChat defaults to native emoticons and retains an explicit image option`() {
        assertEquals(listOf(SendKind.EMOTICON, SendKind.IMAGE), WeChatHostBridge.supportedKinds)
        assertEquals(SendKind.EMOTICON, WeChatHostBridge.defaultSendKind)
    }

    @Test
    fun `QQ retains its existing image default and emoji option`() {
        assertEquals(listOf(SendKind.IMAGE, SendKind.EMOTICON), QQHostBridge.supportedKinds)
        assertEquals(SendKind.IMAGE, QQHostBridge.defaultSendKind)
    }
}
