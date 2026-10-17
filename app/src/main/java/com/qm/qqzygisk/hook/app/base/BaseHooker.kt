package com.qm.qqzygisk.hook.app.base

import com.qm.qqzygisk.hook.utils.Log

abstract class BaseHooker: BaseSetting() {
    private var loaded = false
    private var failureLogged = false

    abstract fun initOnce()

    /**
     * 安装 hook。
     *
     * 只有安装成功才置位：宿主尚未就绪（例如依赖宿主资源的类过早初始化）时安装会失败，
     * 保持未安装状态可以让更晚的宿主生命周期时机重试，而不是永远放弃。
     * 失败日志只输出首次，避免宿主每个生命周期时机重试时刷屏。
     *
     * @return 本次调用（或此前）是否安装成功
     */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            initOnce()
            loaded = true
            true
        } catch (th: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                Log.error("initOnce $name Failed", th)
            }
            false
        }
    }
}
