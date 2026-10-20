package com.qm.qqzygisk.hook.app.chat

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 面板使用记录始终保存在宿主私有目录。
 *
 * 源身份可以是绝对路径或 SAF tree/document URI；真实文件来源继续使用绝对路径的
 * SHA-256，保证旧使用记录文件名逐字节兼容。旧 `<图片目录>/.usage.json` 只在真实文件
 * 来源下读取，绝不再写入所选目录。
 */
internal class ImagePanelMetadata(
    privateFiles: File,
    sourceIdentity: String,
    private val legacyImageRoot: File? = null,
) {
    private val directory = File(privateFiles, "qqzygisk/image-panel")
    private val sourceId = MessageDigest.getInstance("SHA-256")
        .digest(sourceIdentity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    internal val usageFile = File(directory, "$sourceId.usage.json")

    fun readUsage(): String? {
        val candidates = buildList {
            add(usageFile)
            legacyImageRoot?.let { add(File(it, ".usage.json")) }
        }
        for (file in candidates) {
            val text = runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    fun writeUsage(text: String) {
        ImageDirectoryAccess.ensureDirectory(directory)
        val pending = File.createTempFile(sourceId, ".tmp", directory)
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(pending.toPath(), usageFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), usageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            pending.delete()
        }
    }
}
