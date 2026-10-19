package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import java.lang.reflect.Method
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities
import com.qm.qqzygisk.hook.utils.startModuleSettings

internal object DouyinSettingHooker {
    const val SETTINGS_ACTIVITY = "com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity"
    private const val ITEM_CLASS = "com.bytedance.ies.dmt.ui.common.views.CommonItemView"
    private const val ITEM_TAG = "qqzygisk.douyin.settings"
    private const val SETTINGS_LIST_ID = 2131390851 // Douyin 40.7.0's settings scroll content.
    private val hooked = mutableSetOf<Method>()
    private var installing = false

    @Synchronized
    fun install() {
        if (installing) return
        installing = true
        try {
            installLifecycleHooks()
        } finally {
            installing = false
        }
    }

    private fun installLifecycleHooks() {
        val cls = Class.forName(SETTINGS_ACTIVITY, false, appClassLoader)
        for (method in cls.declaredMethods.filter { it.name in listOf("onCreate", "onResume") }) {
            if (method in hooked) continue
            method.hook {
                after {
                    val activity = instance as? Activity ?: return@after
                    activity.window?.decorView?.post { inject(activity) }
                }
            }
            hooked.add(method)
        }
    }

    fun inject(activity: Activity): Boolean {
        if (activity.javaClass.name != SETTINGS_ACTIVITY) return false
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (root.findViewWithTag<View>(ITEM_TAG) != null) return true
        return runCatching {
            val direct = activity.findViewById<View>(SETTINGS_LIST_ID) as? LinearLayout
            val list = direct?.takeIf { it.orientation == LinearLayout.VERTICAL }
                ?: findNativeRowParent(root)
                ?: error("没有找到抖音原生设置列表")
            val itemClass = Class.forName(ITEM_CLASS, false, appClassLoader)
            val row = itemClass.getConstructor(android.content.Context::class.java).newInstance(activity) as View
            itemClass.getMethod("setLeftText", CharSequence::class.java).invoke(row, "QQ Zygisk")
            itemClass.getMethod("setRightUIMode", Int::class.javaPrimitiveType).invoke(row, 0)
            row.tag = ITEM_TAG
            row.contentDescription = "QQ Zygisk"
            row.minimumHeight = (56 * activity.resources.displayMetrics.density).toInt()
            row.setOnClickListener {
                runCatching {
                    AppParasitics.ensureModuleAppResources(activity.resources)
                    val proxy = Class.forName("com.ss.android.ugc.aweme.setting.ui.AboutActivity", false, appClassLoader)
                    activity.registerModuleAppActivities(proxy)
                    activity.startModuleSettings()
                }.onFailure { error ->
                    Log.error("打开抖音 QQ Zygisk 设置失败", error)
                    Toast.makeText(activity, error.message ?: "无法打开 QQ Zygisk 设置", Toast.LENGTH_LONG).show()
                }
            }
            list.addView(row, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            Log.info("抖音设置页已插入 QQ Zygisk 入口")
            true
        }.onFailure { Log.error("抖音设置入口注入失败", it) }.getOrDefault(false)
    }

    private fun findNativeRowParent(root: ViewGroup): LinearLayout? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.javaClass.name == ITEM_CLASS && root is LinearLayout && root.orientation == LinearLayout.VERTICAL) return root
            if (child is ViewGroup) findNativeRowParent(child)?.let { return it }
        }
        return null
    }
}
