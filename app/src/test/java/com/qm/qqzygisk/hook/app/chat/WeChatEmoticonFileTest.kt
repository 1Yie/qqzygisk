package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class WeChatEmoticonFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `extensionless GIF keeps its original bytes and MD5`() {
        val bytes = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0) +
            byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0) +
            byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b)
        val file = temporary.newFile("a391b123").apply { writeBytes(bytes) }
        val emoji = WeChatEmoticonFile.inspect(file)
        assertEquals(WeChatEmoticonFormat.GIF, emoji.format)
        assertEquals(bytes.size.toLong(), emoji.size)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(md5, emoji.md5)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, emoji.file)
    }

    @Test
    fun `a GIF named png is identified from its header`() {
        val file = temporary.newFile("misnamed.png").apply { writeBytes("GIF87a".toByteArray() + ByteArray(30)) }
        assertEquals(WeChatEmoticonFormat.GIF, WeChatEmoticonFile.inspect(file).format)
    }

    @Test
    fun `WebP is distinct from JPEG and PNG`() {
        val webp = temporary.newFile("webp").apply { writeBytes("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(20)) }
        val jpeg = temporary.newFile("jpeg").apply { writeBytes(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 224.toByte()) + ByteArray(20)) }
        val png = temporary.newFile("png").apply { writeBytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + ByteArray(20)) }
        assertEquals(WeChatEmoticonFormat.WEBP, WeChatEmoticonFile.inspect(webp).format)
        assertEquals(WeChatEmoticonFormat.JPEG, WeChatEmoticonFile.inspect(jpeg).format)
        assertEquals(WeChatEmoticonFormat.PNG, WeChatEmoticonFile.inspect(png).format)
    }

    @Test
    fun `unsupported content is not inferred from a gif extension`() {
        val file = temporary.newFile("invalid.gif").apply { writeText("not an emoji") }
        assertThrows(IllegalStateException::class.java) { WeChatEmoticonFile.inspect(file) }
    }

    @Test
    fun `missing file cannot be imported`() {
        assertThrows(IllegalArgumentException::class.java) { WeChatEmoticonFile.inspect(File(temporary.root, "missing")) }
    }
}
