package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException

class ImageDirectoryAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `create folder creates a missing media root`() {
        val root = File(temporary.root, "media/com.tencent.mm/.qqzygisk")
        val folder = ImageDirectoryAccess.createFolder(root, "中文分类")
        assertTrue(folder.isDirectory)
        assertEquals(File(root, "中文分类"), folder)
    }

    @Test
    fun `existing folder is not overwritten`() {
        val root = temporary.newFolder("root")
        val folder = File(root, "1").apply { mkdir() }
        val image = File(folder, "image.png").apply { writeText("keep") }
        val error = assertThrows(IOException::class.java) { ImageDirectoryAccess.createFolder(root, "1") }
        assertTrue(error.cause is FileAlreadyExistsException)
        assertTrue(error.message!!.contains(folder.absolutePath))
        assertEquals("keep", image.readText())
    }

    @Test
    fun `a regular file used as root reports full path and underlying error`() {
        val root = temporary.newFile("not-a-directory")
        val error = assertThrows(IOException::class.java) { ImageDirectoryAccess.createFolder(root, "1") }
        assertTrue(error.cause is FileSystemException)
        assertTrue(error.message!!.contains(root.absolutePath))
        assertTrue(error.message!!.contains("原因："))
        assertTrue(root.isFile)
    }
}
