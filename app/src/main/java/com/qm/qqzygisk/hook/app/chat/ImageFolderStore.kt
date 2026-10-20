package com.qm.qqzygisk.hook.app.chat

import android.net.Uri
import android.provider.DocumentsContract
import com.qm.qqzygisk.hook.app.host.HostType
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天长按保存图片的本地目录。
 * 子文件夹会作为表情面板分组，第一张图默认当封面。
 * 浏览时还会带上模块内置贴纸目录。
 *
 * 三个来源都可能是绝对路径，也可能是 ACTION_OPEN_DOCUMENT_TREE 返回的 tree URI。
 * URI 不会经过 File/路径归一化；授权失效时明确抛 [ImageAccessException]，
 * 绝不静默退回旧路径或假装空目录。
 */
internal object ImageFolderStore {
    const val QQ_ZYGISK_PATH_KEY = "qq_zygisk_image_path"
    const val WECHAT_ZYGISK_PATH_KEY = "wx_zygisk_image_path"
    const val DOUYIN_ZYGISK_PATH_KEY = "douyin_zygisk_image_path"

    const val FUNBOX_PATH_KEY = "funbox_emoticon_path"
    const val TG_STICKERS_PATH_KEY = "tg_stickers_emoticon_path"

    /** 同名配置保存在各自宿主的 SharedPreferences 中，互不覆盖。 */
    const val DEFAULT_QQ_ZYGISK_PATH = HostImagePaths.QQ_IMAGES
    const val DEFAULT_WECHAT_ZYGISK_PATH = HostImagePaths.WECHAT_IMAGES
    const val DEFAULT_FUNBOX_PATH = HostImagePaths.QQ_FUNBOX
    const val DEFAULT_TG_STICKERS_PATH = HostImagePaths.QQ_TG

    internal val hostType: HostType
        get() = HostType.fromPackageName(runCatching { ModuleUtils.packageName }.getOrNull())

    val isWeChat: Boolean get() = hostType == HostType.WECHAT

    /** 当前宿主的图片目录配置项 / 默认值。 */
    val pathKey: String get() = hostType.imagePathKey

    val defaultPath: String get() = HostImagePaths.defaults(hostType).images
    val defaultFunBoxPath: String get() = HostImagePaths.defaults(hostType).funBox
    val defaultTgStickersPath: String get() = HostImagePaths.defaults(hostType).tgStickers

    internal val sourcePaths: ImageSourcePaths
        get() = HostImagePaths.resolve(
            hostType,
            HookSettings.getString(pathKey, defaultPath),
            HookSettings.getString(FUNBOX_PATH_KEY, defaultFunBoxPath),
            HookSettings.getString(TG_STICKERS_PATH_KEY, defaultTgStickersPath),
        )

    val ROOT_PATH: String get() = sourcePaths.images

    /** 所有宿主都扫描图片、FunBox、TG 三种来源，默认路径分别位于各自媒体目录。 */
    val SCAN_ROOTS: List<String> get() = sourcePaths.scanRoots

    private const val LAST_FOLDER_FILE = ".last_folder"
    private const val LAST_FOLDER_KEY = "emoticon_panel_last_folder"
    private const val HISTORY_KEY_SUFFIX = "#__history__"
    const val HISTORY_DIR_NAME = "__history__"
    const val HISTORY_LIMIT_KEY = "history_limit"
    const val DEFAULT_HISTORY_LIMIT = 80
    const val MIN_HISTORY_LIMIT = 1
    const val MAX_HISTORY_LIMIT = 500
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_DOCUMENT_PREFIX = "primary:"
    private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
    private val usageLock = Any()
    @Volatile
    private var usageCache: UsageStore? = null
    private var usageCacheRoot: String? = null
    private val namePattern = Regex("[\\/:*?\"<>|]")
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
    @Volatile
    private var funBoxNameCache = FunBoxNameCache()
    @Volatile
    private var funBoxEntryCache: Pair<String, ImageEntry?>? = null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /**
     * 配置的图片根。文档来源必须是仍持有持久读授权的真实目录；
     * 真实文件来源保持原来的「不存在就创建」行为。
     */
    fun root(): ImageEntry {
        val source = ROOT_PATH
        if (ImageEntry.isDocumentSource(source)) {
            val entry = ImageEntry.from(source)
            // from() 已经校验持久读授权并查询过文档状态：不能再 exists()，否则每次取根都会
            // 多扫一遍持久授权并多查一次 provider。状态表明不是有效目录时仍明确拒绝。
            if (!entry.isDirectory) throw ImageAccessException("图片目录不可用，请重新选择授权目录")
            return entry
        }
        val directory = File(source)
        runCatching { ImageDirectoryAccess.ensureDirectory(directory) }
            .onFailure { Log.error("表情根目录不可用: ${directory.absolutePath}", it) }
        return ImageEntry.fromFile(directory)
    }

