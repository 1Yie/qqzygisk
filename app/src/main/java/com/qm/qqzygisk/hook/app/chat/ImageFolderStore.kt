package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.host.HostType
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天长按保存图片的本地目录。
 * 子文件夹会作为表情面板分组，第一张图默认当封面。
 * 浏览时还会带上模块内置贴纸目录。
 */
object ImageFolderStore {
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
    const val HISTORY_DIR_NAME = "__history__"
    const val HISTORY_LIMIT_KEY = "history_limit"
    const val DEFAULT_HISTORY_LIMIT = 80
    const val MIN_HISTORY_LIMIT = 1
    const val MAX_HISTORY_LIMIT = 500
    private val usageLock = Any()
    @Volatile
    private var usageCache: UsageStore? = null
    private var usageCacheRoot: String? = null
    private val namePattern = Regex("[\\/:*?\"<>|]")
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
    @Volatile
    private var funBoxNameCache = FunBoxNameCache()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    fun root(): File = File(ROOT_PATH).apply {
        runCatching { ImageDirectoryAccess.ensureDirectory(this) }
            .onFailure { Log.error("表情根目录不可用: $absolutePath", it) }
    }

    fun isOwned(folder: File): Boolean =
        folder.absolutePath.startsWith(root().absolutePath + "/")

    fun historyFolder(): File = File(root(), HISTORY_DIR_NAME)

