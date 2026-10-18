package com.qm.qqzygisk.hook.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HostHookInstallGate] 的行为测试。
 *
 * 全部通过实际状态机调用验证，不读取源码文本，因此与实现细节无关。
 */
class HostHookInstallGateTest {

    @Test
    fun `首次安装成功后不再重复安装`() {
        val gate = HostHookInstallGate()
        val runs = AtomicInteger()

        assertEquals(HostHookInstallGate.Outcome.INSTALLED, gate.install { runs.incrementAndGet(); true })
        assertEquals(HostHookInstallGate.Outcome.SKIPPED_INSTALLED, gate.install { runs.incrementAndGet(); true })

        assertEquals(1, runs.get())
        assertEquals(HostHookInstallGate.State.INSTALLED, gate.state)
    }

    @Test
    fun `安装失败后锁定终态不再重试`() {
        val gate = HostHookInstallGate()
        val runs = AtomicInteger()

        assertEquals(HostHookInstallGate.Outcome.FAILED, gate.install { runs.incrementAndGet(); false })
        assertEquals(HostHookInstallGate.Outcome.SKIPPED_FAILED, gate.install { runs.incrementAndGet(); true })

        assertEquals(1, runs.get())
        assertEquals(HostHookInstallGate.State.FAILED, gate.state)
    }

    @Test
    fun `安装抛异常同样锁定终态且异常上抛`() {
        val gate = HostHookInstallGate()
        val runs = AtomicInteger()
        var thrown: Throwable? = null

        try {
            gate.install {
                runs.incrementAndGet()
                throw IllegalStateException("partial patch failed")
            }
        } catch (th: Throwable) {
            thrown = th
        }

        assertTrue(thrown is IllegalStateException)
        assertEquals(HostHookInstallGate.State.FAILED, gate.state)
        assertEquals(HostHookInstallGate.Outcome.SKIPPED_FAILED, gate.install { runs.incrementAndGet(); true })
        assertEquals(1, runs.get())
    }

    @Test
    fun `重入的生命周期回调不会重复安装`() {
        val gate = HostHookInstallGate()
        val runs = AtomicInteger()
        var nested: HostHookInstallGate.Outcome? = null

        val outer = gate.install {
            runs.incrementAndGet()
            nested = gate.install { runs.incrementAndGet(); true }
            true
        }

        assertEquals(HostHookInstallGate.Outcome.INSTALLED, outer)
        assertEquals(HostHookInstallGate.Outcome.SKIPPED_IN_PROGRESS, nested)
        assertEquals(1, runs.get())
    }

    @Test
    fun `并发生命周期回调只执行一次安装`() {
        val gate = HostHookInstallGate()
        val runs = AtomicInteger()
        val threads = 16
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val skipped = CountDownLatch(threads - 1)
        val outcomes = arrayOfNulls<HostHookInstallGate.Outcome>(threads)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val futures = (0 until threads).map { index ->
                pool.submit {
                    ready.countDown()
                    go.await()
                    outcomes[index] = gate.install {
                        runs.incrementAndGet()
                        actionEntered.countDown()
                        releaseAction.await()
                        true
                    }
                    if (outcomes[index] == HostHookInstallGate.Outcome.SKIPPED_IN_PROGRESS) skipped.countDown()
                    finished.countDown()
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS))

            // 等待全部并发竞争者跳过，再允许唯一安装动作完成。
            assertTrue(skipped.await(5, TimeUnit.SECONDS))

            releaseAction.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            futures.forEach { it.get() }

            assertEquals(1, runs.get())
            assertEquals(1, outcomes.count { it == HostHookInstallGate.Outcome.INSTALLED })
            assertEquals(threads - 1, outcomes.count { it == HostHookInstallGate.Outcome.SKIPPED_IN_PROGRESS })
        } finally {
            releaseAction.countDown()
            pool.shutdownNow()
        }
    }
}
