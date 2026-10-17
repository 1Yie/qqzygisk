package com.qm.qqzygisk.hook.utils

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * [ShapeSelector] 行为回归测试。
 *
 * 形状取值来自真实宿主 APK（QQ 9.3.70 `classes22.dex`）：
 * - 设置项构造器 `(Context,int,CharSequence,int,String)`；
 * - 分组构造器只剩 Kotlin 默认参数合成构造器 `(List,CharSequence,CharSequence,int,DefaultConstructorMarker)`，
 *   R8 删掉了真实三参构造器 —— 这正是必须支持“形状回退”的原因；
 * - 分组列表方法 `(Context)->java.util.List`。
 *
 * 断言的核心是“唯一性”：命中不唯一或 0 命中都必须被拒绝，而不是取第一个。
 */
class ShapeSelectorTest {

    private val itemShape = ShapeSelector.constructorShape(
        "设置项构造器",
        listOf(
            Context::class.java,
            Int::class.javaPrimitiveType!!,
            CharSequence::class.java,
            Int::class.javaPrimitiveType!!,
            String::class.java,
        ),
    )

    private val groupRealShape = ShapeSelector.constructorShape(
        "真实三参构造器",
        listOf(List::class.java, CharSequence::class.java, CharSequence::class.java),
    )

    private val groupMaskedShape = ShapeSelector.maskedConstructorShape(
        "Kotlin 默认参数合成构造器",
        listOf(List::class.java, CharSequence::class.java, CharSequence::class.java),
    )

    @Test
    fun `设置项构造器形状按参数类型精确匹配`() {
        assertTrue(itemShape.matches(FakeItem::class.java.declaredConstructors.single()))
    }

    @Test
    fun `包装类型或参数顺序不同都不算命中`() {
        assertFalse(itemShape.matches(FakeItemBoxedInteger::class.java.declaredConstructors.single()))
        assertFalse(itemShape.matches(FakeItemSwapped::class.java.declaredConstructors.single()))
    }

    @Test
    fun `分组方法形状要求 Context 入参和 List 返回值`() {
        val methods = FakeProvider::class.java.declaredMethods.associateBy { it.name }
        assertTrue(
            ShapeSelector.matches(
                methods.getValue("groups"),
                listOf(Context::class.java),
                returnType = List::class.java,
            ),
        )
        assertFalse(
            ShapeSelector.matches(
                methods.getValue("notGroups"),
                listOf(Context::class.java),
                returnType = List::class.java,
            ),
        )
        assertFalse(
            ShapeSelector.matches(
                methods.getValue("groups"),
                listOf(CharSequence::class.java),
                returnType = List::class.java,
            ),
        )
    }

    @Test
    fun `构造器按 JVM 约定视作返回 void`() {
        val masked = FakeGroupMaskedOnly::class.java.declaredConstructors.single()
        assertTrue(ShapeSelector.matches(masked, masked.parameterTypes.toList(), returnType = Void.TYPE))
        assertFalse(ShapeSelector.matches(masked, masked.parameterTypes.toList(), returnType = List::class.java))
    }

    @Test
    fun `合成构造器形状命中默认参数构造器且不命中真实构造器`() {
        val masked = FakeGroupMaskedOnly::class.java.declaredConstructors.single()
        assertTrue(groupMaskedShape.matches(masked))
        assertFalse(groupRealShape.matches(masked))
    }

    @Test
    fun `合成构造器形状要求末位是 DefaultConstructorMarker`() {
        val otherMarker = FakeGroupFiveArgOtherMarker::class.java.declaredConstructors.single()
        assertEquals(5, otherMarker.parameterTypes.size)
        assertEquals(String::class.java, otherMarker.parameterTypes[4])
        assertFalse(groupMaskedShape.matches(otherMarker))
    }

    @Test
    fun `优先真实三参构造器`() {
        val constructor = found(
            ShapeSelector.pickPreferred(
                "分组构造器",
                FakeGroupBoth::class.java.declaredConstructors.toList(),
                listOf(groupRealShape, groupMaskedShape),
                ShapeSelector::signature,
            ),
        )
        assertEquals(3, constructor.parameterTypes.size)
    }

    @Test
    fun `缺少真实构造器时退回合成构造器`() {
        val constructor = found(
            ShapeSelector.pickPreferred(
                "分组构造器",
                FakeGroupMaskedOnly::class.java.declaredConstructors.toList(),
                listOf(groupRealShape, groupMaskedShape),
                ShapeSelector::signature,
            ),
        )
        assertEquals(5, constructor.parameterTypes.size)
        assertEquals(Int::class.javaPrimitiveType, constructor.parameterTypes[3])
        assertEquals(DefaultConstructorMarker::class.java, constructor.parameterTypes[4])
    }

    @Test
    fun `优先形状命中不唯一时立即拒绝而不回退`() {
        // 真实三参形状命中 2 个；若允许回退，合成形状只会命中 1 个而错误地返回结果。
        val candidates = FakeGroupBoth::class.java.declaredConstructors.toList() +
            FakeGroupRealOnly::class.java.declaredConstructors.toList()
        val reason = rejected(
            ShapeSelector.pickPreferred(
                "分组构造器",
                candidates,
                listOf(groupRealShape, groupMaskedShape),
                ShapeSelector::signature,
            ),
        )
        assertTrue(reason, reason.contains("真实三参构造器"))
        assertTrue(reason, reason.contains("2 个"))
    }

