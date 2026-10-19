package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImagePanelMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `writing usage never creates metadata in the image source`() {
        val privateFiles = temporary.newFolder("private-files")
        val images = temporary.newFolder("images")
        val metadata = ImagePanelMetadata(privateFiles, images)
        metadata.writeUsage("{\"files\":{},\"folders\":{}}")
        assertTrue(metadata.usageFile.canonicalFile.toPath().startsWith(privateFiles.canonicalFile.toPath()))
        assertTrue(metadata.usageFile.isFile)
        assertFalse(File(images, ".usage.json").exists())
        assertEquals("{\"files\":{},\"folders\":{}}", metadata.readUsage())
    }

    @Test
    fun `legacy history is read without writing to its old file`() {
        val privateFiles = temporary.newFolder("private-files")
        val images = temporary.newFolder("images")
        val old = File(images, ".usage.json").apply { writeText("legacy-history") }
        val metadata = ImagePanelMetadata(privateFiles, images)
        assertEquals("legacy-history", metadata.readUsage())
        metadata.writeUsage("updated-history")
        assertEquals("legacy-history", old.readText())
        assertEquals("updated-history", metadata.readUsage())
    }

    @Test
    fun `different image roots have separate persistent usage`() {
        val privateFiles = temporary.newFolder("private-files")
        val firstImages = temporary.newFolder("first")
        val secondImages = temporary.newFolder("second")
        val first = ImagePanelMetadata(privateFiles, firstImages)
        val second = ImagePanelMetadata(privateFiles, secondImages)
        first.writeUsage("first-history")
        second.writeUsage("second-history")
        assertNotEquals(first.usageFile, second.usageFile)
        assertEquals("first-history", ImagePanelMetadata(privateFiles, firstImages).readUsage())
        assertEquals("second-history", ImagePanelMetadata(privateFiles, secondImages).readUsage())
    }

    @Test
    fun `atomic replacement leaves no temporary metadata files`() {
        val privateFiles = temporary.newFolder("private-files")
        val metadata = ImagePanelMetadata(privateFiles, temporary.newFolder("images"))
        metadata.writeUsage("before")
        metadata.writeUsage("after")
        assertEquals("after", metadata.readUsage())
        assertEquals(listOf(metadata.usageFile), metadata.usageFile.parentFile!!.listFiles()!!.toList())
    }

    @Test
    fun `a metadata write failure cannot fail an already submitted send`() {
        val privateFiles = temporary.newFile("blocked-private-root")
        val images = temporary.newFolder("images")
        val old = File(images, ".usage.json").apply { writeText("keep") }
        val metadata = ImagePanelMetadata(privateFiles, images)
        var dismissed = false
        var reported: Throwable? = null
        val result = performImagePanelAction(
            send = { Result.success(Unit) },
            recordUsage = { metadata.writeUsage("new") },
            onUsageFailure = { reported = it },
        ).onSuccess { dismissed = true }
        assertTrue(result.isSuccess)
        assertTrue(dismissed)
        assertTrue(reported is IOException)
        assertEquals("keep", old.readText())
    }
}
