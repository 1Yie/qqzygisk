package com.qm.qqzygisk.hook.app.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 图片目录授权失效，或 SAF 文档无法读写。
 *
 * 调用方应把 [message] 直接展示给用户，并引导其重新选择授权目录；
 * 绝不能捕获后静默返回空列表或退回旧路径。
 */
internal class ImageAccessException(message: String) : IllegalStateException(message)

private val DOCUMENT_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_FLAGS,
)

internal class DocumentGrants(val read: Boolean, val write: Boolean)

private const val REVOKED_MESSAGE = "目录授权已失效，请重新选择授权目录"

/**
 * 一个真实文件或 SAF 文档的统一句柄。
 *
 * [key] 是持久身份：真实文件为绝对路径，SAF 文档为完整 document URI。
 * 发送缓存等临时文件路径永远不会出现在 [key] 中。
 */
internal sealed class ImageEntry {
    abstract val key: String
    abstract val name: String
    abstract val parentKey: String?

    abstract val localFile: File?
    abstract val documentUri: Uri?

    abstract val isDirectory: Boolean
    abstract val isFile: Boolean

    /** SAF 同时考虑持久写授权与 provider 能力；真实文件为 `File.canWrite()`。 */
    abstract val canWrite: Boolean
    abstract val canDelete: Boolean

    /** 未知时为 -1。 */
    abstract val size: Long

    /** 未知时为 0。 */
    abstract val modified: Long

    abstract val mimeType: String?

    abstract fun exists(): Boolean

    /** 一次 query 取回全部子项名称/类型/大小/时间/权限。 */
    abstract fun children(): List<ImageEntry>

    abstract fun findChild(name: String): ImageEntry?

    abstract fun createDirectory(name: String): ImageEntry

    abstract fun createFile(name: String, mimeType: String): ImageEntry

    abstract fun inputStream(): InputStream

    abstract fun outputStream(): OutputStream

    /** 失败必须返回 false；权限失效抛 [ImageAccessException]。 */
    abstract fun delete(): Boolean

    final override fun equals(other: Any?): Boolean =
        other is ImageEntry && other.javaClass == javaClass && other.key == key

    final override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "ImageEntry($key)"

