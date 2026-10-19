package com.qm.qqzygisk.hook.app.chat

/** Resolve the view that actually consumes touches inside EmojiWidgetUI's returned root. */
internal object DouyinButtonResolver {
    fun resolve(widget: Any, root: Any): Any {
        val channelMethod = widget.javaClass.methods.firstOrNull { method ->
            method.name == "getChannelView" && method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isInstance(root)
        } ?: error("抖音表情入口缺少 getChannelView，不能绑定外部容器")
        channelMethod.isAccessible = true
        val channel = checkNotNull(channelMethod.invoke(widget, root)) { "抖音表情按钮尚未创建" }
        val itemMethod = channel.javaClass.methods.firstOrNull {
            it.name == "getMItemView" && it.parameterTypes.isEmpty()
        }
        itemMethod?.isAccessible = true
        return itemMethod?.invoke(channel) ?: channel
    }
}
