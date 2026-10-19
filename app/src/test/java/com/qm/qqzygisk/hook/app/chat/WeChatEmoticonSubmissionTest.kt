package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WeChatEmoticonSubmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun gif(): File = temporary.newFile("extensionless-${System.nanoTime()}").apply {
        writeBytes("GIF89a".toByteArray() + ByteArray(100) { it.toByte() })
    }

    private class NativeApi : WeChatEmoticonApi {
        var importedBytes: ByteArray? = null
        var imports = 0
        var submissions = 0
        var sentTalker: String? = null
        var sentMd5: String? = null
        var transform: (ImportedWeChatEmoticon) -> ImportedWeChatEmoticon = { it }
        var accepted = true
        var importFailure: Throwable? = null
        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon {
            imports++
            importFailure?.let { throw it }
            importedBytes = file.file.readBytes()
            return transform(ImportedWeChatEmoticon(file.md5, file.size, if (file.format == WeChatEmoticonFormat.GIF) 2 else 1, true))
        }
        override fun submit(talker: String, md5: String): Boolean {
            submissions++
            sentTalker = talker
            sentMd5 = md5
            return accepted
        }
    }

    @Test
    fun `GIF is imported byte for byte then submitted as a native emoji MD5`() {
        val file = gif()
        val original = file.readBytes()
        val api = NativeApi()
        val result = WeChatEmoticonSubmission(api).send(file, "test-recipient")
        assertTrue(result.isSuccess)
        assertArrayEquals(original, api.importedBytes)
        assertArrayEquals(original, file.readBytes())
        assertEquals(1, api.imports)
        assertEquals(1, api.submissions)
        assertEquals("test-recipient", api.sentTalker)
        assertEquals(WeChatEmoticonFile.inspect(file).md5, api.sentMd5)
    }

    @Test
    fun `changed import bytes abort before native submission`() {
        val api = NativeApi().apply { transform = { it.copy(md5 = "00000000000000000000000000000000") } }
        assertTrue(WeChatEmoticonSubmission(api).send(gif(), "test-recipient").isFailure)
        assertEquals(0, api.submissions)
    }

    @Test
    fun `GIF incorrectly registered as a static image aborts`() {
        val api = NativeApi().apply { transform = { it.copy(nativeType = 1) } }
        assertTrue(WeChatEmoticonSubmission(api).send(gif(), "test-recipient").isFailure)
        assertEquals(0, api.submissions)
    }

    @Test
    fun `missing or incomplete native cache never sends`() {
        for (change in listOf<(ImportedWeChatEmoticon) -> ImportedWeChatEmoticon>(
            { it.copy(size = it.size - 1) }, { it.copy(readable = false) },
        )) {
            val api = NativeApi().apply { transform = change }
            assertTrue(WeChatEmoticonSubmission(api).send(gif(), "test-recipient").isFailure)
            assertEquals(0, api.submissions)
        }
    }

    @Test
    fun `native rejection returns failure without retrying another send type`() {
        val api = NativeApi().apply { accepted = false }
        assertTrue(WeChatEmoticonSubmission(api).send(gif(), "test-recipient").isFailure)
        assertEquals(1, api.submissions)
    }

    @Test
    fun `unavailable session never imports or sends`() {
        val api = NativeApi()
        assertTrue(WeChatEmoticonSubmission(api).send(gif(), null).isFailure)
        assertEquals(0, api.imports)
        assertEquals(0, api.submissions)
    }

    @Test
    fun `import exception follows the normal panel failure path`() {
        val error = IOException("native cache unavailable")
        val api = NativeApi().apply { importFailure = error }
        val result = WeChatEmoticonSubmission(api).send(gif(), "test-recipient")
        assertSame(error, result.exceptionOrNull())
        assertEquals(0, api.submissions)
    }

    @Test
    fun `WebP bytes are not converted to a bitmap before import`() {
        val bytes = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(30)
        val file = temporary.newFile("webp").apply { writeBytes(bytes) }
        val api = NativeApi()
        assertTrue(WeChatEmoticonSubmission(api).send(file, "test-recipient").isSuccess)
        assertArrayEquals(bytes, api.importedBytes)
        assertEquals(1, api.submissions)
    }
}
