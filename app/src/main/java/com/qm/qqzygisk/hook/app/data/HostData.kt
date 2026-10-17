package com.qm.qqzygisk.hook.app.data

import com.qm.qqzygisk.BuildConfig
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

    fun toVerStr() = BuildConfig.VERSION_NAME
}