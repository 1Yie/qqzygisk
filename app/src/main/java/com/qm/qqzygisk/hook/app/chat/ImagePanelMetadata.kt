package com.qm.qqzygisk.hook.app.chat

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Panel bookkeeping stays in private storage, even when image sources are read-only. */
internal class ImagePanelMetadata(privateFiles: File, private val imageRoot: File) {
    private val directory = File(privateFiles, "qqzygisk/image-panel")
    private val sourceId = MessageDigest.getInstance("SHA-256")
        .digest(imageRoot.absolutePath.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    internal val usageFile = File(directory, "$sourceId.usage.json")

    fun readUsage(): String? {
        for (file in listOf(usageFile, File(imageRoot, ".usage.json"))) {
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