    companion object {
        fun isDocumentSource(source: String): Boolean =
            source.trim().startsWith("content:", ignoreCase = true)

        /**
         * 支持配置的 tree URI、历史里的 document URI 和绝对路径。
         *
         * 文档来源没有持久读授权时立即抛 [ImageAccessException]：即使进程内还有临时授权，
         * 也不允许悄悄读写，避免面板假装授权仍然有效。
         * 真实文件来源不接触 Android Context，便于 JVM 测试。
         */
        fun from(
            source: String,
            context: Context? = null,
            parentKey: String? = null,
        ): ImageEntry {
            val value = source.trim()
            require(value.isNotEmpty()) { "图片目录不能为空" }
            if (!isDocumentSource(value)) return fromFile(File(value))
            val host = context
                ?: requireNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
            return openDocument(host, value, parentKey)
        }

        fun fromFile(file: File): ImageEntry = FileImageEntry(file.absoluteFile)

        /** 用于历史等虚拟分类；不能创建、删除或枚举真实文档。 */
        fun virtual(key: String, name: String): ImageEntry = VirtualImageEntry(key, name)

        fun hasPersistedRead(context: Context, source: String): Boolean {
            if (!isDocumentSource(source)) return true
            return runCatching { grantsFor(context, Uri.parse(source.trim())).read }.getOrDefault(false)
        }

        fun hasPersistedWrite(context: Context, source: String): Boolean {
            if (!isDocumentSource(source)) return File(source.trim()).canWrite()
            return runCatching { grantsFor(context, Uri.parse(source.trim())).write }.getOrDefault(false)
        }

        private fun openDocument(context: Context, source: String, parentKey: String?): ImageEntry {
            val parsed = try {
                Uri.parse(source)
            } catch (error: Exception) {
                throw IllegalArgumentException("不是有效的图片目录：$source", error)
            }
            require(parsed.scheme.equals("content", true)) { "不支持的图片目录：$source" }
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
            val documentId = if (treeId != null) {
                runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull() ?: treeId
            } else {
                runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
                    ?: throw IllegalArgumentException("不是有效的文档 URI：$source")
            }
            val canonical = if (treeId != null) {
                DocumentsContract.buildDocumentUriUsingTree(parsed, documentId)
            } else {
                parsed
            }
            val grants = grantsFor(context, parsed)
            if (!grants.read) throw revoked()
            val state = queryState(context.contentResolver, canonical, source)
            val appContext = context.applicationContext ?: context
            return DocumentImageEntry(
                context = appContext,
                documentUri = canonical,
                documentId = documentId,
                treeBacked = treeId != null,
                parentKey = parentKey,
                state = state,
                grants = grants,
            )
        }

        private fun revoked() = ImageAccessException(REVOKED_MESSAGE)

        /** 新建文档后复用本次操作的授权快照，避免再扫描一次持久授权。 */
        internal fun createdDocument(
            context: Context,
            uri: Uri,
            parentKey: String?,
            grants: DocumentGrants,
        ): ImageEntry {
            val documentId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (error: IllegalArgumentException) {
                throw IllegalStateException("无法解析新建文档：$uri", error)
            }
            val state = queryState(context.contentResolver, uri, uri.toString())
            return DocumentImageEntry(
                context = context,
                documentUri = uri,
                documentId = documentId,
                treeBacked = true,
                parentKey = parentKey,
                state = state,
                grants = grants,
            )
        }

        private fun queryState(
            resolver: ContentResolver,
            uri: Uri,
            source: String,
        ): DocumentState {
            try {
                resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return DocumentState.missing()
                    return DocumentState(
                        exists = true,
                        name = cursor.getString(1) ?: source.substringAfterLast('/'),
                        mimeType = cursor.getString(2),
                        size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                        modified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                        flags = if (cursor.isNull(5)) 0 else cursor.getInt(5),
                    )
                }
            } catch (_: SecurityException) {
                throw revoked()
            } catch (error: IllegalArgumentException) {
                throw IllegalStateException("无法读取图片目录：$source", error)
            }
            return DocumentState.missing()
        }

        internal fun grantsFor(context: Context, uri: Uri): DocumentGrants {
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            var read = false
            var write = false
            for (permission in context.contentResolver.persistedUriPermissions) {
                val sameAuthority = permission.uri.authority == uri.authority
                val sameTree = sameAuthority && treeId != null &&
                    runCatching {
                        DocumentsContract.getTreeDocumentId(permission.uri) == treeId
                    }.getOrDefault(false)
                // 文档 ID 只在 provider 内唯一，必须同时比较 authority。
                if (!(permission.uri == uri || sameTree)) continue
                if (permission.isReadPermission) read = true
                if (permission.isWritePermission) write = true
            }
            return DocumentGrants(read, write)
        }

        internal fun mimeTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> null
        }
    }
}

private class FileImageEntry(val file: File) : ImageEntry() {
    override val key: String = file.absolutePath
    override val name: String = file.name
    override val parentKey: String? = file.parentFile?.absolutePath
    override val localFile: File = file
    override val documentUri: Uri? = null
    override val isDirectory: Boolean = file.isDirectory
    override val isFile: Boolean = file.isFile
    override val canWrite: Boolean = file.canWrite()
    override val canDelete: Boolean = file.parentFile?.canWrite() == true && file.exists()
    override val size: Long = if (file.isFile) file.length() else -1L
    override val modified: Long = if (file.exists()) file.lastModified() else 0L
    override val mimeType: String? = ImageEntry.mimeTypeOf(file.name)

    override fun exists(): Boolean = file.exists()

    override fun children(): List<ImageEntry> =
        file.listFiles()
            ?.map { ImageEntry.fromFile(it) }
            .orEmpty()

    override fun findChild(name: String): ImageEntry? =
        file.listFiles()?.firstOrNull { it.name == name }?.let { ImageEntry.fromFile(it) }

