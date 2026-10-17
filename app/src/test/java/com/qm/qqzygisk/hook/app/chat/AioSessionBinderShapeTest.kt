package com.qm.qqzygisk.hook.app.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * [AioSessionBinder] “按形状定位” 回归测试。
 *
 * 形状取自真实宿主 APK（QQ 9.3.70 `classes5.dex`）：
 * - `com.tencent.aio.base.chat.ChatPie` 有唯一 `AIOParam` 实例字段（`h`）；
 * - 唯一创建根方法 `g(ChatFragment, LayoutInflater, ViewGroup, boolean): View`。
 *
 * Fragment 基类由调用方显式传入（真实路径用宿主类加载器解析，见 [AioSessionBinder]），
 * 这里传入本测试自带的 [Fragment] 作为基准，避免任何全局查找。
 * 断言核心：成员混淆短名改名后仍按类型命中；形状不符、基类身份不符或同形状多命中一律拒绝。
 */
class AioSessionBinderShapeTest {

    private val fragmentBase: Class<*> = Fragment::class.java

    @Test
    fun `创建根方法按参数类型命中且与混淆方法名无关`() {
        assertTrue(AioSessionBinder.isCreateRoot(method(FakeChatPie::class.java, "g"), fragmentBase))
        assertTrue(
            AioSessionBinder.isCreateRoot(method(FakeRenamedChatPie::class.java, "renamedRoot"), fragmentBase),
        )
    }

    @Test
    fun `创建根方法拒绝静态_返回类型或参数形状不符`() {
        assertFalse(AioSessionBinder.isCreateRoot(method(FakeShapes::class.java, "staticRoot"), fragmentBase))
        assertFalse(AioSessionBinder.isCreateRoot(method(FakeShapes::class.java, "wrongReturn"), fragmentBase))
        assertFalse(AioSessionBinder.isCreateRoot(method(FakeShapes::class.java, "wrongParamOrder"), fragmentBase))
        assertFalse(AioSessionBinder.isCreateRoot(method(FakeShapes::class.java, "threeParams"), fragmentBase))
    }

    @Test
    fun `Fragment 基类身份不符时不命中`() {
        assertFalse(AioSessionBinder.isCreateRoot(method(FakeChatPie::class.java, "g"), CharSequence::class.java))
    }

    @Test
    fun `聊天根类要求唯一 AIOParam 字段与唯一创建根方法`() {
        assertTrue(AioSessionBinder.matchesChatPie(FakeChatPie::class.java, FakeAioParam::class.java, fragmentBase))
        assertTrue(
            AioSessionBinder.matchesChatPie(FakeRenamedChatPie::class.java, FakeAioParam::class.java, fragmentBase),
        )
        assertFalse(
            AioSessionBinder.matchesChatPie(FakeChatPieTwoParams::class.java, FakeAioParam::class.java, fragmentBase),
        )
        assertFalse(
            AioSessionBinder.matchesChatPie(FakeChatPieTwoRoots::class.java, FakeAioParam::class.java, fragmentBase),
        )
        assertFalse(
            AioSessionBinder.matchesChatPie(FakeChatPieNoRoot::class.java, FakeAioParam::class.java, fragmentBase),
        )
    }

    private fun method(type: Class<*>, name: String): Method = type.declaredMethods.first { it.name == name }

    private class FakeAioParam

    private class FakeChatFragment : Fragment()

    @Suppress("unused")
    private class FakeChatPie {
        private val h: FakeAioParam = FakeAioParam()

        fun g(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            preload: Boolean,
        ): View = throw UnsupportedOperationException()
    }

    @Suppress("unused")
    private class FakeRenamedChatPie {
        private val zz: FakeAioParam = FakeAioParam()

        fun renamedRoot(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            preload: Boolean,
        ): View = throw UnsupportedOperationException()
    }

    @Suppress("unused")
    private class FakeChatPieTwoParams {
        private val h: FakeAioParam = FakeAioParam()
        private val i: FakeAioParam = FakeAioParam()

        fun g(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            preload: Boolean,
        ): View = throw UnsupportedOperationException()
    }

    @Suppress("unused")
    private class FakeChatPieTwoRoots {
        private val h: FakeAioParam = FakeAioParam()

        fun g(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            preload: Boolean,
        ): View = throw UnsupportedOperationException()

        fun g2(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            preload: Boolean,
        ): View = throw UnsupportedOperationException()
    }

    @Suppress("unused")
    private class FakeChatPieNoRoot {
        private val h: FakeAioParam = FakeAioParam()
    }

    @Suppress("unused")
    private class FakeShapes {
        companion object {
            @JvmStatic
            fun staticRoot(
                fragment: FakeChatFragment,
                inflater: LayoutInflater,
                container: ViewGroup,
                preload: Boolean,
            ): View = throw UnsupportedOperationException()
        }

        fun wrongReturn(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            preload: Boolean,
        ) {
        }

        fun wrongParamOrder(
            inflater: LayoutInflater,
            fragment: FakeChatFragment,
            container: ViewGroup,
            preload: Boolean,
        ): View = throw UnsupportedOperationException()

        fun threeParams(
            fragment: FakeChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
        ): View = throw UnsupportedOperationException()
    }
}
