package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.app.host.ChatSessionRef
import com.qm.qqzygisk.hook.app.host.SessionTarget
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

internal data class DouyinSessionTarget(val conversationId: String, val sessionInfo: Any)

/** Bind the actual EmojiWidgetUI, not similarly named buttons in comments or live streams. */
internal object DouyinChatSession {
    private const val WIDGET_CLASS = "com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.entrances.EmojiWidgetUI"
    private const val SESSION_CLASS = "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo"
    private val hooked = mutableSetOf<Method>()
    private var installing = false
    private val widgets = WeakHashMap<View, WeakReference<Any>>()

    @Synchronized
    fun install() {
        if (installing) return
        installing = true
        try {
            installWidgetHook()
        } finally {
            installing = false
        }
    }

    private fun installWidgetHook() {
        val widget = Class.forName(WIDGET_CLASS, false, HostData.appClassLoader)
        val create = widget.getDeclaredMethod("LJJLIIIJL", ViewGroup::class.java)
        if (create !in hooked) {
            create.hook {
                after {
                    val root = result as? View ?: return@after
                    runCatching { bindWidget(instance, root) }
                        .onFailure { Log.error("绑定抖音表情按钮失败", it) }
                }
            }
            hooked.add(create)
            Log.info("抖音好友聊天表情入口 hook 已安装")
        }
        val viewCreated = widget.getMethod("onViewCreated", View::class.java)
        if (viewCreated !in hooked) {
            viewCreated.hook {
                after {
                    if (!widget.isInstance(instance)) return@after
                    val root = args.firstOrNull() as? View ?: return@after
                    runCatching { bindWidget(instance, root) }
                        .onFailure { Log.error("抖音表情按钮初始化后绑定失败", it) }
                }
            }
            hooked.add(viewCreated)
        }
    }

    private fun bindWidget(widget: Any, root: View) {
        val view = DouyinButtonResolver.resolve(widget, root) as? View
            ?: error("抖音表情点击目标不是 View")
        HostData.alignHostClassLoader(widget.javaClass.classLoader)
        widgets[view] = WeakReference(widget)
        EmoticonButtonHooker.attachToView(view)
        view.post {
            if (widgets[view]?.get() === widget) EmoticonButtonHooker.attachToView(view)
        }
        Log.info("已绑定抖音私信表情点击目标: ${view.javaClass.name} id=${view.id}, root=${root.javaClass.name}")
    }

    fun matches(view: View): Boolean = widgets[view]?.get() != null

    fun sessionFor(view: View): ChatSessionRef? {
        val widget = widgets[view]?.get() ?: return null
        val target = resolve(widget) ?: return null
        val activity = activityOf(view.context) ?: return null
        return BoundSession(WeakReference(view), WeakReference(widget), WeakReference(activity), target.conversationId)
    }

    private fun resolve(widget: Any): DouyinSessionTarget? = runCatching {
        val field = generateSequence(widget.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.type.name == SESSION_CLASS } ?: return@runCatching null
        field.isAccessible = true
        val session = field.get(widget) ?: return@runCatching null
        val id = session.javaClass.getMethod("getConversationId").invoke(session) as? String
        id?.takeIf { it.isNotBlank() }?.let { DouyinSessionTarget(it, session) }
    }.getOrNull()

    private fun activityOf(context: Context): Activity? {
        var current = context
        val visited = mutableSetOf<Context>()
        while (visited.add(current)) {
            if (current is Activity) return current
            if (current !is ContextWrapper) break
            current = current.baseContext
        }
        return null
    }

    private class BoundSession(
        private val view: WeakReference<View>,
        private val widget: WeakReference<Any>,
        private val activity: WeakReference<Activity>,
        private val conversationId: String,
    ) : ChatSessionRef {
        override fun resolve(): SessionTarget {
            val host = activity.get() ?: return SessionTarget.Unavailable
            if (host.isFinishing || host.isDestroyed) return SessionTarget.Unavailable
            val button = view.get()?.takeIf { it.isAttachedToWindow } ?: return SessionTarget.Unavailable
            val owner = widget.get() ?: return SessionTarget.Unavailable
            if (widgets[button]?.get() !== owner) return SessionTarget.Unavailable
            val current = DouyinChatSession.resolve(owner) ?: return SessionTarget.Unavailable
            if (current.conversationId != conversationId) return SessionTarget.Changed
            return SessionTarget.Ready(current)
        }
    }
}
