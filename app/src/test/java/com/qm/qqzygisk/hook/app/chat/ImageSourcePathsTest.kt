package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.host.HostType
import org.junit.Assert.*
import org.junit.Test

class ImageSourcePathsTest {
    @Test
    fun `Douyin exposes the same three independent sources`() {
        val defaults = HostImagePaths.defaults(HostType.DOUYIN)
        assertEquals(3, defaults.scanRoots.size)
        assertTrue(defaults.scanRoots.all { "/Android/media/com.ss.android.ugc.aweme/" in it })
        assertEquals(defaults, HostImagePaths.resolve(HostType.DOUYIN, "", "", ""))
        assertEquals(HostType.DOUYIN, HostType.fromPackageName("com.ss.android.ugc.aweme"))
        assertNotEquals(HostType.QQ.imagePathKey, HostType.DOUYIN.imagePathKey)
        assertNotEquals(HostType.WECHAT.imagePathKey, HostType.DOUYIN.imagePathKey)
        assertTrue(HostType.DOUYIN.enablePanelByDefault)
    }

    @Test
    fun `QQ and WeChat both expose image FunBox and TG sources`() {
        for (isWeChat in listOf(false, true)) {
            val paths = HostImagePaths.defaults(isWeChat)
            val host = if (isWeChat) "com.tencent.mm" else "com.tencent.mobileqq"
            assertEquals(listOf(paths.funBox, paths.tgStickers, paths.images), paths.scanRoots)
            assertEquals(3, paths.scanRoots.size)
            assertTrue(paths.scanRoots.all { "/Android/media/$host/" in it })
        }
    }

    @Test
    fun `existing QQ defaults stay unchanged`() {
        val paths = HostImagePaths.defaults(false)
        assertEquals("/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqzygisk", paths.images)
        assertEquals("/storage/self/primary/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage", paths.funBox)
        assertEquals("/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1", paths.tgStickers)
    }

    @Test
    fun `WeChat migrates previous accidental QQ default sources`() {
        val paths = HostImagePaths.resolve(true, "", HostImagePaths.QQ_FUNBOX, HostImagePaths.QQ_TG)
        assertEquals(HostImagePaths.defaults(true), paths)
        val emulatedAlias = HostImagePaths.QQ_FUNBOX.replace("/storage/self/primary/", "/storage/emulated/0/")
        assertEquals(HostImagePaths.WECHAT_FUNBOX, HostImagePaths.resolve(true, "", emulatedAlias, "").funBox)
    }

    @Test
    fun `custom paths stay editable in either host`() {
        for (isWeChat in listOf(false, true)) {
            val paths = HostImagePaths.resolve(isWeChat, " /sdcard/my images ", "/sdcard/box", "/sdcard/tg")
            assertEquals("/sdcard/my images", paths.images)
            assertEquals("/sdcard/box", paths.funBox)
            assertEquals("/sdcard/tg", paths.tgStickers)
        }
    }

    @Test
    fun `empty inputs restore all host defaults`() {
        assertEquals(HostImagePaths.defaults(true), HostImagePaths.resolve(true, " ", "", "\t"))
        assertEquals(HostImagePaths.defaults(false), HostImagePaths.resolve(false, " ", "", "\t"))
    }

    @Test
    fun `duplicate sources are scanned once`() {
        assertEquals(listOf("/same"), ImageSourcePaths("/same", "/same", "/same").scanRoots)
    }
}
