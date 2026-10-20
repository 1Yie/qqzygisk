package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import com.qm.qqzygisk.hook.app.host.HostRuntime
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings

/**
 * 表情来源目录的 SAF 目录授权。
 *
 * 三个来源配置字段继续保存原来的设置键：值既可以是绝对路径，也可以是
 * `ACTION_OPEN_DOCUMENT_TREE` 返回的 content tree URI。这里只负责选择、持久化
 * 授权、状态判断和清理，不读写来源配置本身；配置写入仍由设置页在用户明确保存时完成。
 *
 * 这里不依赖宿主实现，[isHostAvailable] 只用来在设置页里提示“需要从宿主打开”；
 * 授权本身作用于调用进程的 UID，因此同一份代码也可以被真实设备上的授权测试复用。
 */
object ImageDirectoryGrants {

    private const val CONTENT_SCHEME = "content"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private val PRIMARY_STORAGE_ROOTS = listOf(
        "/storage/emulated/0",
        "/storage/self/primary",
        "/sdcard",
    )
    private val PRIMARY_STORAGE_PREFIXES = listOf(
        "/storage/emulated/0/",
        "/storage/self/primary/",
        "/sdcard/",
    )

    @Volatile
    private var applicationContextCache: Context? = null

    private const val TAKE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 模块自己申请的临时授权记录；与来源设置分开保存，不是第二套目录配置。 */
    private const val GRANT_PREFERENCES_NAME = "qqzygisk_directory_grants"
    private const val PROVISIONAL_KEY = "provisional_tree_uris"

    /** 选择器返回的目录和系统实际给出的授权 flags，flags 不能被简化掉。 */
    data class PickedTree(val uri: Uri, val flags: Int)

