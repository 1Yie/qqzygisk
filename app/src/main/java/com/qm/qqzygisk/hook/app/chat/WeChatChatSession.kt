package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.host.ChatSessionRef
import com.qm.qqzygisk.hook.app.host.SessionTarget
import com.qm.qqzygisk.hook.utils.Log
import java.lang.ref.WeakReference

/**
 * 微信的聊天会话解析。
 *
 * 微信 8.5 的聊天页是**挂在 `LauncherUI` 里的 Fragment**（VAS 的 fragment mode），不是独立的
 * `ChattingUI` Activity，所以拿 Activity 的 Intent 找不到会话。会话标识按下面顺序解析：
 *
 * 1. 聊天页 Activity 的 Intent extra `Chat_User`（独立聊天 Activity 的场景，如分享拉起）
 * 2. 承载长按按钮的 Fragment 的 arguments（fragment mode 下会话是通过参数传进去的）
 * 3. 该 Fragment（及一层 `Chatting*` 持有对象）上的会话 String 字段
 * 4. Activity 上的会话 String 字段
 *
 * 发送时优先重新实时解析；解析不到就沿用打开面板时捕获的会话，只有确实读到**另一个**会话
 * 才判定为切换。
 */
internal object WeChatChatSession {
    /** 聊天页启动参数：会话标识（好友 wxid / 群 xxx@chatroom / 公众号 gh_xxx） */
    private const val TALKER_EXTRA = "Chat_User"

    /** 会话标识形状：允许 wxid_*、*@chatroom、gh_*、数字/字母下划线短标识 */
    private val talkerPattern =
        Regex("^(wxid_[A-Za-z0-9_-]+|gh_[A-Za-z0-9_-]+|[0-9]+@chatroom|[A-Za-z0-9_.@-]{3,64})$")

    private const val HOLDER_HINT = "Chatting"

    @Volatile
    private var strategyLogged = false

    @Volatile
    private var failureDetailLogged = false

    fun sessionFor(view: View): ChatSessionRef? {
        // 真实宿主类由 TinkerClassLoader 加载，先对齐，后续所有宿主类反射才拿得到同一份
        HostData.alignHostClassLoader(view.javaClass.classLoader)
        val activity = activityOf(view.context) ?: return null
        val talker = talkerOf(activity, view)
        if (talker.isNullOrBlank()) {
            Log.warn("没有从聊天页拿到会话标识: ${activity.javaClass.name}")
            logFailureDetail(activity, view)
            return null
        }
        return SessionRef(WeakReference(activity), WeakReference(view), talker)
    }

    /** 从任意 Context 往上找 Activity（聊天页的 View 可能挂着 ContextThemeWrapper）。 */
    fun activityOf(context: Context?): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun talkerOf(activity: Activity, anchor: View?): String? {
        intentTalker(activity)?.let {
            logStrategyOnce("intent", activity.javaClass.name)
            return it
        }
        val hostFragment = anchor?.let { fragmentHost(activity, it) }
        if (hostFragment != null) {
            fragmentArgumentsTalker(hostFragment)?.let {
                logStrategyOnce("fragment-args", hostFragment.javaClass.name)
                return it
            }
            fieldTalker(hostFragment)?.let {
                logStrategyOnce("fragment-field", hostFragment.javaClass.name)
                return it
            }
        }
        fieldTalker(activity)?.let {
            logStrategyOnce("activity-field", activity.javaClass.name)
            return it
        }
        return null
    }

