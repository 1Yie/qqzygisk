package com.qm.qqzygisk.hook.parasitic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import com.qm.qqzygisk.hook.utils.value

/**
 * 当前 Hook APP 的生命周期实例处理类
 *
 * - 请使用 [onAppLifecycle] 方法来获取 [AppLifecycle]
 * @param isOnFailureThrowToApp 是否在发生异常时将异常抛出给宿主
 */
class AppLifecycle(private val isOnFailureThrowToApp: Boolean) {

    /**
     * 监听当前 Hook APP 装载 [Application.attachBaseContext]
     * @param result 回调 - ([Context] baseContext,[Boolean] 是否已执行 super)
     */
    fun attachBaseContext(result: (baseContext: Context, hasCalledSuper: Boolean) -> Unit) {
       AppParasitics.AppLifecycleActor.get("test").attachBaseContextCallback = result
    }

    /**
     * 监听当前 Hook APP 装载 [Application.onCreate]
     * @param initiate 方法体
     */
    fun onCreate(initiate: Application.() -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").onCreateCallback = initiate
    }

    /**
     * 监听当前 Hook APP (宿主) 的 Activity 创建。
     *
     * 回调在宿主原始 `Instrumentation.callActivityOnCreate` 之前派发（由模块的
     * Instrumentation 委托直接调用），因此可在宿主 Activity 方法体执行前完成依赖宿主 UI 的安装。
     * @param result 回调 - ([Activity] 即将创建的 Activity)
     */
    fun beforeActivityCreate(result: (Activity) -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").beforeActivityCreateCallback = result
    }

    /**
     * 监听当前 Hook APP 装载 [Application.onTerminate]
     * @param initiate 方法体
     */
    fun onTerminate(initiate: Application.() -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").onTerminateCallback = initiate
    }

    /**
     * 监听当前 Hook APP 装载 [Application.onLowMemory]
     * @param initiate 方法体
     */
    fun onLowMemory(initiate: Application.() -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").onLowMemoryCallback = initiate
    }

    /**
     * 监听当前 Hook APP 装载 [Application.onTrimMemory]
     * @param result 回调 - ([Application] 当前实例,[Int] 类型)
     */
    fun onTrimMemory(result: (self: Application, level: Int) -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").onTrimMemoryCallback = result
    }

    /**
     * 监听当前 Hook APP 装载 [Application.onConfigurationChanged]
     * @param result 回调 - ([Application] 当前实例,[Configuration] 配置实例)
     */
    fun onConfigurationChanged(result: (self: Application, config: Configuration) -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").onConfigurationChangedCallback = result
    }

    /**
     * 注册系统广播监听
     * @param action 系统广播 Action
     * @param result 回调 - ([Context] 当前上下文,[Intent] 当前 Intent)
     */
    fun registerReceiver(vararg action: String, result: (context: Context, intent: Intent) -> Unit) {
        if (action.isNotEmpty())
            AppParasitics.AppLifecycleActor.get("test").onReceiverActionsCallbacks[action.value()] = action to result
    }

    /**
     * 注册系统广播监听
     * @param filter 广播意图过滤器
     * @param result 回调 - ([Context] 当前上下文,[Intent] 当前 Intent)
     */
    fun registerReceiver(filter: IntentFilter, result: (context: Context, intent: Intent) -> Unit) {
        AppParasitics.AppLifecycleActor.get("test").onReceiverFiltersCallbacks[filter.toString()] = filter to result
    }

    /** 设置创建生命周期监听回调 */
    fun build() {
        if (AppParasitics.AppLifecycleActor.isOnFailureThrowToApp == null)
            AppParasitics.AppLifecycleActor.isOnFailureThrowToApp = isOnFailureThrowToApp
    }
}
