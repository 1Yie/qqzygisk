package com.qm.qqzygisk.hook.app.hooker

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingEntryPlacementTest {
    @Test
    fun `入口跟随通用设置分组而不是固定下标或列表末尾`() {
        val general = Group(listOf(Item(ICON)))
        val other = Group(listOf(Item(OTHER_ICON)))
        assertEquals(2, SettingHooker.findInsertionIndex(listOf(other, other, general, other), Group::class.java, Item::class.java, ICON))
        assertEquals(0, SettingHooker.findInsertionIndex(listOf(general, other, other), Group::class.java, Item::class.java, ICON))
    }

    @Test
    fun `分组字段与图标字段改名及继承后仍定位同一语义锚点`() {
        val groups = listOf(
            RenamedGroup(listOf(RenamedItem(OTHER_ICON))),
            RenamedGroup(listOf(RenamedItem(ICON))),
            RenamedGroup(listOf(RenamedItem(OTHER_ICON))),
        )
        assertEquals(1, SettingHooker.findInsertionIndex(groups, RenamedGroup::class.java, RenamedItem::class.java, ICON))
    }

    @Test
    fun `没有通用设置锚点时置顶而不是追加到底部`() {
        val groups = listOf(Group(listOf(Item(OTHER_ICON))), Group(listOf(Item(OTHER_ICON))))
        assertEquals(0, SettingHooker.findInsertionIndex(groups, Group::class.java, Item::class.java, ICON))
    }

    @Test
    fun `未解析图标资源时不把默认零值当作锚点`() {
        val groups = listOf(Group(listOf(Item(OTHER_ICON))), Group(listOf(Item(0))))
        assertEquals(0, SettingHooker.findInsertionIndex(groups, Group::class.java, Item::class.java, 0))
    }

    @Test(expected = IllegalStateException::class)
    fun `多个分组命中相同图标时拒绝任意选取`() {
        val groups = listOf(Group(listOf(Item(ICON))), Group(listOf(Item(ICON))))
        SettingHooker.findInsertionIndex(groups, Group::class.java, Item::class.java, ICON)
    }

    @Test
    fun `忽略不属于设置项类型的同图标对象`() {
        val groups = listOf(
            Group(listOf(Decoration(ICON))),
            Group(listOf(Item(OTHER_ICON))),
            Group(listOf(Item(ICON))),
        )
        assertEquals(2, SettingHooker.findInsertionIndex(groups, Group::class.java, Item::class.java, ICON))
    }

    private class Item(private val h: Int)
    private class Group(private val a: List<Any>)
    private class Decoration(private val image: Int)
    private open class IconBase(private val changedIconName: Int)
    private class RenamedItem(icon: Int) : IconBase(icon)
    private class RenamedGroup(private val changedListName: List<Any>)

    private companion object {
        const val ICON = 0x7f080123
        const val OTHER_ICON = 0x7f080456
    }
}