    /**
     * 自定义目录选择 Contract。
     *
     * `ActivityResultContracts.OpenDocumentTree` 只回传 [Uri]，会丢失系统实际返回的
     * read/write flags，无法据此只申请真正拿到的权限。这里保留完整 [Intent.flags]。
     */
    class OpenDirectoryTreeContract : ActivityResultContract<Uri?, PickedTree?>() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                // 隐式 Intent，不指定 component，由系统 DocumentsUI 处理。
                input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }

        override fun parseResult(resultCode: Int, intent: Intent?): PickedTree? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val uri = intent.data ?: return null
            return PickedTree(uri = uri, flags = intent.flags)
        }
    }

    /** [takePersistedGrant] 的结果。失败时 [error] 是可直接展示给用户的中文原因。 */
    data class GrantOutcome(
        val uri: Uri,
        val read: Boolean,
        val write: Boolean,
        val error: String? = null,
        /** 本次调用是否新取得授权；之前已存在的授权不会进入模块临时授权记录。 */
        val newlyAcquired: Boolean = false,
    ) {
        val granted: Boolean get() = error == null && read
        val uriString: String get() = uri.toString()
    }

    enum class GrantState {
        /** 当前是普通本地路径，不需要 SAF 授权。 */
        PATH,

        /** 有持久读写授权。 */
        GRANTED,

        /** 有持久读取授权，但不能写入；可以浏览和发送。 */
        READ_ONLY,

        /** 曾经授权过，但持久授权已失效，必须重新选择，不能回退到其他路径。 */
        NEEDS_REAUTHORIZATION,

        /** 配置里的 content 地址不是有效的目录树授权。 */
        INVALID,
    }

    data class SourceStatus(
        val state: GrantState,
        val uri: Uri? = null,
        val message: String = "",
    ) {
        val usesDocument: Boolean get() = uri != null
        val needsReauthorization: Boolean
            get() = state == GrantState.NEEDS_REAUTHORIZATION || state == GrantState.INVALID
        val readOnly: Boolean get() = state == GrantState.READ_ONLY
    }

    /** 当前宿主是否安装了模块入口；standalone 模块应用里为 false。 */
    fun isHostAvailable(): Boolean = runCatching { HostRuntime.current }.isSuccess

    /** 宿主包名；standalone 模块应用里为 null。 */
    fun hostPackageName(): String? =
        runCatching { HostRuntime.current.packageName }.getOrNull()

    /**
     * 宿主 app context。
     *
     * 代理的 SettingActivity 属于宿主进程，ContentResolver 和 SharedPreferences 必须来自宿主，
     * 不能用模块资源 Context 推断包归属。
     */
    fun hostContext(context: Context): Context {
        applicationContextCache?.let { return it }
        val hostApplication = runCatching { AppParasitics.appContext }.getOrNull()
        val resolved = hostApplication
            ?: context.findActivity()?.applicationContext
            ?: context.applicationContext
            ?: context
        applicationContextCache = resolved
        return resolved
    }

    fun resolver(context: Context): ContentResolver = hostContext(context).contentResolver

    fun isContentSource(source: String): Boolean =
        source.trim().startsWith("$CONTENT_SCHEME://", ignoreCase = true)

    /**
     * 用当前输入框内容给目录选择器一个初始位置，仅用于 `EXTRA_INITIAL_URI`。
     *
     * content tree URI 原样返回；常见主存储绝对路径映射到 ExternalStorageProvider
     * 的 `primary:` document URI，方便直接定位隐藏目录（如 QQ 的 .qqzygisk）。
     * 这里不申请授权、不访问文件；其他路径返回 null。
     */
    fun initialTreeUriFor(source: String): Uri? {
        val trimmed = source.trim()
        if (isContentSource(trimmed)) {
            val uri = Uri.parse(trimmed)
            return if (runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) uri else null
        }
        val relative = primaryStorageRelativePath(trimmed) ?: return null
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$relative")
    }

    private fun primaryStorageRelativePath(path: String): String? {
        if (path in PRIMARY_STORAGE_ROOTS) return ""
        val prefix = PRIMARY_STORAGE_PREFIXES.firstOrNull { path.startsWith(it) } ?: return null
        return path.removePrefix(prefix).trim('/')
    }

    /**
     * 申请持久授权。
     *
     * 只使用系统实际返回的 read/write flags，至少要有 read；必须是 content tree，
     * 且系统实际返回了可持久化 flag。取消选择时调用方不应调用本方法。
     */
    fun takePersistedGrant(context: Context, picked: PickedTree): GrantOutcome {
        val uri = picked.uri
        val isDirectoryTree = uri.scheme?.equals(CONTENT_SCHEME, ignoreCase = true) == true &&
            runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        if (!isDirectoryTree) {
            return GrantOutcome(
                uri = uri,
                read = false,
                write = false,
                error = "只能授权目录，不能把单个文件当作表情目录",
            )
        }
        if (picked.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            return GrantOutcome(
                uri = uri,
                read = false,
                write = false,
                error = "系统没有返回可持久化的目录授权，请重新选择",
            )
        }
        val received = picked.flags and TAKE_FLAGS
        if (received and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return GrantOutcome(
                uri = uri,
                read = false,
                write = false,
                error = "系统没有返回该目录的读取授权，请重新选择",
            )
        }
        val contentResolver = resolver(context)
        val alreadyPersisted = runCatching {
            contentResolver.persistedUriPermissions.any { sameTree(it.uri, uri) && it.isReadPermission }
        }.getOrDefault(false)
        return try {
            contentResolver.takePersistableUriPermission(uri, received)
            val permission = runCatching {
                contentResolver.persistedUriPermissions.firstOrNull {
                    sameTree(it.uri, uri) && it.isReadPermission
                }
            }.getOrNull() ?: return GrantOutcome(
                uri = uri,
                read = false,
                write = false,
                error = "目录授权未能保存，请重新选择",
            )
            if (!alreadyPersisted) addProvisional(context, uri)
            GrantOutcome(
                uri = uri,
                read = true,
                write = permission.isWritePermission,
                error = null,
                newlyAcquired = !alreadyPersisted,
            )
        } catch (error: SecurityException) {
            GrantOutcome(
                uri = uri,
                read = false,
                write = false,
                error = "该目录不支持持久授权，请换一个目录（${error.message ?: "未知原因"}）",
            )
        } catch (error: IllegalArgumentException) {
            GrantOutcome(
                uri = uri,
                read = false,
                write = false,
                error = "目录授权信息无效，请重新选择目录（${error.message ?: "未知原因"}）",
            )
        }
    }

    /** 判断配置值当前的授权状态。普通路径永远不需要授权。 */
    fun status(context: Context, source: String): SourceStatus {
        val trimmed = source.trim()
        if (!isContentSource(trimmed)) {
            return SourceStatus(GrantState.PATH, message = "本地路径，不使用目录授权")
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme?.equals(CONTENT_SCHEME, ignoreCase = true) != true) {
            return SourceStatus(GrantState.INVALID, uri, "目录地址无法识别")
        }
        val isTree = runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        if (!isTree) {
            return SourceStatus(
                GrantState.INVALID,
                uri,
                "该地址不是目录授权，请重新选择目录",
            )
        }
        val permissions = runCatching { resolver(context).persistedUriPermissions }
            .getOrElse { return SourceStatus(GrantState.INVALID, uri, "无法读取目录授权状态") }
        val read = permissions.firstOrNull { sameTree(it.uri, uri) && it.isReadPermission }
            ?: return SourceStatus(
                GrantState.NEEDS_REAUTHORIZATION,
                uri,
                "目录授权已失效，请重新选择目录授权",
            )
        return if (read.isWritePermission) {
            SourceStatus(GrantState.GRANTED, uri, "已取得读写授权")
        } else {
            SourceStatus(
                GrantState.READ_ONLY,
                uri,
                "已授权：只读，可浏览和发送，不能保存或删除",
            )
        }
    }

    fun hasPersistedRead(context: Context, source: String): Boolean =
        status(context, source).let { it.state == GrantState.GRANTED || it.state == GrantState.READ_ONLY }

    /**
     * 模块取得过、仍在授权记录里的目录树 URI（包括已被来源配置采用的目录）。
     * 用于在替换/清除/页面放弃时判断哪些持久授权属于本功能。
     */
    fun provisionalGrants(context: Context): List<Uri> =
        readProvisional(context).mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * 释放模块自己取得过、但已不再被任何来源配置引用的目录授权。
     *
     * 只处理模块自己的授权记录，绝不清扫宿主全部 persistedUriPermissions：
     * 宿主可能还有与本功能无关的授权。仍被 [keepSources] 采用的记录会保留，
     * 以便以后被替换/清除时仍然可以判断归属；已经失效的记录直接移除。
     *
     * @return 实际释放的 URI。
     */
    fun releaseProvisional(context: Context, keepSources: Collection<String>): List<Uri> {
        val entries = readProvisional(context)
        if (entries.isEmpty()) return emptyList()
        val contentResolver = resolver(context)
        val keep = keepSources.mapNotNull(::treeUriOrNull)
        val permissions = runCatching { contentResolver.persistedUriPermissions }
            .getOrElse { return emptyList() }
        val released = mutableListOf<Uri>()
        val remaining = mutableListOf<String>()
        entries.forEach { entry ->
            val uri = runCatching { Uri.parse(entry) }.getOrNull()
            if (uri == null) return@forEach
            // 授权已被其他途径撤销：从记录里移除即可，没有可释放的权限。
            val permission = permissions.firstOrNull { sameTree(it.uri, uri) } ?: return@forEach
            // 仍被任何来源配置引用：保留在记录里，以后被替换/清除时仍可判断归属。
            if (keep.any { sameTree(it, uri) }) {
                remaining += entry
                return@forEach
            }
            val flags =
                (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags == 0) return@forEach
            runCatching { contentResolver.releasePersistableUriPermission(permission.uri, flags) }
                .onSuccess { released += permission.uri }
                .onFailure { remaining += entry }
        }
        writeProvisional(context, remaining)
        return released
    }

    /**
     * 下次进入设置页时清理上次页面遗留的临时授权。
     *
     * [restoredFromState] 为 true（旋转或进程恢复）时不动，避免撤销刚选好、还没保存的目录；
     * 只有全新进入页面才会认为旧临时授权已被放弃。
     */
    fun cleanupAbandonedProvisional(context: Context, restoredFromState: Boolean) {
        if (restoredFromState) return
        releaseProvisional(context, configuredSources().values)
    }

    /** 当前宿主已保存的三个来源值（用于清理时判断授权是否仍被引用）。 */
    fun configuredSources(): Map<String, String> = linkedMapOf(
        ImageFolderStore.pathKey to
            HookSettings.getString(ImageFolderStore.pathKey, ImageFolderStore.defaultPath),
        ImageFolderStore.FUNBOX_PATH_KEY to
            HookSettings.getString(ImageFolderStore.FUNBOX_PATH_KEY, ImageFolderStore.defaultFunBoxPath),
        ImageFolderStore.TG_STICKERS_PATH_KEY to
            HookSettings.getString(ImageFolderStore.TG_STICKERS_PATH_KEY, ImageFolderStore.defaultTgStickersPath),
    )

    /**
     * 保存前校验：content 来源必须仍有持久读取授权。
     *
     * @return 设置键到错误消息的映射；为空表示全部有效。
     */
    fun validateSources(context: Context, sources: Map<String, String>): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for ((key, value) in sources) {
            val sourceStatus = status(context, value)
            if (sourceStatus.needsReauthorization) {
                errors[key] = sourceStatus.message.ifEmpty { "目录授权已失效，请重新选择目录授权" }
            }
        }
        return errors
    }

    /** 当前宿主的三个来源设置键：图片根、FunBox、TGStickersExported。 */
    fun sourceKeys(): List<String> = listOf(
        ImageFolderStore.pathKey,
        ImageFolderStore.FUNBOX_PATH_KEY,
        ImageFolderStore.TG_STICKERS_PATH_KEY,
    )

    private fun treeUriOrNull(source: String): Uri? {
        val trimmed = source.trim()
        if (!isContentSource(trimmed)) return null
        val uri = Uri.parse(trimmed)
        return if (runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) uri else null
    }

    private fun sameTree(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        if (first.toString().trimEnd('/') == second.toString().trimEnd('/')) return true
        // 系统可能把同一棵树的授权记录成带 document 后缀的 URI；与 ImageEntry 保持同样的树 ID 语义。
        if (first.authority != second.authority) return false
        val firstTree = runCatching { DocumentsContract.getTreeDocumentId(first) }.getOrNull()
        val secondTree = runCatching { DocumentsContract.getTreeDocumentId(second) }.getOrNull()
        return firstTree != null && secondTree != null && firstTree == secondTree
    }

    private fun grantPreferences(context: Context) =
        hostContext(context).getSharedPreferences(GRANT_PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun readProvisional(context: Context): List<String> =
        grantPreferences(context)
            .getString(PROVISIONAL_KEY, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun writeProvisional(context: Context, entries: List<String>) {
        grantPreferences(context).edit()
            .putString(PROVISIONAL_KEY, entries.distinct().joinToString("\n"))
            .commit()
    }

    private fun addProvisional(context: Context, uri: Uri) {
        val entries = readProvisional(context)
        if (entries.any { entry ->
                val existing = runCatching { Uri.parse(entry) }.getOrNull()
                existing != null && sameTree(existing, uri)
            }
        ) {
            return
        }
        writeProvisional(context, entries + uri.toString())
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
