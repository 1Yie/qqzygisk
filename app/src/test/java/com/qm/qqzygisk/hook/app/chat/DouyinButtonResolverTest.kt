package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Test

class DouyinButtonResolverTest {
    class Root
    class ClickTarget
    class Channel(private val touchTarget: Any?) {
        fun getMItemView(): Any? = touchTarget
    }
    class Widget(val root: Root, val channel: Channel) {
        fun getChannelView(candidate: Root): Channel {
            check(candidate === root)
            return channel
        }
    }

    @Test
    fun `long press targets the inner clickable view instead of the root container`() {
        val root = Root()
        val target = ClickTarget()
        val widget = Widget(root, Channel(target))
        assertSame(target, DouyinButtonResolver.resolve(widget, root))
    }

    @Test
    fun `channel consuming its own touches is itself a valid target`() {
        val root = Root()
        val channel = Channel(null)
        assertSame(channel, DouyinButtonResolver.resolve(Widget(root, channel), root))
    }

    @Test
    fun `a mismatched host API does not silently attach to a non clickable root`() {
        assertThrows(IllegalStateException::class.java) {
            DouyinButtonResolver.resolve(Any(), Root())
        }
    }
}
