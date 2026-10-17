package com.qm.qqzygisk.hook.utils

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 纯反射形状匹配：只做“签名比对 + 唯一性裁决”，不含任何宿主混淆名假设。
 *
 * 宿主每次版本更新都可能位移短名（`cd.a` -> `fd.a`），因此目标选择必须按
 * “参数/返回类型形状”搜索并强制唯一：0 个命中或多个命中都算失败，
 * 禁止“取第一个命中”这种在版本升级时静默选错目标的写法。
 *
 * 本工具不依赖 KavaRef，也不缓存任何失败结果。
 */
object ShapeSelector {

    /** kotlin stdlib 稳定 ABI 名（非宿主混淆名）：Kotlin 默认参数合成构造器的标记类。 */
    private const val DEFAULT_CONSTRUCTOR_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

    /** 唯一性裁决结果。 */
    sealed interface Pick<out T> {
        /** 恰好唯一命中。 */
        data class Found<T>(val value: T) : Pick<T>

        /** 没有命中，或命中不唯一；[reason] 为可直接打印的诊断信息。 */
        data class Rejected(val reason: String) : Pick<Nothing>
    }

    /** 一种候选形状，用于按优先级择优（如“优先真实构造器，缺失时退回合成构造器”）。 */
    class Shape<T>(val label: String, val matches: (T) -> Boolean)

    /**
     * 参数类型逐个精确比对（含基本类型、顺序敏感）；[returnType] 非空时同时比对返回值。
     *
     * 构造器没有返回值，按 JVM 约定其返回值视作 `void`，因此 `returnType = Void.TYPE` 可命中构造器。
     */
    fun matches(
        executable: Executable,
        parameterTypes: List<Class<*>>,
        returnType: Class<*>? = null,
    ): Boolean {
        val actual = executable.parameterTypes
        if (actual.size != parameterTypes.size || !prefixMatches(actual, parameterTypes)) return false
        return returnType == null || returnTypeOf(executable) == returnType
    }

    /** 只比对前 [parameterTypes].size 个参数类型，用于“前缀 + 尾部合成参数”的 Kotlin 构造器。 */
    fun matchesPrefix(executable: Executable, parameterTypes: List<Class<*>>): Boolean =
        prefixMatches(executable.parameterTypes, parameterTypes)

    /**
     * 可读签名，用于日志与错误信息。
     *
     * 构造器没有返回值，按 JVM 约定记为 `void`。
     */
    fun signature(executable: Executable): String = buildString {
        append(executable.declaringClass.name)
        append('#')
        append(executable.name)
        append('(')
        append(executable.parameterTypes.joinToString(",") { it.name })
        append(")->")
        append(returnTypeOf(executable).name)
    }

    /** 精确构造器形状：参数类型完全一致（顺序、基本类型都一致）。 */
    fun constructorShape(label: String, parameterTypes: List<Class<*>>): Shape<Constructor<*>> =
        Shape(label) { matches(it, parameterTypes) }

    /**
     * Kotlin 默认参数合成构造器形状：真实参数 + `int mask` + `DefaultConstructorMarker`。
     *
     * R8 会删掉没有调用点的真实构造器、只留这个合成构造器（宿主 9.3.70 的分组类就是如此），
     * 因此真实形状缺失时按本形状回退。末尾标记类按 kotlin stdlib 稳定名比对，不做“任意引用类型”的宽松匹配。
     */
    fun maskedConstructorShape(label: String, realParameterTypes: List<Class<*>>): Shape<Constructor<*>> =
        Shape(label) { executable ->
            val actual = executable.parameterTypes
            val maskIndex = realParameterTypes.size
            actual.size == maskIndex + 2 &&
                actual[maskIndex] == Int::class.javaPrimitiveType &&
                actual[maskIndex + 1].name == DEFAULT_CONSTRUCTOR_MARKER &&
                prefixMatches(actual, realParameterTypes)
        }

    /**
     * 恰好一个候选命中才返回 [Pick.Found]；0 个或多个都返回 [Pick.Rejected]。
     *
     * @param label 诊断用名称，如“设置项类”。
     * @param describe 候选的可读描述，如 [signature]。
     */
    fun <T> pickUnique(
        label: String,
        candidates: Iterable<T>,
        describe: (T) -> String,
        predicate: (T) -> Boolean,
    ): Pick<T> = pickPreferred(label, candidates, listOf(Shape("命中", predicate)), describe)

    /**
     * 按 [shapes] 顺序择优：返回第一个唯一命中的形状。
     *
     * 某个形状命中多个候选时**立即**判为歧义（绝不退回更弱的形状去凑一个结果），
     * 只有 0 命中才继续尝试下一个形状；全部落空时汇总每个形状的失败原因。
     */
    fun <T> pickPreferred(
        label: String,
        candidates: Iterable<T>,
        shapes: List<Shape<T>>,
        describe: (T) -> String,
    ): Pick<T> {
        val all = candidates.toList()
        val failures = mutableListOf<String>()
        for (shape in shapes) {
            val hits = all.filter(shape.matches)
            when (hits.size) {
                1 -> return Pick.Found(hits[0])
                0 -> failures += "${shape.label}: 0 个"
                else -> return Pick.Rejected(
                    "$label 命中不唯一(${shape.label}: ${hits.size} 个 -> " +
                        "${hits.joinToString(" | ", limit = 4, transform = describe)})",
                )
            }
        }
        return Pick.Rejected("$label 无法唯一确定(共扫描 ${all.size} 个候选): ${failures.joinToString("; ")}")
    }

    /** 按索引逐个比对前缀，零拷贝、零装箱。 */
    private fun prefixMatches(actual: Array<Class<*>>, parameterTypes: List<Class<*>>): Boolean {
        if (actual.size < parameterTypes.size) return false
        for (index in parameterTypes.indices) {
            if (actual[index] != parameterTypes[index]) return false
        }
        return true
    }

    /** `Executable` 本身没有返回值，只有 [Method] 有；构造器按 JVM 约定视作 `void`。 */
    private fun returnTypeOf(executable: Executable): Class<*> =
        (executable as? Method)?.returnType ?: Void.TYPE
}
