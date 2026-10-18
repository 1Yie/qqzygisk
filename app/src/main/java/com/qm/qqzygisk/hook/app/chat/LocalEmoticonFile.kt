package com.qm.qqzygisk.hook.app.chat

import java.io.File
import java.security.MessageDigest

internal enum class LocalEmoticonFormat { GIF, PNG, JPEG, WEBP }

internal data class LocalEmoticonFile(
    val file: File,
    val md5: String,
    val size: Long,
    val format: LocalEmoticonFormat,
) {
    companion object {
        fun inspect(file: File): LocalEmoticonFile {
            require(file.isFile) { "表情文件不可用" }
            val digest = MessageDigest.getInstance("MD5")
            val header = ByteArray(32)
            var headerSize = 0
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    val prefix = minOf(header.size - headerSize, count)
                    if (prefix > 0) {
                        buffer.copyInto(header, headerSize, 0, prefix)
                        headerSize += prefix
                    }
                    size += count
                }
            }
            val bytes = header.copyOf(headerSize)
            val format = when {
                bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a") -> LocalEmoticonFormat.GIF
                bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> LocalEmoticonFormat.PNG
                bytes.size >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() -> LocalEmoticonFormat.JPEG
                bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> LocalEmoticonFormat.WEBP
                else -> error("不支持的表情文件格式")
            }
            val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return LocalEmoticonFile(file, md5, size, format)
        }
    }
}
