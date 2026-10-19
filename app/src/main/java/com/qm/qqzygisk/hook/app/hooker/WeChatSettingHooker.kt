package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.host.WeChatHostBridge
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities
import com.qm.qqzygisk.hook.utils.startModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** 微信 8.0.79 的 setting_new 页面；在宿主排好序后插入，避免破坏前驱链。 */
internal object WeChatSettingHooker {
    private const val ITEM_BASE = "la4.i"
    private const val BUILDER_CLASS = "fb4.g"
    private const val ROOT_GROUP =
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain"
    private const val ITEM_TITLE = "QQ Zygisk"
    private const val ITEM_KEY = "QQZygisk_Settings"
    private val templateCandidates = listOf(
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemNewFriendCommonSayHi",
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemCommonSayHi",
    )

    private val installedBuilders = mutableSetOf<Method>()
    private val installedItemMethods = mutableSetOf<Method>()
    private val activityItems = WeakHashMap<Activity, WeakReference<Any>>()
    private val itemOwners = WeakHashMap<Any, WeakReference<Activity>>()
    private var installing = false
    private var failureLogged = false

    @Synchronized
    fun install(): Boolean {
        if (installing) return false
        installing = true
        return try {
            val itemBase = Class.forName(ITEM_BASE, false, appClassLoader)
            val builder = Class.forName(BUILDER_CLASS, false, appClassLoader)
            val buildMethod = builder.declaredMethods.firstOrNull { method ->
                method.name == "d" && method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == itemBase &&
                    List::class.java.isAssignableFrom(method.parameterTypes[1])
            } ?: error("没找到微信设置项构建器 $BUILDER_CLASS.d")
            if (buildMethod in installedBuilders) return true
            buildMethod.hook {
                after {
                    val root = args.firstOrNull() ?: return@after
                    if (root.javaClass.name != ROOT_GROUP) return@after
                    val original = result ?: return@after
                    runCatching { inject(root, original) }
                        .onSuccess { updated -> result = updated }
                        .onFailure { Log.error("微信设置入口注入失败", it) }
                }
            }
            installedBuilders.add(buildMethod)
            Log.info("微信设置入口 hook 已安装: ${builder.name}.d (after)")
            true
        } catch (error: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                Log.error("安装微信设置入口失败，等待宿主加载后重试", error)
            }
            false
        } finally {
            installing = false
        }
    }

    private fun inject(root: Any, pair: Any): Any {
        val activity = activityOf(root) ?: error("微信设置根项没有宿主 Activity")
        HookSettings.initialize(activity)
        // fb4.g.d 返回宿主 Kotlin Pair(root, orderedChildren)。保留宿主的顺序和所有原始项，
        // 不把新项塞进以 Q7.frontItem 为 key 的 HashMap，也不依赖一次性的 Activity 标记。
        val childrenField = pair.javaClass.declaredFields.firstOrNull { field ->
            if (Modifier.isStatic(field.modifiers)) return@firstOrNull false
            field.isAccessible = true
            field.get(pair) is List<*>
        } ?: error("微信设置构建结果没有子项列表")
        val children = (childrenField.get(pair) as List<*>).filterNotNull()
        val item = activityItems[activity]?.get() ?: createItem(activity).also {
            activityItems[activity] = WeakReference(it)
        }
        val updated = insertWeChatSettingEntry(children, item) { it.javaClass.simpleName }
        if (updated === children) return pair
        val ctor = pair.javaClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes.none { type -> type.isPrimitive }
        } ?: error("微信设置构建结果没有 Pair 构造器")
        ctor.isAccessible = true
        Log.info("微信设置页已插入 QQ Zygisk 入口: ${activity.javaClass.name}, 原有 ${children.size} 项")
        return ctor.newInstance(root, updated)
    }

    private fun activityOf(item: Any): Activity? =
        findMethod(item.javaClass, "getMvvmActivity", emptyArray())?.let { method ->
            method.isAccessible = true
            method.invoke(item) as? Activity
        }

    private fun createItem(activity: Activity): Any {
        val itemBase = Class.forName(ITEM_BASE, false, appClassLoader)
        val template = templateCandidates.asSequence()
            .mapNotNull { runCatching { Class.forName(it, false, appClassLoader) }.getOrNull() }
            .firstOrNull { candidate ->
                !Modifier.isAbstract(candidate.modifiers) && itemBase.isAssignableFrom(candidate) &&
                    candidate.declaredConstructors.any { ctor ->
                        ctor.parameterTypes.size == 1 && ctor.parameterTypes[0].isInstance(activity)
                    }
            } ?: error("没有可实例化的微信设置项模板")
        val ctor = template.declaredConstructors.first {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(activity)
        }
        ctor.isAccessible = true
        val item = ctor.newInstance(activity)
        itemOwners[item] = WeakReference(activity)
        hookItemMethods(template)
        // 该实例不在宿主的 UIComponent 集合里，显式建立 diff 使用的初始快照。
        findMethod(template, "onCreate", arrayOf(Bundle::class.java))?.let { method ->
            method.isAccessible = true
            method.invoke(item, null)
        }
        return item
    }

    private fun hookItemMethods(template: Class<*>) {
        fun override(name: String, value: Any?) {
            val method = findMethod(template, name, emptyArray()) ?: error("微信设置项缺少 $name")
            if (!installedItemMethods.add(method)) return
            method.hook {
                before {
                    if (itemOwners.containsKey(instance)) result = value
                }
            }
        }
        // R7 才是标题；P7 是列表/埋点 key，U7/b8 是副标题，N7 是提示资源。
        override("R7", ITEM_TITLE)
        override("P7", ITEM_KEY)
        override("U7", "")
        override("b8", null)
        override("N7", null)
        val click = findMethod(
            template, "g8", arrayOf(Context::class.java, View::class.java, Int::class.javaPrimitiveType!!),
        ) ?: error("微信设置项缺少点击方法")
        if (installedItemMethods.add(click)) {
            click.hook {
                before {
                    val activity = itemOwners[instance]?.get() ?: return@before
                    result = null
                    runCatching {
                        val proxy = WeChatHostBridge.proxyActivityClass ?: error("未找到微信代理 Activity")
                        activity.registerModuleAppActivities(proxy)
                        activity.startModuleSettings()
                    }.onFailure { Log.error("打开 QQ Zygisk 设置失败", it) }
                }
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, params: Array<Class<*>>): Method? =
        generateSequence(cls) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.contentEquals(params) }
}
