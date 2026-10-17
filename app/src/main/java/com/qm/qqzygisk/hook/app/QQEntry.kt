package com.qm.qqzygisk.hook.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.SettingData
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.app.hooker.AntiRevokeHooker
import com.qm.qqzygisk.hook.app.hooker.ChatMenuHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonPanelHooker
import com.qm.qqzygisk.hook.app.hooker.EmotionToPicHooker
import com.qm.qqzygisk.hook.app.hooker.MsgFontHooker
import com.qm.qqzygisk.hook.app.hooker.RepeaterHooker
import com.qm.qqzygisk.hook.app.hooker.SettingHooker
import com.qm.qqzygisk.hook.app.hooker.StartActivityHooker
import com.qm.qqzygisk.hook.app.hooker.SystemCameraHooker
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.parasitic.AppParasitics.setInstrumentation
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.qm.qqzygisk.hook.utils.onAppLifecycle
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities
import java.util.concurrent.atomic.AtomicBoolean

object QQEntry {
    val settings = mutableSetOf<SettingData>()

    /**
     * 依赖宿主启动完成的 hook：它们的安装会强制初始化宿主 UI 类
     * （菜单布局 -> ViewUtils -> BaseApplication.context），必须等宿主自己的 Activity 生命周期。
     */
    private val hostUiHookers = listOf(ChatMenuHooker, RepeaterHooker, SettingHooker)

    /** 宿主 UI 相关 hook 是否已全部安装成功 */
    private val hostUiHooksInstalled = AtomicBoolean(false)

    /** 安装失败日志只输出一次，避免宿主每个 Activity 重复刷屏 */
    private val hostUiHooksFailureLogged = AtomicBoolean(false)

    /** 宿主 ClassLoader 对比日志只输出一次 */
    private val hostClassLoaderLogged = AtomicBoolean(false)

    private var hostPackageName: String = ""

    fun init(
        loader: ClassLoader,
        packageName: String,
    ) {
        settings.clear()
        hostPackageName = packageName
        HostData.init(loader)
        Log.info("running on: ${HostData.toVerStr()}")
        runCatching { NtImageRkeyProvider.installHook() }
            .onFailure { Log.error("安装 NT 图片 rkey hook 失败", it) }
        val generalSettingActivityClass = "com.tencent.mobileqq.activity.photo.CameraPreviewActivity".toAppClass()
        val hookedInstrumentationClass = "com.tencent.biz.richframework.hook.instrumentation.HookedInstrumentation".toAppClass()
        hookedInstrumentationClass
            .resolve()
            .firstMethod { name = "hookInstrumentation" }
            .hook {
                after {
                    setInstrumentation()
                }
            }

        installHostActivityGate()

        onAppLifecycle {
            attachBaseContext { baseContext, _ ->
                HookSettings.initialize(baseContext)
                Log.info("hook settings: ${HookSettings.dump(settings)}")
            }
            onCreate {
                registerModuleAppActivities(proxy = generalSettingActivityClass)
                AntiRevokeHooker.retry()
                RepeaterHooker.retry()
            }
        }
        val hooks =
            listOf(
                ChatMenuHooker,
                RepeaterHooker,
                EmoticonButtonHooker,
                EmoticonPanelHooker,
                EmotionToPicHooker,
                StartActivityHooker,
                SystemCameraHooker,
                SettingHooker,
                MsgFontHooker,
                AntiRevokeHooker,
            )
        hooks.forEach { hooker ->
            if (hooker.isShow) {
                settings.add(hooker.toSettingData())
                settings.addAll(hooker.extraSettings)
            }
            if (hooker !in hostUiHookers) hooker.load()
        }
        StartActivityHooker.decorators.forEach { hooker ->
            if (hooker.isShow) {
                settings.add(hooker.toSettingData())
                settings.addAll(hooker.extraSettings)
            }
        }
        AppParasitics.registerToAppLifecycle(packageName)
        Log.info("runed on: ${HostData.toVerStr()}")
    }