    private fun intentTalker(activity: Activity): String? = runCatching {
        activity.intent?.getStringExtra(TALKER_EXTRA)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun fragmentArgumentsTalker(fragment: Any): String? {
        val args = runCatching { invokeNoArg(fragment, "getArguments") }.getOrNull() ?: return null
        val direct = runCatching {
            args.javaClass.getMethod("getString", String::class.java).invoke(args, TALKER_EXTRA) as? String
        }.getOrNull()
        if (!direct.isNullOrBlank()) return direct
        // 参数里没有固定 key 时，按会话标识形状找唯一候选
        val candidates = LinkedHashSet<String>()
        runCatching {
            val keySet = args.javaClass.getMethod("keySet").invoke(args) as? Set<*>
            keySet?.forEach { key ->
                val value = runCatching {
                    args.javaClass.getMethod("get", String::class.java).invoke(args, key as String)
                }.getOrNull()
                if (value is String && talkerPattern.matches(value)) candidates.add(value)
            }
        }
        return candidates.singleOrNull()
    }

    /**
     * 从聊天页对象上找会话标识：先看 `Chatting*` 系持有对象，再看自己。
     * 只有唯一命中才采用，避免把标题 / 自己 wxid 之类字段当成会话。
     */
    private fun fieldTalker(target: Any): String? {
        val candidates = LinkedHashSet<String>()
        collectStringFields(target, candidates)

        // 再看一层 Chatting* 持有对象（会话对象通常挂在那里）
        for (cls in generateSequence<Class<*>>(target.javaClass) { it.superclass }) {
            for (field in cls.declaredFields) {
                if (field.type.isPrimitive || field.type == String::class.java) continue
                if (!field.type.name.contains(HOLDER_HINT)) continue
                val holder = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue
                collectStringFields(holder, candidates)
            }
        }
        return candidates.singleOrNull()
    }

    private fun collectStringFields(target: Any, into: MutableSet<String>) {
        for (cls in generateSequence<Class<*>>(target.javaClass) { it.superclass }) {
            for (field in cls.declaredFields) {
                if (field.type != String::class.java) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target) as? String
                }.getOrNull() ?: continue
                if (talkerPattern.matches(value)) into.add(value)
            }
        }
    }

    /** 找到承载 [anchor] 的那个 Fragment（fragment mode 下聊天页就是它）。 */
    private fun fragmentHost(activity: Activity, anchor: View): Any? {
        val manager = fragmentManager(activity) ?: return null
        return allFragments(manager)
            .firstOrNull { fragment ->
                val view = fragmentView(fragment) ?: return@firstOrNull false
                isAncestor(view, anchor)
            }
    }

    private fun fragmentManager(activity: Activity): Any? {
        listOf("getSupportFragmentManager", "getFragmentManager").forEach { name ->
            val manager = runCatching { invokeNoArg(activity, name) }.getOrNull()
            if (manager != null) return manager
        }
        return null
    }

    private fun allFragments(manager: Any): List<Any> {
        val result = mutableListOf<Any>()
        val fragments = runCatching {
            invokeNoArg(manager, "getFragments") as? List<*>
        }.getOrNull().orEmpty()
        fragments.filterNotNull().forEach { fragment ->
            result.add(fragment)
            val childManager = runCatching { invokeNoArg(fragment, "getChildFragmentManager") }.getOrNull()
            if (childManager != null) result.addAll(allFragments(childManager))
        }
        return result
    }

    private fun fragmentView(fragment: Any): View? =
        runCatching { invokeNoArg(fragment, "getView") as? View }.getOrNull()

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        method.isAccessible = true
        return method.invoke(target)
    }

    private fun isAncestor(ancestor: View, node: View): Boolean {
        var current: View? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun logStrategyOnce(strategy: String, detail: String) {
        if (strategyLogged) return
        strategyLogged = true
        Log.info("微信会话标识来源: $strategy ($detail)")
    }

    /** 一次定位失败时，把 Fragment 上的字符串字段打出来，方便下一轮适配。 */
    private fun logFailureDetail(activity: Activity, anchor: View) {
        if (failureDetailLogged) return
        failureDetailLogged = true
        runCatching {
            val host = fragmentHost(activity, anchor)
            if (host == null) {
                Log.warn("微信会话定位: 没有找到承载按钮的 Fragment, activity=${activity.javaClass.name}")
                return@runCatching
            }
            val names = mutableListOf<String>()
            for (cls in generateSequence<Class<*>>(host.javaClass) { it.superclass }) {
                for (field in cls.declaredFields) {
                    if (field.type != String::class.java) continue
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(host) as? String
                    }.getOrNull()
                    names.add("${cls.simpleName}.${field.name}=${value?.let(::mask) ?: "null"}")
                }
            }
            Log.warn("微信会话定位: fragment=${host.javaClass.name} 字符串字段=${names.joinToString()}")
        }.onFailure { Log.error("微信会话定位诊断失败", it) }
    }

    private fun mask(value: String): String =
        if (value.length <= 4) "***" else "${value.take(3)}***${value.takeLast(2)}"

    private class SessionRef(
        private val activityRef: WeakReference<Activity>,
        private val anchorRef: WeakReference<View>,
        private val openedTalker: String,
    ) : ChatSessionRef {
        override fun resolve(): SessionTarget {
            val activity = activityRef.get() ?: return SessionTarget.Unavailable
            if (activity.isFinishing || activity.isDestroyed) return SessionTarget.Unavailable
            val live = talkerOf(activity, anchorRef.get())
            // 微信有时会把 Intent/参数换掉、或页面被复用，读不到实时会话时沿用打开面板时的会话；
            // 只有确实读到**另一个**会话才判定为切换，避免「人还在这个聊天里却说没有会话」。
            if (live != null && live != openedTalker) return SessionTarget.Changed
            return SessionTarget.Ready(openedTalker)
        }
    }
}