    fun isOwned(folder: ImageEntry): Boolean {
        val root = root()
        val local = folder.localFile
        if (local != null) {
            val rootFile = root.localFile ?: return false
            val base = rootFile.absolutePath.trimEnd('/')
            val path = local.absolutePath
            return path == base || path.startsWith("$base/")
        }
        var current: ImageEntry? = folder
        var depth = 0
        while (current != null && depth < 64) {
            if (current.key == root.key) return true
            val parent = current.parentKey ?: return false
            current = runCatching { ImageEntry.from(parent) }.getOrNull()
            depth++
        }
        return false
    }

    fun historyFolder(): ImageEntry = historyFolder(root())

    private fun historyFolder(root: ImageEntry): ImageEntry {
        val key = root.localFile?.let { File(it, HISTORY_DIR_NAME).absolutePath }
            ?: (root.key + HISTORY_KEY_SUFFIX)
        return ImageEntry.virtual(key, HISTORY_DIR_NAME)
    }

    fun historyLimit(): Int =
        HookSettings.getInt(HISTORY_LIMIT_KEY, DEFAULT_HISTORY_LIMIT)
            .coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)

    fun isHistoryFolder(folder: ImageEntry): Boolean = folder.name == HISTORY_DIR_NAME

    private fun isHistoryKey(key: String): Boolean =
        key.endsWith(HISTORY_KEY_SUFFIX) || key.substringAfterLast('/') == HISTORY_DIR_NAME

    /**
     * 枚举分类快照，供面板/表情面板一次绑定首屏。
     *
     * 根只解析一次，且只枚举“分类发现/外部可读过滤”真正需要的目录：授权根自身与外部来源
     * 及其分组。自有子分类只保留根枚举得到的元数据，不提前 children()，避免把每个分类的
     * 全部文件压回首屏屏障；分类集合与顺序和旧的 folders() 完全一致。
     */
    fun scanFolders(includeExternal: Boolean = false): FolderScan {
        val root = root()
        val rootChildren = root.children()
        val imagesByFolder = LinkedHashMap<String, List<ImageEntry>>()
        val owned = mutableListOf<ImageEntry>()
        // 选中的目录本身也可能直接放图片（没有子分类）；必须作为不可删除的分类出现。
        // 它和分类共用同一批 children：一次判断可读性，一次取图片列表。
        if (rootChildren.any(::isReadableImage)) owned += root
        imagesByFolder[root.key] = sortedImages(rootChildren)
        rootChildren.filterTo(owned) {
            it.isDirectory && it.name != HISTORY_DIR_NAME && !it.name.startsWith(".")
        }
        val extra = if (includeExternal) {
            SCAN_ROOTS.asSequence()
                .filter { it != ROOT_PATH }
                .mapNotNull { externalRoot(it) }
                .flatMap { source ->
                    val children = source.children()
                    val result = mutableListOf<ImageEntry>()
                    if (children.any(::isReadableImage)) {
                        result += source
                        imagesByFolder[source.key] = sortedImages(children)
                    }
                    // 外部目录里没有可读图片的分组不展示：系统可能放行列目录、却拒绝读文件，
                    // 过滤掉这些分组，避免出现一排点进去全空、切换还卡顿的分类。
                    // 这里枚举到的 children 同时就是该分组的图片列表，不再二次枚举。
                    for (child in children) {
                        if (
                            !child.isDirectory ||
                            child.name == HISTORY_DIR_NAME ||
                            child.name.startsWith(".")
                        ) {
                            continue
                        }
                        val childEntries = child.children()
                        if (childEntries.any(::isReadableImage)) {
                            result += child
                            imagesByFolder[child.key] = sortedImages(childEntries)
                        }
                    }
                    result.asSequence()
                }
                .toList()
        } else {
            emptyList()
        }
        val real = (owned + extra)
            .distinctBy { it.key }
            .filter { !isHistoryFolder(it) }
            .sortedWith(
                compareByDescending<ImageEntry> { folderUsage(it) }
                    .thenBy { displayName(it).lowercase() },
            )
        if (isWeChat) {
            Log.info("微信目录扫描: root=$ROOT_PATH 自有分类=${owned.size} 外部分类=${extra.size}")
        }
        val folders = if (includeExternal) listOf(historyFolder(root)) + real else real
        return FolderScan(root = root, folders = folders, imagesByFolder = imagesByFolder)
    }

    /** 外部来源只在缺失时跳过；已配置的 URI 授权失效仍然明确抛出。 */
    private fun externalRoot(source: String): ImageEntry? {
        val entry = ImageEntry.from(source)
        return entry.takeIf { it.isDirectory }
    }

    fun images(folder: ImageEntry): List<ImageEntry> {
        if (isHistoryFolder(folder)) return historyImages()
        if (!folder.isDirectory) return emptyList()
        return sortedImages(folder.children())
    }

    private fun sortedImages(children: List<ImageEntry>): List<ImageEntry> =
        children
            .filter { it.isFile && isImageEntry(it) }
            .sortedByDescending { it.modified }

    fun coverFile(folder: ImageEntry): ImageEntry? {
        if (isHistoryFolder(folder)) return historyImages().firstOrNull()
        if (!folder.isDirectory) return null
        return folder.children()
            .asSequence()
            .filter { it.isFile && isImageEntry(it) }
            .minByOrNull { it.modified }
    }

    fun displayName(folder: ImageEntry): String {
        if (isHistoryFolder(folder)) return "历史"
        val funBox = funBoxEntry(sourcePaths.funBox)
        if (funBox == null || folder.parentKey != funBox.key) return folder.name
        return funBoxPackNames(funBox)[folder.name]?.takeIf { it.isNotBlank() } ?: folder.name
    }

    fun recordUsage(item: ImageEntry) {
        if (!item.isFile || !isImageEntry(item)) return
        val now = System.currentTimeMillis()
        val parentKey = item.parentKey ?: item.localFile?.parentFile?.absolutePath
        synchronized(usageLock) {
            val store = loadUsageLocked()
            bump(store.files, item.key, now, parentKey)
            if (parentKey != null && !isHistoryKey(parentKey)) {
                bump(store.folders, parentKey, now, null)
            }
            saveUsageLocked(store)
        }
        notifyChanged()
    }

    fun folderUsage(folder: ImageEntry): Int {
        if (isHistoryFolder(folder)) return 0
        return synchronized(usageLock) {
            loadUsageLocked().folders[folder.key]?.count ?: 0
        }
    }

    fun lastFolderFrom(available: List<ImageEntry>, root: ImageEntry = root()): ImageEntry? {
        val saved = HookSettings.getString(LAST_FOLDER_KEY, "").ifBlank { readLastFolderMarker(root) }
        if (saved.isEmpty()) {
            // 没有记录时优先真实分类：扁平授权目录只有一个根分类，不能默认落在空的历史页。
            return available.firstOrNull { !isHistoryFolder(it) } ?: available.firstOrNull()
        }
        return available.firstOrNull { it.key == saved }
            ?: available.firstOrNull { it.name == saved }
            ?: available.firstOrNull()
    }

    /** 沿用调用方刚解析过的授权根，避免为了读旧标记再解析一次根。 */
    private fun readLastFolderMarker(root: ImageEntry): String = runCatching {
        root.findChild(LAST_FOLDER_FILE)
            ?.takeIf { it.isFile }
            ?.inputStream()
            ?.bufferedReader()
            ?.use { it.readText() }
            ?.trim()
    }.getOrNull().orEmpty()

    /** 真的能打开一次流才算可读：跨应用目录可能列得出文件却拒绝读取。 */
    private fun isReadableImage(entry: ImageEntry): Boolean {
        if (!entry.isFile || !isImageEntry(entry)) return false
        return runCatching { entry.inputStream().close() }.isSuccess
    }

    fun remember(folder: ImageEntry) {
        HookSettings.setString(LAST_FOLDER_KEY, folder.key)
    }

    fun createFolder(rawName: String): ImageEntry {
        val name = sanitizeName(rawName)
        check(name.isNotEmpty() && name != HISTORY_DIR_NAME && name != "历史") { "文件夹名无效" }
        val root = root()
        if (root.localFile == null && !root.canWrite) {
            throw ImageAccessException("目录授权只读，无法新建分类")
        }
        val folder = root.createDirectory(name)
        remember(folder)
        notifyChanged()
        return folder
    }

    fun canDelete(folder: ImageEntry): Boolean {
        if (!folder.isDirectory || isHistoryFolder(folder)) return false
        val root = root()
        if (root.key == folder.key) return false
        if (folder.parentKey != root.key) return false
        return isOwned(folder) && folder.canDelete
    }

    fun deleteFolder(folder: ImageEntry) {
        check(canDelete(folder)) { "只能删除模块自己的文件夹" }
        val root = root()
        check(folder.key != root.key) { "不能删除选中的授权目录" }
        // 顶部句柄可能来自旧 UI 快照：SAF 目录先按当前 provider 状态重新解析。
        val target = if (folder.localFile != null) folder else refreshForDeletion(folder, root)
        // 先完整枚举并验证所有后代，任何一项不能删除就在改动前失败；
        // 这份顺序（后序）同时用于实际删除和 usage 精确 key 清理，避免二次遍历。
        val plan = mutableListOf<ImageEntry>()
        collectDeletionPlan(target, plan)
        for (entry in plan) {
            check(entry.delete()) { "无法删除文件夹: ${entry.name}" }
        }
        val removed = plan.mapTo(mutableSetOf()) { it.key }
        synchronized(usageLock) {
            val store = loadUsageLocked()
            store.folders.keys.removeAll(removed)
            store.files.keys.removeAll(removed)
            store.files.entries.removeAll { entry -> entry.value.parentKey?.let { it in removed } == true }
            // 真实文件来源才允许按路径前缀清理旧记录；URI 只能依赖上面枚举出的精确 key。
            val local = folder.localFile
            if (local != null) {
                val prefix = local.absolutePath.trimEnd('/', '\\') + File.separator
                store.files.keys.removeAll { it.startsWith(prefix) }
            }
            saveUsageLocked(store)
        }
        if (HookSettings.getString(LAST_FOLDER_KEY, "") == folder.key) {
            HookSettings.setString(LAST_FOLDER_KEY, "")
        }
        runCatching {
            val marker = root.findChild(LAST_FOLDER_FILE)?.takeIf { it.isFile } ?: return@runCatching
            val text = marker.inputStream().bufferedReader().use { it.readText() }.trim()
            if (text == folder.key) marker.delete()
        }
        notifyChanged()
    }

    /** 用当前 provider 状态重新解析待删的 SAF 目录，避免旧快照掩盖撤销的授权。 */
    private fun refreshForDeletion(folder: ImageEntry, root: ImageEntry): ImageEntry {
        val fresh = ImageEntry.from(folder.key, parentKey = folder.parentKey)
        check(fresh.isDirectory) { "文件夹已不存在: ${folder.name}" }
        check(fresh.key != root.key && fresh.parentKey == root.key) { "只能删除模块自己的文件夹" }
        if (!fresh.canDelete) {
            throw ImageAccessException("目录只读或不支持删除，无法删除：${fresh.name}")
        }
        return fresh
    }

    /**
     * 后序收集整个子树；对每个 SAF 文档先验证当前持久授权与 provider 删除能力，
     * 任何一项不满足都在发生删除前抛出。真实文件分支保持原有行为。
     */
    private fun collectDeletionPlan(entry: ImageEntry, plan: MutableList<ImageEntry>) {
        if (entry.localFile == null && !entry.canDelete) {
            throw ImageAccessException("目录只读或不支持删除，无法删除：${entry.name}")
        }
        if (entry.isDirectory) entry.children().forEach { collectDeletionPlan(it, plan) }
        plan += entry
    }

    data class FunBoxMigration(val packs: Int, val copied: Int, val skipped: Int)

    /**
     * 把 FunBox 表情包复制到 QQ Zygisk 目录。按 packs 里的名称建文件夹，不删除源文件。
     * 两端都接受绝对路径或 SAF tree URI。
     */
    fun migrateFunBoxToQqZygisk(funBoxPath: String, qqZygiskPath: String): FunBoxMigration {
        val funBox = funBoxPath.trim()
        val qqZygisk = qqZygiskPath.trim()
        check(funBox.isNotEmpty() && qqZygisk.isNotEmpty()) { "目录不能为空" }
        val sourceRoot = ImageEntry.from(funBox)
        val destRoot = ImageEntry.from(qqZygisk)
        check(sourceRoot.isDirectory) { "FunBox 目录不存在" }

        val sourceLocal = sourceRoot.localFile
        val destLocal = destRoot.localFile
        if (sourceLocal != null && destLocal != null) {
            val canonicalSource = sourceLocal.canonicalFile
            val canonicalDest = destLocal.canonicalFile
            check(canonicalSource != canonicalDest) { "FunBox 目录和 QQ Zygisk 目录相同" }
            check(!canonicalSource.path.startsWith(canonicalDest.path + File.separator)) {
                "FunBox 目录不能位于 QQ Zygisk 目录里面"
            }
            check(!canonicalDest.path.startsWith(canonicalSource.path + File.separator)) {
                "QQ Zygisk 目录不能位于 FunBox 目录里面"
            }
        } else {
            when (directoryRelation(sourceRoot, destRoot)) {
                DirectoryRelation.SAME ->
                    error("FunBox 目录和 QQ Zygisk 目录相同")
                DirectoryRelation.SOURCE_CONTAINS_DEST ->
                    error("QQ Zygisk 目录不能位于 FunBox 目录里面")
                DirectoryRelation.DEST_CONTAINS_SOURCE ->
                    error("FunBox 目录不能位于 QQ Zygisk 目录里面")
                DirectoryRelation.DISJOINT -> Unit
            }
        }

        if (destLocal != null) {
            check(destRoot.isDirectory || destLocal.mkdirs()) { "无法创建 QQ Zygisk 目录" }
        } else {
            check(destRoot.isDirectory) { "无法创建 QQ Zygisk 目录" }
        }

        val names = funBoxPackNames(sourceRoot)
        val usedNames = mutableSetOf<String>()
        var packs = 0
        var copied = 0
        var skipped = 0
        for (pack in listChildFolders(sourceRoot)) {
            val images = collectImages(pack)
            if (images.isEmpty()) continue
            val display = names[pack.name]?.takeIf { it.isNotBlank() } ?: pack.name
            val folderName = migrationFolderName(display, pack.name, usedNames) ?: continue
            val dest = existingChild(destRoot, folderName) ?: destRoot.createDirectory(folderName)
            check(dest.isDirectory) { "无法创建文件夹: $folderName" }
            packs++
            for (image in images) {
                if (placeImage(dest, image)) copied++ else skipped++
            }
        }
        if (copied > 0) notifyChanged()
        return FunBoxMigration(packs, copied, skipped)
    }

    private fun migrationFolderName(
        display: String,
        packId: String,
        usedNames: MutableSet<String>,
    ): String? {
        var name = sanitizeName(display).ifEmpty { sanitizeName(packId) }
        if (name.isEmpty() || name == HISTORY_DIR_NAME || name == "历史") {
            name = sanitizeName(packId)
        }
        if (name.isEmpty() || name == HISTORY_DIR_NAME || name == "历史") return null
        if (!usedNames.add(name)) {
            val suffix = sanitizeName(packId).ifEmpty { "pack" }
            name = sanitizeName("${name}_$suffix").ifEmpty { return null }
            if (!usedNames.add(name) || name == HISTORY_DIR_NAME || name == "历史") return null
        }
        return name
    }

    private enum class DirectoryRelation {
        SAME,
        SOURCE_CONTAINS_DEST,
        DEST_CONTAINS_SOURCE,
        DISJOINT,
    }

    /**
     * 复制前确定两端目录的真实包含关系，避免把生成的包再次复制进自己。
     *
     * - 同一 provider 的文档：先比较 authority + document ID，再用
     *   [DocumentsContract.isChildDocument] 询问 provider；provider 无法确认时明确失败。
     * - 标准 ExternalStorageProvider 的 `primary:` 文档与普通路径：只用已知的
     *   primary 存储映射做字符串比较，不猜测任意 provider 路径，不做文件 IO、不扩大权限。
     * - 其他无法确认的情况抛 [IllegalStateException]，绝不假设互不包含。
     */
    private fun directoryRelation(source: ImageEntry, dest: ImageEntry): DirectoryRelation =
        establishRelation(source, dest)
            ?: throw IllegalStateException("无法确认 FunBox 目录与 QQ Zygisk 目录的包含关系，请改选其他目录")

    private fun establishRelation(source: ImageEntry, dest: ImageEntry): DirectoryRelation? {
        val sourcePath = primaryPath(source)
        val destPath = primaryPath(dest)
        if (sourcePath != null && destPath != null) return pathRelation(sourcePath, destPath)

        val sourceUri = source.documentUri
        val destUri = dest.documentUri
        if (sourceUri == null || destUri == null) return null
        if (!sourceUri.authority.equals(destUri.authority, ignoreCase = true)) return null
        val sourceId = documentId(source) ?: return null
        val destId = documentId(dest) ?: return null
        if (sourceId == destId) return DirectoryRelation.SAME

        val resolver = AppParasitics.appContext?.contentResolver ?: return null
        // 用真实的已知子项验证 provider 实现了后代语义；不能用自身当探针，
        // strict-descendant provider 对 parent == child 会返回 false。
        // 空目录没有后代，可以直接判否，不需要 provider 支持。
        val sourceChildren = source.children()
        if (sourceChildren.isNotEmpty()) {
            val witness = firstDocumentUri(sourceChildren) ?: return null
            if (!DocumentsContract.isChildDocument(resolver, sourceUri, witness)) return null
            if (DocumentsContract.isChildDocument(resolver, sourceUri, destUri)) {
                return DirectoryRelation.SOURCE_CONTAINS_DEST
            }
        }
        val destChildren = dest.children()
        if (destChildren.isNotEmpty()) {
            val witness = firstDocumentUri(destChildren) ?: return null
            if (!DocumentsContract.isChildDocument(resolver, destUri, witness)) return null
            if (DocumentsContract.isChildDocument(resolver, destUri, sourceUri)) {
                return DirectoryRelation.DEST_CONTAINS_SOURCE
            }
        }
        return DirectoryRelation.DISJOINT
    }

    private fun firstDocumentUri(children: List<ImageEntry>): Uri? =
        children.firstNotNullOfOrNull { it.documentUri }

    private fun documentId(entry: ImageEntry): String? =
        entry.documentUri?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }

    /**
     * 已知的 primary 存储映射：标准 ExternalStorageProvider 的 `primary:<relative>`
     * 与 `/storage/emulated/0/<relative>` 等价；`/storage/self/primary` 和 `/sdcard`
     * 只是同一位置的别名。仅用于比较，不访问文件系统。
     */
    private fun primaryPath(entry: ImageEntry): String? {
        entry.localFile?.let { return normalizePrimaryPath(it.absolutePath) }
        val uri = entry.documentUri ?: return null
        if (!uri.authority.equals(EXTERNAL_STORAGE_AUTHORITY, ignoreCase = true)) return null
        val id = documentId(entry) ?: return null
        if (!id.startsWith(PRIMARY_DOCUMENT_PREFIX)) return null
        val relative = id.removePrefix(PRIMARY_DOCUMENT_PREFIX).trim('/')
        return normalizePrimaryPath(
            if (relative.isEmpty()) PRIMARY_STORAGE_ROOT else "$PRIMARY_STORAGE_ROOT/$relative",
        )
    }

    private fun normalizePrimaryPath(path: String): String {
        val alias = when {
            path == "/storage/self/primary" -> PRIMARY_STORAGE_ROOT
            path.startsWith("/storage/self/primary/") ->
                PRIMARY_STORAGE_ROOT + path.removePrefix("/storage/self/primary")
            path == "/sdcard" -> PRIMARY_STORAGE_ROOT
            path.startsWith("/sdcard/") -> PRIMARY_STORAGE_ROOT + path.removePrefix("/sdcard")
            else -> path
        }
        val segments = ArrayDeque<String>()
        for (segment in alias.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return "/" + segments.joinToString("/")
    }

    private fun pathRelation(sourcePath: String, destPath: String): DirectoryRelation = when {
        sourcePath == destPath -> DirectoryRelation.SAME
        isUnder(destPath, sourcePath) -> DirectoryRelation.SOURCE_CONTAINS_DEST
        isUnder(sourcePath, destPath) -> DirectoryRelation.DEST_CONTAINS_SOURCE
        else -> DirectoryRelation.DISJOINT
    }

    private fun isUnder(path: String, ancestor: String): Boolean =
        path.startsWith(ancestor.trimEnd('/') + "/")

    private fun collectImages(pack: ImageEntry): List<ImageEntry> {
        val images = mutableListOf<ImageEntry>()
        val local = pack.localFile
        if (local != null) {
            val root = local.canonicalFile
            fun walk(dir: File) {
                dir.listFiles()?.forEach { child ->
                    val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                    val inside = canonical == root || canonical.path.startsWith(root.path + File.separator)
                    if (!inside) return@forEach
                    when {
                        canonical.isDirectory && !canonical.name.startsWith(".") -> walk(canonical)
                        canonical.isFile && isImageName(canonical.name) -> images.add(ImageEntry.fromFile(canonical))
                    }
                }
            }
            walk(root)
            return images
        }
        fun walk(entry: ImageEntry) {
            entry.children().forEach { child ->
                when {
                    child.isDirectory && !child.name.startsWith(".") -> walk(child)
                    child.isFile && isImageName(child.name) -> images.add(child)
                }
            }
        }
        walk(pack)
        return images
    }

    private fun placeImage(destDir: ImageEntry, source: ImageEntry): Boolean {
        val preferred = existingChild(destDir, source.name)
        if (preferred != null) {
            if (sameFile(preferred, source)) return false
            if (sameSize(preferred, source)) return false
            val dot = source.name.lastIndexOf('.')
            val extension = if (dot > 0) source.name.substring(dot + 1) else ""
            val base = if (dot > 0) source.name.substring(0, dot).ifEmpty { source.name } else source.name
            for (index in 2..1000) {
                val name = if (extension.isEmpty()) "${base}_$index" else "${base}_$index.$extension"
                val candidate = existingChild(destDir, name)
                if (candidate == null) {
                    copyImage(destDir, source, name)
                    return true
                }
                if (sameSize(candidate, source)) return false
            }
            error("无法写入: ${source.name}")
        }
        copyImage(destDir, source, source.name)
        return true
    }

    private fun sameFile(first: ImageEntry, second: ImageEntry): Boolean {
        if (first.key == second.key) return true
        val firstLocal = first.localFile ?: return false
        val secondLocal = second.localFile ?: return false
        return runCatching { firstLocal.canonicalFile == secondLocal.canonicalFile }.getOrDefault(false)
    }

    private fun sameSize(first: ImageEntry, second: ImageEntry): Boolean =
        first.size >= 0 && second.size >= 0 && first.size == second.size

    private fun existingChild(directory: ImageEntry, name: String): ImageEntry? {
        val local = directory.localFile
        if (local != null) {
            val child = File(local, name)
            return if (child.exists()) ImageEntry.fromFile(child) else null
        }
        return directory.findChild(name)
    }

    private fun copyImage(destDir: ImageEntry, source: ImageEntry, name: String) {
        val local = destDir.localFile
        if (local != null) {
            ImageDirectoryAccess.ensureDirectory(local)
            val target = File(local, name)
            try {
                FileOutputStream(target).use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            return
        }
        val mime = ImageEntry.mimeTypeOf(name) ?: "application/octet-stream"
        val target = destDir.createFile(name, mime)
        try {
            target.outputStream().use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
    }

    fun saveImage(folder: ImageEntry, bytes: ByteArray, extension: String): ImageEntry {
        check(isOwned(folder)) { "只能保存到模块自己的文件夹" }
        val normalized = normalizeExtension(extension)
        val name = "${md5(bytes)}.$normalized"
        val local = folder.localFile
        if (local != null) {
            ImageDirectoryAccess.ensureDirectory(local)
            val file = File(local, name)
            val created = !file.exists()
            if (created) file.writeBytes(bytes)
            remember(folder)
            if (created) notifyChanged()
            return ImageEntry.fromFile(file)
        }
        if (!folder.canWrite) throw ImageAccessException("目录授权只读，无法保存图片")
        remember(folder)
        val existing = folder.findChild(name)
        if (existing != null && existing.isFile) return existing
        val target = folder.createFile(name, ImageEntry.mimeTypeOf(name) ?: "image/jpeg")
        try {
            target.outputStream().use { it.write(bytes) }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
        notifyChanged()
        return target
    }

    fun deleteImage(item: ImageEntry) {
        check(item.isFile && isImageEntry(item)) { "不是可删除的表情文件" }
        check(item.delete()) { "无法删除表情" }
        synchronized(usageLock) {
            val store = loadUsageLocked()
            store.files.remove(item.key)
            saveUsageLocked(store)
        }
        notifyChanged()
    }

    private fun listChildFolders(dir: ImageEntry): List<ImageEntry> =
        dir.children()
            .filter { it.isDirectory && !it.name.startsWith(".") && it.name != HISTORY_DIR_NAME }

    private fun historyImages(): List<ImageEntry> {
        val entries = synchronized(usageLock) { loadUsageLocked().files.entries.map { it.key to it.value } }
        return entries
            .sortedWith(
                compareByDescending<Pair<String, UsageEntry>> { it.second.count }
                    .thenByDescending { it.second.lastUsed },
            )
            .asSequence()
            .mapNotNull { (key, usage) ->
                val entry = runCatching {
                    if (ImageEntry.isDocumentSource(key)) {
                        ImageEntry.from(key, parentKey = usage.parentKey)
                    } else {
                        ImageEntry.fromFile(File(key))
                    }
                }.onFailure { Log.warn("历史图片来源不可用: $key", it) }.getOrNull()
                entry?.takeIf { it.isFile && isImageEntry(it) }
            }
            .take(historyLimit())
            .toList()
    }

    private fun bump(
        map: MutableMap<String, UsageEntry>,
        key: String,
        now: Long,
        parentKey: String?,
    ) {
        val current = map[key]
        if (current == null) {
            map[key] = UsageEntry(1, now, parentKey)
        } else {
            current.count += 1
            current.lastUsed = now
            if (parentKey != null) current.parentKey = parentKey
        }
    }

    private fun metadata(): ImagePanelMetadata {
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        val raw = ROOT_PATH
        val document = ImageEntry.isDocumentSource(raw)
        // 真实文件来源必须沿用旧实现 `File(ROOT_PATH).absolutePath` 的摘要，
        // 否则旧 .usage.json 的使用记录文件名会变化、历史直接丢失。
        val identity = if (document) raw else File(raw).absolutePath
        val legacy = if (document) null else File(raw)
        return ImagePanelMetadata(context.filesDir, identity, legacy)
    }

    private fun loadUsageLocked(): UsageStore {
        val currentRoot = ROOT_PATH
        usageCache?.takeIf { usageCacheRoot == currentRoot }?.let { return it }
        val parsed = runCatching {
            val raw = metadata().readUsage().orEmpty()
            if (raw.isBlank()) return@runCatching UsageStore()
            val json = JSONObject(raw)
            UsageStore(
                files = readUsageMap(json.optJSONObject("files")),
                folders = readUsageMap(json.optJSONObject("folders")),
            )
        }.getOrDefault(UsageStore())
        usageCacheRoot = currentRoot
        usageCache = parsed
        return parsed
    }

    private fun saveUsageLocked(store: UsageStore) {
        usageCacheRoot = ROOT_PATH
        usageCache = store
        runCatching {
            val json = JSONObject()
                .put("files", writeUsageMap(store.files))
                .put("folders", writeUsageMap(store.folders))
            metadata().writeUsage(json.toString())
        }.onFailure { Log.error("保存表情使用记录失败，保留当前进程内的记录", it) }
    }

    private fun readUsageMap(obj: JSONObject?): MutableMap<String, UsageEntry> {
        if (obj == null) return mutableMapOf()
        val result = mutableMapOf<String, UsageEntry>()
        obj.keys().forEach { key ->
            val item = obj.optJSONObject(key) ?: return@forEach
            result[key] = UsageEntry(
                count = item.optInt("c"),
                lastUsed = item.optLong("t"),
                parentKey = item.optString("p").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return result
    }

    private fun writeUsageMap(map: Map<String, UsageEntry>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, entry) ->
            val item = JSONObject().put("c", entry.count).put("t", entry.lastUsed)
            entry.parentKey?.let { item.put("p", it) }
            obj.put(key, item)
        }
        return obj
    }

    private fun funBoxEntry(source: String): ImageEntry? {
        funBoxEntryCache?.takeIf { it.first == source }?.let { return it.second }
        val entry = runCatching { ImageEntry.from(source) }.getOrNull()
        funBoxEntryCache = source to entry
        return entry
    }

    private fun funBoxPackNames(storage: ImageEntry): Map<String, String> {
        val packsFile = findFunBoxPacksFile(storage) ?: return emptyMap()
        val cached = funBoxNameCache
        if (
            cached.key == packsFile.key &&
            cached.lastModified == packsFile.modified &&
            cached.length == packsFile.size
        ) {
            return cached.names
        }

        val raw = runCatching {
            packsFile.inputStream().bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyMap()
        val names = runCatching { parseFunBoxPackList(raw) }
            .getOrElse { parseFunBoxPackListFallback(raw) }
        funBoxNameCache = FunBoxNameCache(
            key = packsFile.key,
            lastModified = packsFile.modified,
            length = packsFile.size,
            names = names,
        )
        return names
    }

    private fun parseFunBoxPackList(raw: String): Map<String, String> {
        val list = JSONObject(raw).optJSONArray("list") ?: return emptyMap()
        return buildMap {
            for (index in 0 until list.length()) {
                val pack = list.optJSONObject(index) ?: continue
                val id = pack.optString("id").trim()
                val name = pack.optString("name").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) put(id, name)
            }
        }
    }

    private fun parseFunBoxPackListFallback(raw: String): Map<String, String> {
        val names = linkedMapOf<String, String>()
        Regex("\\{[^{}]*\\}").findAll(raw).forEach { match ->
            val id = jsonStringField(match.value, "id")?.trim().orEmpty()
            val name = jsonStringField(match.value, "name")?.trim().orEmpty()
            if (id.isNotEmpty() && name.isNotEmpty()) names[id] = name
        }
        return names
    }

    private fun jsonStringField(source: String, key: String): String? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(source) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun findFunBoxPacksFile(storage: ImageEntry): ImageEntry? {
        val local = storage.localFile
        if (local != null) {
            val stickerRoot = local.parentFile ?: return null
            val direct = File(stickerRoot, "packs")
            if (direct.isFile) return ImageEntry.fromFile(direct)
            return stickerRoot.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.map { File(it, "packs") }
                ?.firstOrNull(File::isFile)
                ?.let { ImageEntry.fromFile(it) }
        }
        val parentKey = storage.parentKey ?: return null
        val stickerRoot = runCatching { ImageEntry.from(parentKey) }.getOrNull() ?: return null
        val children = runCatching { stickerRoot.children() }.getOrNull() ?: return null
        children.firstOrNull { it.isFile && it.name == "packs" }?.let { return it }
        for (child in children) {
            if (!child.isDirectory) continue
            val packs = runCatching { child.findChild("packs") }.getOrNull()
            if (packs != null && packs.isFile) return packs
        }
        return null
    }

    private fun isImageEntry(entry: ImageEntry): Boolean = isImageName(entry.name)

    private fun isImageName(name: String): Boolean {
        if (name.startsWith(".") || name.startsWith("__cover__.") || name.endsWith(".txt.jpg")) {
            return false
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isEmpty() || ext in imageExtensions
    }

    private fun sanitizeName(rawName: String): String =
        namePattern.replace(rawName.trim(), "_")
            .trim('.', ' ')

    private fun normalizeExtension(extension: String): String {
        val ext = extension.lowercase().removePrefix(".")
        return if (ext in imageExtensions) ext else "jpg"
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private data class UsageEntry(
        var count: Int,
        var lastUsed: Long,
        var parentKey: String? = null,
    )

    private data class UsageStore(
        val files: MutableMap<String, UsageEntry> = mutableMapOf(),
        val folders: MutableMap<String, UsageEntry> = mutableMapOf(),
    )

    private data class FunBoxNameCache(
        val key: String = "",
        val lastModified: Long = Long.MIN_VALUE,
        val length: Long = Long.MIN_VALUE,
        val names: Map<String, String> = emptyMap(),
    )
}

/**
 * [ImageFolderStore.scanFolders] 一次加载得到的分类快照。
 *
 * [imagesByFolder] 只包含本次扫描真正枚举过的目录（授权根、外部可读分组，以及调用方用
 * [withImagesFor] 刚枚举过的分类），值遵循 [ImageFolderStore.images] 的筛选与排序；
 * 消费者未命中时应调用 [ImageFolderStore.images] 重新读取。
 * 这只是单次加载快照：不能用来绕过后续的读取/解码/删除授权校验。
 */
internal data class FolderScan(
    val root: ImageEntry,
    val folders: List<ImageEntry>,
    val imagesByFolder: Map<String, List<ImageEntry>>,
) {
    /**
     * 把刚刚真正枚举过的分类图片补进快照，供首屏直接绑定。
     *
     * 只应在完成一次真实枚举后调用，不能用来伪装“已扫描”；已存在的分类原样返回。
     */
    fun withImagesFor(folder: ImageEntry): FolderScan {
        if (imagesByFolder.containsKey(folder.key)) return this
        val images = ImageFolderStore.images(folder)
        return copy(imagesByFolder = imagesByFolder + (folder.key to images))
    }

    /**
     * 用本次快照判断分类的可删除性，避免每个分类都重新解析授权根。
     *
     * 分类必须是授权根的直接子目录，且 provider 声明支持删除；真正删除时
     * [ImageFolderStore.deleteFolder] 仍会按当前 provider 状态重新校验授权。
     */
    fun canDelete(folder: ImageEntry): Boolean =
        folder.isDirectory &&
            !ImageFolderStore.isHistoryFolder(folder) &&
            folder.key != root.key &&
            folder.parentKey == root.key &&
            folder.canDelete
}
