package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FunBoxMigrationTest {
    @Test
    fun `按表情包名称复制图片并在再次迁移时跳过`() {
        val root = Files.createTempDirectory("funbox-migrate").toFile()
        val storage = File(root, "Sticker/Storage/pack-1")
        val dest = File(root, "qqzygisk")
        assertTrue(storage.mkdirs())
        File(root, "Sticker/packs").writeText(
            """{"list":[{"id":"pack-1","name":"猫猫"}]}""",
        )
        File(storage, "a.png").writeBytes(byteArrayOf(1, 2, 3))
        File(storage, "note.txt").writeText("skip")
        File(storage, "__cover__.png").writeBytes(byteArrayOf(9))
        File(storage, "nested").mkdirs()
        File(storage, "nested/b.webp").writeBytes(byteArrayOf(4, 5))

        val first = ImageFolderStore.migrateFunBoxToQqZygisk(storage.parent, dest.path)
        assertEquals(1, first.packs)
        assertEquals(2, first.copied)
        assertEquals(0, first.skipped)
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(dest, "猫猫/a.png").readBytes().toList())
        assertTrue(File(dest, "猫猫/nested/b.webp").isFile || File(dest, "猫猫/b.webp").isFile)
        assertFalse(File(dest, "猫猫/__cover__.png").exists())
        assertFalse(File(dest, "猫猫/note.txt").exists())
        assertTrue(File(storage, "a.png").isFile)

        val second = ImageFolderStore.migrateFunBoxToQqZygisk(storage.parent, dest.path)
        assertEquals(1, second.packs)
        assertEquals(0, second.copied)
        assertEquals(2, second.skipped)
    }

    @Test
    fun `源目录不存在时失败`() {
        val dest = Files.createTempDirectory("funbox-dest").toFile()
        val missing = File(dest, "missing")
        val error = runCatching {
            ImageFolderStore.migrateFunBoxToQqZygisk(missing.path, File(dest, "out").path)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("FunBox 目录不存在", error?.message)
    }
}
