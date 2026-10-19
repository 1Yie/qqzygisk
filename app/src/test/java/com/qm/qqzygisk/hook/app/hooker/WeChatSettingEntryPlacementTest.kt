package com.qm.qqzygisk.hook.app.hooker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WeChatSettingEntryPlacementTest {
    private class Item(val name: String)

    @Test
    fun `both page builds retain the entry and all original settings`() {
        val original = listOf(
            Item("SettingAdditionHeaderSearch"),
            Item("SettingGroupPersonalInfo"),
            Item("SettingGroupAccountInfo"),
            Item("SettingGroupGeneral"),
        )
        val entry = Item("QQ Zygisk")
        repeat(2) {
            val result = insertWeChatSettingEntry(original.toList(), entry) { it.name }
            assertEquals(
                listOf("SettingAdditionHeaderSearch", "SettingGroupPersonalInfo", "QQ Zygisk", "SettingGroupAccountInfo", "SettingGroupGeneral"),
                result.map { it.name },
            )
            assertEquals(original, result.filter { it !== entry })
        }
        assertEquals(4, original.size)
    }

    @Test
    fun `rebuilding an injected list does not duplicate the entry`() {
        val entry = Item("QQ Zygisk")
        val original = listOf(Item("SettingGroupPersonalInfo"), entry, Item("SettingGroupAccountInfo"))
        assertSame(original, insertWeChatSettingEntry(original, entry) { it.name })
    }

    @Test
    fun `missing anchor appends without removing original settings`() {
        val original = listOf(Item("first"), Item("second"))
        val entry = Item("QQ Zygisk")
        val result = insertWeChatSettingEntry(original, entry) { it.name }
        assertEquals(original + entry, result)
    }
}
