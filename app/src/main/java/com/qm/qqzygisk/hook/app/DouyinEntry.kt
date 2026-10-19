package com.qm.qqzygisk.hook.app

import android.app.Activity
import android.app.Instrumentation
import com.qm.qqzygisk.hook.app.chat.DouyinChatSession
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.hooker.DouyinSettingHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.app.host.DouyinHostBridge
import com.qm.qqzygisk.hook.app.host.HostRuntime
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.qm.qqzygisk.hook.utils.onAppLifecycle

object DouyinEntry {
    private var chatHookFailureLogged = false

    fun init(loader: ClassLoader, packageName: String) {
        HostData.init(loader)
        HostRuntime.install(DouyinHostBridge)
        onAppLifecycle {
            attachBaseContext { context, _ -> HookSettings.initialize(context) }
        }
        AppParasitics.registerToAppLifecycle(packageName)
        EmoticonButtonHooker.load()
        ClassLoader::class.java.declaredMethods.filter {
            it.name == "loadClass" && it.parameterTypes.firstOrNull() == String::class.java
        }.forEach { method ->
            method.hook {
                after {
                    val name = args.firstOrNull() as? String ?: return@after
                    if (name != DouyinSettingHooker.SETTINGS_ACTIVITY && name !=
                        "com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.entrances.EmojiWidgetUI"
                    ) return@after
                    val type = result as? Class<*> ?: return@after
                    HostData.alignHostClassLoader(type.classLoader)
                    runCatching {
                        if (name == DouyinSettingHooker.SETTINGS_ACTIVITY) DouyinSettingHooker.install()
                        else DouyinChatSession.install()
                    }.onFailure { Log.error("抖音宿主类加载后安装入口失败", it) }
                }
            }
        }
        Instrumentation::class.java.declaredMethods.filter { method ->
            method.name in listOf("callActivityOnCreate", "callActivityOnResume") &&
                method.parameterTypes.firstOrNull() == Activity::class.java
        }.forEach { method ->
            runCatching {
                method.hook {
                    before {
                        val activity = args.firstOrNull() as? Activity ?: return@before
                        if (!isHostActivity(activity, packageName)) return@before
                        HostData.alignHostClassLoader(activity.javaClass.classLoader)
                        HookSettings.initialize(activity)
                        runCatching { DouyinChatSession.install() }.onFailure {
                            if (!chatHookFailureLogged) {
                                chatHookFailureLogged = true
                                Log.error("抖音聊天入口等待宿主加载后重试", it)
                            }
                        }
                    }
                    after {
                        val activity = args.firstOrNull() as? Activity ?: return@after
                        if (!isHostActivity(activity, packageName)) return@after
                        activity.window?.decorView?.post {
                            runCatching {
                                DouyinSettingHooker.inject(activity)
                                EmoticonButtonHooker.attachToActivity(activity)
                            }.onFailure { Log.error("抖音界面入口初始化失败", it) }
                        }
                    }
                }
            }.onFailure { Log.error("安装抖音生命周期入口失败", it) }
        }
        Log.info("Douyin entry loaded on: $packageName ${HostData.toVerStr()}")
    }

    private fun isHostActivity(activity: Activity, packageName: String): Boolean =
        activity.packageName == packageName && !activity.javaClass.name.startsWith(ModuleUtils.modulePackageName)
}