    @Test
    fun `同形状多命中判为歧义`() {
        val candidates = FakeGroupMaskedOnly::class.java.declaredConstructors.toList() +
            FakeGroupMaskedOnly2::class.java.declaredConstructors.toList()
        val reason = rejected(
            ShapeSelector.pickPreferred(
                "分组构造器",
                candidates,
                listOf(groupRealShape, groupMaskedShape),
                ShapeSelector::signature,
            ),
        )
        assertTrue(reason, reason.contains("2 个"))
        assertTrue(reason, reason.contains(FakeGroupMaskedOnly::class.java.name))
    }

    @Test
    fun `0 命中给出可诊断原因`() {
        val reason = rejected(
            ShapeSelector.pickPreferred(
                "分组构造器",
                FakeGroupWithoutShape::class.java.declaredConstructors.toList(),
                listOf(groupRealShape, groupMaskedShape),
                ShapeSelector::signature,
            ),
        )
        assertTrue(reason, reason.contains("0 个"))
        assertTrue(reason, reason.contains("真实三参构造器"))
    }

    @Test
    fun `pickUnique 拒绝重复命中的候选类`() {
        val classes = listOf(FakeGroupBoth::class.java, FakeGroupMaskedOnly::class.java)
        val reason = rejected(
            ShapeSelector.pickUnique("分组类", classes, { it.name }) { candidate ->
                candidate.declaredConstructors.any { groupRealShape.matches(it) || groupMaskedShape.matches(it) }
            },
        )
        assertTrue(reason, reason.contains("2 个"))

        val single = found(
            ShapeSelector.pickUnique("分组类", classes, { it.name }) { it == FakeGroupBoth::class.java },
        )
        assertEquals(FakeGroupBoth::class.java, single)
    }

    @Test
    fun `matchesPrefix 只比对前缀且长度不足即失败`() {
        val masked = FakeGroupMaskedOnly::class.java.declaredConstructors.single()
        val prefix = listOf(
            List::class.java,
            CharSequence::class.java,
            CharSequence::class.java,
            Int::class.javaPrimitiveType!!,
        )
        assertTrue(ShapeSelector.matchesPrefix(masked, prefix))
        assertFalse(ShapeSelector.matchesPrefix(masked, listOf(CharSequence::class.java, List::class.java)))
        assertFalse(
            ShapeSelector.matchesPrefix(
                masked,
                prefix + DefaultConstructorMarker::class.java + String::class.java,
            ),
        )
    }

    @Test
    fun `signature 输出类名方法名参数与返回值`() {
        val text = ShapeSelector.signature(FakeGroupMaskedOnly::class.java.declaredConstructors.single())
        assertTrue(text, text.contains(FakeGroupMaskedOnly::class.java.name))
        assertTrue(
            text,
            text.contains(
                "(java.util.List,java.lang.CharSequence,java.lang.CharSequence,int," +
                    "kotlin.jvm.internal.DefaultConstructorMarker)->void",
            ),
        )
    }

    private fun <T> found(pick: ShapeSelector.Pick<T>): T = when (pick) {
        is ShapeSelector.Pick.Found -> pick.value
        is ShapeSelector.Pick.Rejected -> throw AssertionError("期望唯一命中，实际被拒绝: ${pick.reason}")
    }

    private fun rejected(pick: ShapeSelector.Pick<*>): String = when (pick) {
        is ShapeSelector.Pick.Found -> throw AssertionError("期望被拒绝，实际命中: ${pick.value}")
        is ShapeSelector.Pick.Rejected -> pick.reason
    }
}

/** 设置项形状：`(Context,int,CharSequence,int,String)`。 */
private class FakeItem {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, id: Int, text: CharSequence, iconRes: Int, key: String?)
}

/** 第二参是包装类型 `Integer`：与 `int` 形状不匹配。 */
private class FakeItemBoxedInteger {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, id: Int?, text: CharSequence, iconRes: Int, key: String?)
}

/** 参数顺序不同：与形状不匹配。 */
private class FakeItemSwapped {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, text: CharSequence, id: Int, iconRes: Int, key: String?)
}

/** 分组方法形状：`(Context)->List` 与干扰项。 */
private class FakeProvider {
    @Suppress("UNUSED_PARAMETER")
    fun groups(context: Context): List<Any?> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun notGroups(context: Context): String = ""
}

/** 真实宿主分组形状：只剩 Kotlin 默认参数合成构造器。 */
private class FakeGroupMaskedOnly {
    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<Any?>, topTitle: CharSequence, bottomTitle: CharSequence, mask: Int, marker: DefaultConstructorMarker?)
}

/** 与上者同形状的第二个类：用于验证“同形状多命中即歧义”。 */
private class FakeGroupMaskedOnly2 {
    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<Any?>, topTitle: CharSequence, bottomTitle: CharSequence, mask: Int, marker: DefaultConstructorMarker?)
}

/** 五参但末位不是 DefaultConstructorMarker：合成构造器形状必须拒绝。 */
private class FakeGroupFiveArgOtherMarker {
    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<Any?>, topTitle: CharSequence, bottomTitle: CharSequence, mask: Int, other: String)
}

/** 同时带真实三参构造器与合成构造器：必须优先真实构造器。 */
private class FakeGroupBoth {
    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<Any?>, topTitle: CharSequence, bottomTitle: CharSequence)

    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<Any?>, topTitle: CharSequence, bottomTitle: CharSequence, mask: Int, marker: DefaultConstructorMarker?)
}

/** 只有真实三参构造器：用于验证优先形状命中不唯一时不回退。 */
private class FakeGroupRealOnly {
    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<Any?>, topTitle: CharSequence, bottomTitle: CharSequence)
}

/** 只有无关构造器：0 命中。 */
private class FakeGroupWithoutShape {
    @Suppress("UNUSED_PARAMETER")
    constructor(title: String)
}