    override fun createDirectory(name: String): ImageEntry =
        ImageEntry.fromFile(ImageDirectoryAccess.createFolder(file, name))

    override fun createFile(name: String, mimeType: String): ImageEntry {
        ImageDirectoryAccess.ensureDirectory(file)
        val created = File(file, name)
        try {
            if (!created.createNewFile()) throw IOException("文件已存在：$name")
        } catch (error: IOException) {
            throw IOException("无法创建文件：$name\n原因：${error.message}", error)
        }
        return ImageEntry.fromFile(created)
    }

    override fun inputStream(): InputStream = FileInputStream(file)

    override fun outputStream(): OutputStream = FileOutputStream(file)

    override fun delete(): Boolean = file.delete()
}

private class DocumentImageEntry(
    private val context: Context,
    override val documentUri: Uri,
    private val documentId: String,
    private val treeBacked: Boolean,
    override val parentKey: String?,
    private val state: DocumentState,
    private val grants: DocumentGrants,
) : ImageEntry() {
    private val resolver: ContentResolver = context.contentResolver
    override val key: String = documentUri.toString()
    override val name: String = state.name
    override val localFile: File? = null
    override val isDirectory: Boolean = state.exists && state.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    override val isFile: Boolean = state.exists && state.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
    override val size: Long = if (isFile) state.size else -1L
    override val modified: Long = state.modified
    override val mimeType: String? = state.mimeType
    override val canWrite: Boolean = grants.write && state.exists && (
        if (isDirectory) {
            state.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        } else {
            state.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
        }
        )
    override val canDelete: Boolean = grants.write && state.exists &&
        state.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0

    override fun exists(): Boolean {
        if (!state.exists) return false
        // 旧句柄可能持有已失效的权限快照；每次操作都重新读取当前持久授权。
        if (!ImageEntry.grantsFor(context, documentUri).read) {
            throw ImageAccessException(REVOKED_MESSAGE)
        }
        return try {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        } catch (error: SecurityException) {
            throw ImageAccessException(REVOKED_MESSAGE)
        } catch (_: Exception) {
            // provider 可能用 FileNotFoundException / ParcelableException / IAE 表示文档已不存在。
            false
        }
    }

    override fun children(): List<ImageEntry> {
        if (!treeBacked || !state.exists || !isDirectory) return emptyList()
        // 树授权可能在面板打开后被清除；即使进程内还有临时授权，也不允许继续枚举。
        val fresh = ImageEntry.grantsFor(context, documentUri)
        if (!fresh.read) throw ImageAccessException(REVOKED_MESSAGE)
        val childUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, documentId)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("无法枚举目录：$key", error)
        }
        val result = mutableListOf<ImageEntry>()
        try {
            resolver.query(childUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val childDocumentUri = DocumentsContract.buildDocumentUriUsingTree(documentUri, childId)
                    val childName = cursor.getString(1) ?: childId.substringAfterLast('/')
                    val childMime = cursor.getString(2)
                    val childState = DocumentState(
                        exists = true,
                        name = childName,
                        mimeType = childMime,
                        size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                        modified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                        flags = if (cursor.isNull(5)) 0 else cursor.getInt(5),
                    )
                    result += DocumentImageEntry(
                        context = context,
                        documentUri = childDocumentUri,
                        documentId = childId,
                        treeBacked = true,
                        parentKey = key,
                        state = childState,
                        grants = fresh,
                    )
                }
            }
        } catch (_: SecurityException) {
            throw ImageAccessException(REVOKED_MESSAGE)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("无法枚举目录：$key", error)
        }
        return result
    }

    override fun findChild(name: String): ImageEntry? = children().firstOrNull { it.name == name }

    override fun createDirectory(name: String): ImageEntry =
        create(name, DocumentsContract.Document.MIME_TYPE_DIR)

    override fun createFile(name: String, mimeType: String): ImageEntry = create(name, mimeType)

    private fun create(name: String, mimeType: String): ImageEntry {
        // 旧句柄的权限快照可能已过期：创建前重新读取当前持久授权与 provider 能力。
        val fresh = ImageEntry.grantsFor(context, documentUri)
        if (!fresh.read) throw ImageAccessException(REVOKED_MESSAGE)
        if (!fresh.write) throw ImageAccessException("目录授权只读，请重新授权可写目录")
        if (!isDirectory || state.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw ImageAccessException("目标目录不允许新建内容")
        }
        val created = try {
            DocumentsContract.createDocument(resolver, documentUri, mimeType, name)
        } catch (_: SecurityException) {
            throw ImageAccessException(REVOKED_MESSAGE)
        } ?: throw IOException("无法创建：$name")
        val createdId = try {
            DocumentsContract.getDocumentId(created)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("无法解析新建文档：$name", error)
        }
        // provider 可能返回不带 tree 段的 document URI；统一转成 tree 作用域 URI，
        // 否则之后无法枚举该目录的子项。
        val canonicalCreated = DocumentsContract.buildDocumentUriUsingTree(documentUri, createdId)
        // 重新读取 provider 的真实 flags：删除/写入能力必须以 provider 声明为准，
        // 不能根据“刚创建成功”推断（例如目录可能不支持删除）。
        return ImageEntry.createdDocument(context, canonicalCreated, key, fresh)
    }

    override fun inputStream(): InputStream {
        if (!ImageEntry.grantsFor(context, documentUri).read) throw ImageAccessException(REVOKED_MESSAGE)
        return try {
            resolver.openInputStream(documentUri)
                ?: throw FileNotFoundException("无法读取图片：$name")
        } catch (_: SecurityException) {
            throw ImageAccessException(REVOKED_MESSAGE)
        }
    }

    override fun outputStream(): OutputStream {
        val fresh = ImageEntry.grantsFor(context, documentUri)
        if (!fresh.read) throw ImageAccessException(REVOKED_MESSAGE)
        if (!fresh.write) throw ImageAccessException("目录授权只读，无法写入：$name")
        if (!isFile || state.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0) {
            throw ImageAccessException("目标文件不允许写入：$name")
        }
        return try {
            resolver.openOutputStream(documentUri, "wt")
                ?: throw IOException("无法写入图片：$name")
        } catch (_: SecurityException) {
            throw ImageAccessException(REVOKED_MESSAGE)
        }
    }

    override fun delete(): Boolean {
        val fresh = ImageEntry.grantsFor(context, documentUri)
        if (!fresh.read || !fresh.write) {
            throw ImageAccessException("目录只读或授权已失效，无法删除：$name")
        }
        // provider 未声明删除能力时绝不调用 deleteDocument。
        if (state.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE == 0) return false
        return try {
            DocumentsContract.deleteDocument(resolver, documentUri)
        } catch (_: SecurityException) {
            throw ImageAccessException("目录只读或授权已失效，无法删除：$name")
        } catch (_: Exception) {
            false
        }
    }
}

private class VirtualImageEntry(
    override val key: String,
    override val name: String,
) : ImageEntry() {
    override val parentKey: String? = null
    override val localFile: File? = null
    override val documentUri: Uri? = null
    override val isDirectory: Boolean = true
    override val isFile: Boolean = false
    override val canWrite: Boolean = false
    override val canDelete: Boolean = false
    override val size: Long = -1L
    override val modified: Long = 0L
    override val mimeType: String? = null

    override fun exists(): Boolean = true

    override fun children(): List<ImageEntry> = emptyList()

    override fun findChild(name: String): ImageEntry? = null

    override fun createDirectory(name: String): ImageEntry =
        throw ImageAccessException("历史分类不能创建目录")

    override fun createFile(name: String, mimeType: String): ImageEntry =
        throw ImageAccessException("历史分类不能写入文件")

    override fun inputStream(): InputStream = throw FileNotFoundException("历史分类不是真实目录")

    override fun outputStream(): OutputStream = throw FileNotFoundException("历史分类不是真实目录")

    override fun delete(): Boolean = false
}

private class DocumentState(
    val exists: Boolean,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val modified: Long,
    val flags: Int,
) {
    companion object {
        fun missing() = DocumentState(false, "", null, -1L, 0L, 0)
    }
}
