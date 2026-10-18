package com.qm.qqzygisk.hook.app.data

import com.qm.qqzygisk.BuildConfig
import com.qm.qqzygisk.hook.utils.Log
import org.lsposed.lsparanoid.Obfuscate
import kotlin.properties.Delegates

@Obfuscate
object HostData {
    var appClassLoader by Delegates.notNull<ClassLoader>()

    /**
     * 仅解析宿主类，不触发宿主类的静态初始化。
     *
     * 菜单布局类（如 QQCustomMenuExpandableLayout）的静态初始化依赖宿主 Application 级资源，
     * 过早初始化会在宿主完成自身启动前失败并永久污染该类（NoClassDefFoundError）。
     * 真正的静态初始化由 hook 安装路径（Hooks.ensureDeclaringClassInitialized）在合适时机触发。
     */
    fun String.toAppClass(): Class<*> = Class.forName(this, false, appClassLoader)

    fun init(loader: ClassLoader) {
        appClassLoader = loader
    }

    /**
     * 把宿主类解析用的 ClassLoader 对齐到真实运行宿主代码的那一份。
     *
     * 微信内置 Tinker（热修复），`TinkerApplication.attachBaseContext` 会把 `LoadedApk.mClassLoader`
     * 替换成 `TinkerClassLoader`；模块在 `createOrUpdateClassLoaderLocked` 时机拿到的是替换前的
     * PathClassLoader。用旧 loader 反射加载宿主类会得到**另一份 class 拷贝**，其静态状态（例如
     * MMKernel 单例）全是未初始化的，调用时会抛 `Kernel not initialized by MMApplication!`。
     *
     * 因此每次从真实宿主 View/Activity 上拿到 ClassLoader 时都对齐一次。
     */
    fun alignHostClassLoader(loader: ClassLoader?) {
        val current = loader ?: return
        if (current === appClassLoader) return
        appClassLoader = current
        Log.warn("宿主 ClassLoader 已对齐: ${current.javaClass.name}")
    }

    fun toVerStr() = BuildConfig.VERSION_NAME
}