package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 只为真正需要 `File` 的宿主 native API 实质化 SAF 图片。
 *
 * 稳定文件按「来源身份 + 内容摘要」命名：同内容不同来源不会互相覆盖，
 * 也不会把发送缓存路径当成历史身份。稳定文件从不删除（native 读取可能异步），
 * 只有本次未完成的临时文件会在失败时清理。
 */
internal object ImageSendCache {
    private const val DIRECTORY = "qqzygisk/send-cache"

    private val sources = ConcurrentHashMap<String, SourceRef>()
    private val materialized = ConcurrentHashMap<String, Materialized>()

    private data class SourceRef(val key: String, val parentKey: String?)

    private data class Materialized(val file: File, val size: Long, val modified: Long)

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    /** 判断文件是否位于发送缓存目录；缓存路径不能作为历史/来源身份。 */
    fun isCacheFile(context: Context, file: File): Boolean =
        file.absolutePath.startsWith(directory(context).absolutePath + File.separator)

    /**
     * 返回一个真实文件：真实文件来源直接返回原文件，文档来源流式复制到宿主私有缓存。
     * 重复调用按内容去重，已有稳定文件不会被覆盖或删除。
     */
    fun fileFor(context: Context, entry: ImageEntry): File {
        entry.localFile?.let { return it }
        val directory = directory(context)
        ImageDirectoryAccess.ensureDirectory(directory)

        val sourceDigest = digestHex(entry.key.toByteArray(Charsets.UTF_8))
        // 文档可能在任何时刻被修改；命中内存备忘前必须重新向 provider 查询当前元数据。
        val current = currentEntry(context, entry)
        val extension = extensionOf(current)
        val cached = materialized[entry.key]
        if (
            cached != null &&
            current.size >= 0 &&
            current.modified > 0 &&
            cached.size == current.size &&
            cached.modified == current.modified &&
            cached.file.isFile
        ) {
            sources[cached.file.absolutePath] = SourceRef(entry.key, entry.parentKey)
            return cached.file
        }

        val temp = File.createTempFile("$sourceDigest-", ".part", directory)
        val stable: File = try {
            copyInto(current, temp, sourceDigest, extension, directory)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        } finally {
            if (temp.exists()) temp.delete()
        }

        if (current.size >= 0 && current.modified > 0) {
            materialized[entry.key] = Materialized(stable, current.size, current.modified)
        }
        sources[stable.absolutePath] = SourceRef(entry.key, entry.parentKey)
        return stable
    }

    /** 重新读取 SAF 文档的当前句柄；重新查询 provider，而不是相信旧快照。 */
    private fun currentEntry(context: Context, entry: ImageEntry): ImageEntry =
        ImageEntry.from(entry.key, context, entry.parentKey)

    /**
     * 把 native 回调只能看到的 File 路径还原成原始来源。
     *
     * 缓存路径没有映射时返回 null，调用方必须跳过历史记录，绝不能把缓存文件当来源。
     * 其他真实文件按本地文件来源返回。
     */
    fun sourceFor(context: Context, file: File): ImageEntry? {
        val ref = sources[file.absolutePath]
        if (ref != null) {
            return runCatching { ImageEntry.from(ref.key, context, ref.parentKey) }.getOrNull()
        }
        if (isCacheFile(context, file)) return null
        return if (file.isFile) ImageEntry.fromFile(file) else null
    }

    private fun copyInto(
        entry: ImageEntry,
        temp: File,
        sourceDigest: String,
        extension: String,
        directory: File,
    ): File {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        entry.inputStream().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    copied += read
                }
                output.flush()
            }
        }
        val stable = File(directory, "$sourceDigest-${hex(digest.digest())}.$extension")
        if (!stable.isFile || stable.length() != copied) moveInto(temp, stable)
        return stable
    }

    private fun moveInto(temp: File, stable: File) {
        try {
            Files.move(temp.toPath(), stable.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            if (stable.length() != temp.length()) {
                Files.move(temp.toPath(), stable.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), stable.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            if (!stable.isFile) throw error
        }
    }

    private fun extensionOf(entry: ImageEntry): String {
        val fromName = entry.name.substringAfterLast('.', "").lowercase()
        if (fromName in IMAGE_EXTENSIONS) return if (fromName == "jpeg") "jpg" else fromName
        return when (entry.mimeType?.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            else -> "img"
        }
    }

    private fun digestHex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
}