    /**
     * 安装宿主 Activity 生命周期入口。
     *
     * 宿主 Application.onCreate 返回时宿主可能还没有真正完成 Application 初始化：
     * 宿主真实 Application 的 onCreate 由 RFix/qfix 代理延迟派发，而宿主 UI 类的静态初始化
     * 依赖该初始化（ViewUtils.<clinit> 需要 BaseApplication.context）。过早安装会失败并
     * 永久污染这些宿主类，因此这里只挂框架 Instrumentation 的 Activity 生命周期入口，
     * 等宿主自己的 Activity 真正创建/可见时再安装，且每次宿主生命周期时机都可重试。
     */
    private fun installHostActivityGate() {
        val activityGate: (Activity) -> Unit = { activity ->
            runCatching { onHostActivityLifecycle(activity) }
                .onFailure { Log.error("宿主 Activity 生命周期入口执行失败", it) }
        }
        listOf(
            "callActivityOnCreate" to arrayOf<Any>(Activity::class, Bundle::class),
            "callActivityOnCreate" to arrayOf<Any>(Activity::class, Bundle::class, PersistableBundle::class),
            "callActivityOnResume" to arrayOf<Any>(Activity::class),
        ).forEach { (methodName, parameterTypes) ->
            runCatching {
                Instrumentation::class
                    .resolve()
                    .optional(silent = true)
                    .firstMethodOrNull {
                        name = methodName
                        parameters(*parameterTypes)
                    }
                    ?.hook {
                        before {
                            val activity = args.getOrNull(0) as? Activity ?: return@before
                            activityGate(activity)
                        }
                    }
            }.onFailure { Log.error("安装宿主 Activity 生命周期入口失败: $methodName", it) }
        }
    }

    /**
     * 把宿主类解析用的 ClassLoader 对齐到真实宿主 Activity 的 ClassLoader。
     *
     * 宿主（RFix/qfix）可能在模块初始化（早期 LoadedApk 时机）之后才替换真正加载宿主类的
     * ClassLoader，模块初始化时拿到的 ClassLoader 不一定是线上 QQ 实际使用的那个。
     * 这里在宿主 Activity 生命周期入口对比并（必要时）对齐，之后宿主类扫描/hook 都使用
     * 同一个来源，避免 hook 到过期的宿主类副本。扫描缓存按 ClassLoader 标识重建，无需手动失效。
     */
    private fun alignHostClassLoader(activity: Activity) {
        val moduleLoader = HostData.appClassLoader
        val hostLoader = activity.javaClass.classLoader ?: return
        if (hostClassLoaderLogged.compareAndSet(false, true)) {
            Log.info(
                "宿主 ClassLoader 对比: module=${moduleLoader.javaClass.name}@${System.identityHashCode(moduleLoader)} " +
                    "host=${hostLoader.javaClass.name}@${System.identityHashCode(hostLoader)} same=${moduleLoader === hostLoader}",
            )
        }
        if (moduleLoader !== hostLoader) {
            HostData.init(hostLoader)
            Log.warn("宿主 ClassLoader 与模块初始化时不同, 已对齐为宿主 Activity 的 ClassLoader: ${hostLoader.javaClass.name}")
        }
    }

    /**
     * 宿主自己的 Activity 走到生命周期入口时安装依赖宿主资源的 hook。
     * 每个 hooker 自己保证幂等，安装失败时不置位，宿主后续生命周期时机可以继续重试。
     */
    private fun onHostActivityLifecycle(activity: Activity) {
        if (hostUiHooksInstalled.get()) return
        if (!isHostActivity(activity)) return
        alignHostClassLoader(activity)
        val start = SystemClock.elapsedRealtime()
        val chatMenu = ChatMenuHooker.load()
        val repeater = RepeaterHooker.load()
        val setting = SettingHooker.load()
        val emoticonFailure = runCatching { EmoticonButtonHooker.installHostHooks() }.exceptionOrNull()
        val cost = SystemClock.elapsedRealtime() - start
        if (chatMenu && repeater && setting && emoticonFailure == null) {
            if (hostUiHooksInstalled.compareAndSet(false, true)) {
                Log.info("依赖宿主资源的 hook 安装完成, 耗时 ${cost}ms")
            }
        } else if (hostUiHooksFailureLogged.compareAndSet(false, true)) {
            Log.warn(
                "依赖宿主资源的 hook 未全部安装, 等待宿主后续生命周期重试: " +
                    "chatMenu=$chatMenu repeater=$repeater setting=$setting emoticon=${emoticonFailure == null}, 耗时 ${cost}ms",
                emoticonFailure,
            )
        }
    }

    /**
     * 只接受宿主自己的 Activity：跳过模块自身注入的 Activity（代理），
     * 也跳过其它包（插件等）的 Activity，等宿主自己的 Activity 真正创建。
     */
    private fun isHostActivity(activity: Activity): Boolean {
        if (activity.javaClass.name.startsWith(ModuleUtils.modulePackageName)) return false
        return activity.packageName == hostPackageName
    }
}
