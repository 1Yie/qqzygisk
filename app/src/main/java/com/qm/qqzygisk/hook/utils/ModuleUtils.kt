package com.qm.qqzygisk.hook.utils

import android.app.AndroidAppHelper
import com.qm.qqzygisk.BuildConfig
import com.v7878.zygisk.ZygoteLoader

internal object ModuleUtils {
    private var isZygiskLoaded = false

    /** Xposed 模块是否已被装载 */
    private var isXpLoaded = false
    var modulePackageName = BuildConfig.APPLICATION_ID

    val isXpEnvironment get() = isXpLoaded

    /**
     * 模块 APK 在宿主私有目录中的副本后缀（安装脚本 customize.sh 负责放置）。
     * 目录名里的 U+200B 是为了避免宿主自己的目录清理逻辑扫到该文件。
     */
    private const val HOST_MODULE_APK_SUFFIX = "/files/mmkv\u200B/fugfhj"

    /** 兼容旧包名的兜底宿主（路径只在拿不到宿主包名时才用它） */
    private const val FALLBACK_HOST_PACKAGE = "com.tencent.mobileqq"

    /** LSPosed 模式下由框架下发的模块 APK 路径 */
    private var xpModuleAppFilePath: String? = null

    /**
     * 资源注入用的模块 APK 路径。
     *
     * Zygisk 模式下模块 APK 需要由安装脚本拷到宿主自己的数据目录里（应用无权读取其它应用的数据目录），
     * 所以必须按当前宿主包名现算，不能写死成 QQ 的路径，否则微信里 addAssetPath 会静默失败、
     * 模块资源全部拿不到。
     */
    val moduleAppFilePath: String
        get() = xpModuleAppFilePath
            ?: "/data/user/0/${packageName ?: FALLBACK_HOST_PACKAGE}$HOST_MODULE_APK_SUFFIX"

    /**
     * Hook 宿主的包名
     */
    val packageName: String? get() = if (isXpEnvironment) AndroidAppHelper.currentApplicationInfo().packageName else ZygoteLoader.getPackageName()

    fun onZygiskLoadModule() {
        isZygiskLoaded = true
    }

    fun onXpLoadModule(appFilePath: String) {
        isXpLoaded = true
        xpModuleAppFilePath = appFilePath
    }
}