    fun historyLimit(): Int =
        HookSettings.getInt(HISTORY_LIMIT_KEY, DEFAULT_HISTORY_LIMIT)
            .coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)

    fun isHistoryFolder(folder: File): Boolean = folder.name == HISTORY_DIR_NAME

    fun folders(includeExternal: Boolean = false): List<File> {
        val owned = listChildFolders(root())
        val extra = if (includeExternal) {
            SCAN_ROOTS.asSequence()
                .filter { it != ROOT_PATH }
                .map(::File)
                .filter { it.isDirectory && it.canRead() }
                .flatMap { listChildFolders(it).asSequence() }
                // 外部目录里没有可读图片的分组不展示：系统可能放行列目录、却拒绝读文件，
                // 过滤掉这些分组，避免出现一排点进去全空、切换还卡顿的分类。
                .filter(::hasReadableImage)
                .toList()
        } else {
            emptyList()
        }
        val real = (owned + extra)
            .distinctBy { it.absolutePath }
            .filter { !isHistoryFolder(it) }
            .sortedWith(
                compareByDescending<File> { folderUsage(it) }
                    .thenBy { displayName(it).lowercase() },
            )
        if (isWeChat) {
            Log.info("微信目录扫描: root=$ROOT_PATH 自有分类=${owned.size} 外部分类=${extra.size}")
        }
        return if (includeExternal) listOf(historyFolder()) + real else real
    }

    fun images(folder: File): List<File> {
        if (isHistoryFolder(folder)) return historyImages()
        return folder.listFiles()
            ?.filter { it.isFile && isImageFile(it) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun coverFile(folder: File): File? {
        if (isHistoryFolder(folder)) return historyImages().firstOrNull()
        return folder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isImageFile(it) }
            ?.minByOrNull { it.lastModified() }
    }

    fun displayName(folder: File): String {
        if (isHistoryFolder(folder)) return "历史"
        val storagePath = sourcePaths.funBox
        if (folder.parentFile?.absolutePath != File(storagePath).absolutePath) return folder.name
        return funBoxPackNames(storagePath)[folder.name]?.takeIf { it.isNotBlank() } ?: folder.name
    }

    fun recordUsage(file: File) {
        if (!file.isFile || !isImageFile(file)) return
        val now = System.currentTimeMillis()
        synchronized(usageLock) {
            val store = loadUsageLocked()
            bump(store.files, file.absolutePath, now)
            val parent = file.parentFile
            if (parent != null && !isHistoryFolder(parent)) {
                bump(store.folders, parent.absolutePath, now)
            }
            saveUsageLocked(store)
        }
        notifyChanged()
    }

    fun folderUsage(folder: File): Int {
        if (isHistoryFolder(folder)) return 0
        return synchronized(usageLock) {
            loadUsageLocked().folders[folder.absolutePath]?.count ?: 0
        }
    }

    fun lastFolderFrom(available: List<File>): File? {
        val saved = HookSettings.getString(LAST_FOLDER_KEY, "").ifBlank {
            // 目录可能属于别的宿主（微信读 QQ 的 Android/media 会被 EACCES 拒绝），
            // 读失败只能当作没记录，绝不能让面板直接打开失败。
            runCatching {
                File(root(), LAST_FOLDER_FILE)
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
            }.getOrNull().orEmpty()
        }
        if (saved.isEmpty()) return available.firstOrNull()
        return available.firstOrNull { it.absolutePath == saved }
            ?: available.firstOrNull { it.name == saved }
            ?: available.firstOrNull()
    }

    /**
     * 目录里是否存在**真的能打开**的图片。
     *
     * 只判断 canRead() 不够：跨应用访问 `Android/media/<其它包>` 时系统可能放行列目录、
     * 却拒绝读文件，必须真开一次流才能确认，否则会出现一排点进去全空的分类。
     */
    private fun hasReadableImage(folder: File): Boolean {
        val candidate = folder.listFiles()
            ?.firstOrNull { it.isFile && isImageFile(it) }
            ?: return false
        return runCatching { java.io.FileInputStream(candidate).close() }.isSuccess
    }

    fun lastFolder(includeExternal: Boolean = false): File? {
        val available = folders(includeExternal)
        val saved = HookSettings.getString(LAST_FOLDER_KEY, "").ifBlank {
            runCatching {
                File(ROOT_PATH, LAST_FOLDER_FILE)
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
            }.getOrNull().orEmpty()
        }
        if (saved.isEmpty()) return available.firstOrNull()
        return available.firstOrNull { it.absolutePath == saved }
            ?: available.firstOrNull { it.name == saved }
            ?: available.firstOrNull()
    }

    fun remember(folder: File) {
        HookSettings.setString(LAST_FOLDER_KEY, folder.absolutePath)
    }

    fun createFolder(rawName: String): File {
        val name = sanitizeName(rawName)
        check(name.isNotEmpty() && name != HISTORY_DIR_NAME && name != "历史") { "文件夹名无效" }
        val folder = ImageDirectoryAccess.createFolder(File(ROOT_PATH), name)
        remember(folder)
        notifyChanged()
        return folder
    }

    fun canDelete(folder: File): Boolean {
        if (!folder.isDirectory || isHistoryFolder(folder)) return false
        val parent = folder.parentFile ?: return false
        return parent.absolutePath == root().absolutePath && isOwned(folder)
    }

    fun deleteFolder(folder: File) {
        check(canDelete(folder)) { "只能删除模块自己的文件夹" }
        val rootPath = root().absolutePath
        deleteTree(folder, rootPath)
        val prefix = folder.absolutePath.trimEnd('/', '\\') + File.separator
        synchronized(usageLock) {
            val store = loadUsageLocked()
            store.folders.remove(folder.absolutePath)
            store.files.keys.removeAll { it.startsWith(prefix) }
            saveUsageLocked(store)
        }
        if (HookSettings.getString(LAST_FOLDER_KEY, "") == folder.absolutePath) {
            HookSettings.setString(LAST_FOLDER_KEY, "")
        }
        runCatching {
            val marker = File(root(), LAST_FOLDER_FILE)
            if (marker.isFile && marker.readText().trim() == folder.absolutePath) marker.delete()
        }
        notifyChanged()
    }

    private fun deleteTree(file: File, rootPath: String) {
        val path = file.absolutePath
        check(path == rootPath || path.startsWith(rootPath + File.separator)) {
            "拒绝删除模块目录之外的文件"
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it, rootPath) }
        }
        check(file.delete()) { "无法删除文件夹: ${file.name}" }
    }

    data class FunBoxMigration(val packs: Int, val copied: Int, val skipped: Int)

    /**
     * 把 FunBox 表情包复制到 QQ Zygisk 目录。按 packs 里的名称建文件夹，不删除源文件。
     */
    fun migrateFunBoxToQqZygisk(funBoxPath: String, qqZygiskPath: String): FunBoxMigration {
        val funBox = funBoxPath.trim()
        val qqZygisk = qqZygiskPath.trim()
        check(funBox.isNotEmpty() && qqZygisk.isNotEmpty()) { "目录不能为空" }
        val sourceRoot = File(funBox).canonicalFile
        val destRoot = File(qqZygisk).canonicalFile
        check(sourceRoot.isDirectory) { "FunBox 目录不存在" }
        check(sourceRoot != destRoot) { "FunBox 目录和 QQ Zygisk 目录相同" }
        check(!sourceRoot.path.startsWith(destRoot.path + File.separator)) {
            "FunBox 目录不能位于 QQ Zygisk 目录里面"
        }
        check(!destRoot.path.startsWith(sourceRoot.path + File.separator)) {
            "QQ Zygisk 目录不能位于 FunBox 目录里面"
        }
        check(destRoot.isDirectory || destRoot.mkdirs()) { "无法创建 QQ Zygisk 目录" }
        val names = funBoxPackNames(sourceRoot.path)
        val usedNames = mutableSetOf<String>()
        var packs = 0
        var copied = 0
        var skipped = 0
        for (pack in listChildFolders(sourceRoot)) {
            val images = collectImages(pack)
            if (images.isEmpty()) continue
            val display = names[pack.name]?.takeIf { it.isNotBlank() } ?: pack.name
            val folderName = migrationFolderName(display, pack.name, usedNames) ?: continue
            val dest = File(destRoot, folderName)
            check(dest.isDirectory || dest.mkdirs()) { "无法创建文件夹: $folderName" }
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

    private fun collectImages(pack: File): List<File> {
        val root = pack.canonicalFile
        val images = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.forEach { child ->
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                val inside = canonical == root || canonical.path.startsWith(root.path + File.separator)
                if (!inside) return@forEach
                when {
                    canonical.isDirectory && !canonical.name.startsWith(".") -> walk(canonical)
                    canonical.isFile && isImageFile(canonical) -> images.add(canonical)
                }
            }
        }
        walk(root)
        return images
    }

    private fun placeImage(destDir: File, source: File): Boolean {
        val preferred = File(destDir, source.name)
        if (preferred.canonicalFile == source.canonicalFile) return false
        if (!preferred.exists()) {
            source.copyTo(preferred)
            return true
        }
        if (preferred.length() == source.length()) return false
        val ext = source.extension
        val base = source.nameWithoutExtension.ifEmpty { source.name }
        for (index in 2..1000) {
            val name = if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext"
            val candidate = File(destDir, name)
            if (!candidate.exists()) {
                source.copyTo(candidate)
                return true
            }
            if (candidate.length() == source.length()) return false
        }
        error("无法写入: ${source.name}")
    }

    fun saveImage(folder: File, bytes: ByteArray, extension: String): File {
        check(isOwned(folder)) { "只能保存到模块自己的文件夹" }
        ensureFolder(folder)
        val file = File(folder, "${md5(bytes)}.${normalizeExtension(extension)}")
        val created = !file.exists()
        if (created) file.writeBytes(bytes)
        remember(folder)
        if (created) notifyChanged()
        return file
    }

    fun deleteImage(file: File) {
        check(file.isFile && isImageFile(file)) { "不是可删除的表情文件" }
        check(file.delete()) { "无法删除表情" }
        synchronized(usageLock) {
            val store = loadUsageLocked()
            store.files.remove(file.absolutePath)
            saveUsageLocked(store)
        }
        notifyChanged()
    }

    private fun listChildFolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != HISTORY_DIR_NAME }
            .orEmpty()
            .toList()

    private fun historyImages(): List<File> {
        val entries = synchronized(usageLock) { loadUsageLocked().files.entries.toList() }
        return entries
            .sortedWith(
                compareByDescending<Map.Entry<String, UsageEntry>> { it.value.count }
                    .thenByDescending { it.value.lastUsed },
            )
            .map { File(it.key) }
            .filter { it.isFile && isImageFile(it) }
            .take(historyLimit())
    }

    private fun bump(map: MutableMap<String, UsageEntry>, key: String, now: Long) {
        val current = map[key]
        if (current == null) {
            map[key] = UsageEntry(1, now)
        } else {
            current.count += 1
            current.lastUsed = now
        }
    }

    private fun metadata(): ImagePanelMetadata {
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        return ImagePanelMetadata(context.filesDir, File(ROOT_PATH))
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
            )
        }
        return result
    }

    private fun writeUsageMap(map: Map<String, UsageEntry>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, entry) ->
            obj.put(
                key,
                JSONObject().put("c", entry.count).put("t", entry.lastUsed),
            )
        }
        return obj
    }

    private fun funBoxPackNames(storagePath: String): Map<String, String> {
        val packsFile = findFunBoxPacksFile(storagePath) ?: return emptyMap()
        val cached = funBoxNameCache
        if (
            cached.path == packsFile.absolutePath &&
            cached.lastModified == packsFile.lastModified() &&
            cached.length == packsFile.length()
        ) {
            return cached.names
        }

        val raw = packsFile.readText()
        val names = runCatching { parseFunBoxPackList(raw) }
            .getOrElse { parseFunBoxPackListFallback(raw) }
        funBoxNameCache = FunBoxNameCache(
            path = packsFile.absolutePath,
            lastModified = packsFile.lastModified(),
            length = packsFile.length(),
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

    private fun findFunBoxPacksFile(storagePath: String): File? {
        val stickerRoot = File(storagePath).parentFile ?: return null
        val direct = File(stickerRoot, "packs")
        if (direct.isFile) return direct
        return stickerRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.map { File(it, "packs") }
            ?.firstOrNull(File::isFile)
    }

    private fun isImageFile(file: File): Boolean {
        val name = file.name
        if (name.startsWith(".") || name.startsWith("__cover__.") || name.endsWith(".txt.jpg")) {
            return false
        }
        val ext = file.extension.lowercase()
        return ext.isEmpty() || ext in imageExtensions
    }

    private fun ensureFolder(folder: File) = ImageDirectoryAccess.ensureDirectory(folder)

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

    private data class UsageEntry(var count: Int, var lastUsed: Long)

    private data class UsageStore(
        val files: MutableMap<String, UsageEntry> = mutableMapOf(),
        val folders: MutableMap<String, UsageEntry> = mutableMapOf(),
    )

    private data class FunBoxNameCache(
        val path: String = "",
        val lastModified: Long = Long.MIN_VALUE,
        val length: Long = Long.MIN_VALUE,
        val names: Map<String, String> = emptyMap(),
    )
}
