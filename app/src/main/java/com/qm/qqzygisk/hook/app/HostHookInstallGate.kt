package com.qm.qqzygisk.hook.app

import java.util.concurrent.atomic.AtomicReference

/**
 * 宿主就绪 hook 安装闸门。
 *
 * 状态机：`NOT_STARTED -> INSTALLING -> INSTALLED / FAILED`。
 *
 * - 只有第一个到达的调用能从 [State.NOT_STARTED] 进入 [State.INSTALLING] 并执行安装动作；
 * - [State.INSTALLING] 期间的重入（嵌套 Activity 生命周期事件）与并发调用直接跳过，
 *   绝不会二次执行安装动作；
 * - [State.INSTALLED] / [State.FAILED] 都是终态：成功不再重复安装，失败（含部分 hook 安装后
 *   失败）也不再重试，避免宿主每个 Activity 生命周期都反复改写 hook 入口。
 *
 * 纯状态机，不依赖 Android 框架，可独立单元测试。
 */
internal class HostHookInstallGate {

    enum class State { NOT_STARTED, INSTALLING, INSTALLED, FAILED }

    /** 一次 [install] 调用的结果。 */
    enum class Outcome {
        /** 本次调用执行了安装且成功。 */
        INSTALLED,

        /** 本次调用执行了安装但失败（闸门进入终态 [State.FAILED]）。 */
        FAILED,

        /** 此前已成功安装，本次跳过。 */
        SKIPPED_INSTALLED,

        /** 此前已失败（终态），本次跳过。 */
        SKIPPED_FAILED,

        /** 另一个调用正在安装（重入/并发），本次跳过。 */
        SKIPPED_IN_PROGRESS,
    }

    private val stateRef = AtomicReference(State.NOT_STARTED)

    val state: State get() = stateRef.get()

    /**
     * 尝试执行一次安装。
     *
     * @param action 安装动作，返回是否全部安装成功；抛出的异常同样使闸门进入终态 [State.FAILED] 并向上抛出。
     * @return 本次调用的结果
     */
    fun install(action: () -> Boolean): Outcome {
        if (!stateRef.compareAndSet(State.NOT_STARTED, State.INSTALLING)) return skipped()
        val succeeded = try {
            action()
        } catch (th: Throwable) {
            stateRef.set(State.FAILED)
            throw th
        }
        stateRef.set(if (succeeded) State.INSTALLED else State.FAILED)
        return if (succeeded) Outcome.INSTALLED else Outcome.FAILED
    }

    private fun skipped(): Outcome = when (stateRef.get()) {
        State.INSTALLED -> Outcome.SKIPPED_INSTALLED
        State.FAILED -> Outcome.SKIPPED_FAILED
        else -> Outcome.SKIPPED_IN_PROGRESS
    }
}
