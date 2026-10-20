package com.qm.qqzygisk.hook.app.hooker

import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.chat.ImageAccessException
import com.qm.qqzygisk.hook.app.chat.ImageEntry
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.app.chat.ImageSendCache
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.set
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

abstract class ExtraEmoticon {
    abstract fun QQEmoticonObject(): Any
}

abstract class ExtraEmoticonPanel {
    abstract fun emoticons(): List<ExtraEmoticon>
    abstract fun emoticonPanelIconURL(): String?
    abstract fun uniqueId(): String

    /**
     * 是否已至少成功发布过一份快照。加载中或首次加载失败时为 false，
     * 调用方此时必须保留现有列表，不能把空列表当成删除结果。
     */
    open fun isSnapshotReady(): Boolean = true
}

abstract class ExtraEmoticonProvider {
    abstract fun extraEmoticonList(): List<ExtraEmoticonPanel>
    abstract fun uniqueId(): String
}

/** 快照最短刷新间隔：避免适配器刷新与数据加载互相触发形成循环。 */
private const val REFRESH_INTERVAL_MS = 2000L

/** 同一授权错误最多每 5 秒提示一次，避免面板刷新时反复弹 Toast。 */
private const val ERROR_NOTIFY_INTERVAL_MS = 5000L

/**
 * 首张之后的生效最小发布间隔：下一次发布至少再准备这么多张。
 * 配合倍增阈值，让累积发布的前缀快照总量保持 O(N)。
 */
private const val FIRST_LOAD_MIN_STEP = 16

/**
 * 原生发送回调只能拿到 FavoriteEmoticonInfo.path。SAF 来源在这里通过
 * [ImageSendCache.sourceFor] 还原成原始文档身份后更新历史；无法映射的缓存路径直接忽略，
 * 缓存文件绝不能变成历史里的“表情”。
 */
internal fun recordEmoticonSendUsage(path: String) {
    val context = AppParasitics.appContext ?: return
    val entry = runCatching { ImageSendCache.sourceFor(context, File(path)) }
        .onFailure { Log.error("还原表情来源失败: $path", it) }
        .getOrNull() ?: return
    runCatching { ImageFolderStore.recordUsage(entry) }
        .onFailure { Log.error("更新表情历史失败: $path", it) }
}

/**
 * 原生面板的数据接口是同步的，不能在这里等待 provider/复制任务。
 * 所有目录枚举与文件实质化都在这个后台线程完成，完成后通过 [LocalDocumentEmoticonProvider.onDataRefreshed]
 * 通知宿主刷新适配器；调用方立即拿到上一份快照（首次为空，属于加载中的瞬时状态）。
 */
private val emoticonLoader = Executors.newSingleThreadExecutor { task ->
    Thread(task, "QQZygisk-EmoticonLoad").apply { isDaemon = true }
}

/**
 * 分类图标单独排队：图标解析（含 SAF 封面复制）不能排在当前打开页面的图片准备前面，
 * 否则首屏要等所有 tab 的图标都处理完。
 */
private val emoticonIconLoader = Executors.newSingleThreadExecutor { task ->
    Thread(task, "QQZygisk-EmoticonIcon").apply { isDaemon = true }
}

private val mainHandler = Handler(Looper.getMainLooper())

private fun submitLoad(task: () -> Unit) {
    runCatching { emoticonLoader.execute(task) }
        .onFailure { Log.error("无法调度表情加载任务", it) }
}

private fun submitIconLoad(task: () -> Unit) {
    runCatching { emoticonIconLoader.execute(task) }
        .onFailure { Log.error("无法调度表情图标任务", it) }
}

private class BuiltEmoticons(
    val items: List<ExtraEmoticon>,
    val signature: String,
)

/** 一次分类构建的结果：成功、被 generation 取消，或宿主反射对象整体不可用。 */
private sealed class EmoticonBuildResult {
    class Ready(val built: BuiltEmoticons) : EmoticonBuildResult()
    object Cancelled : EmoticonBuildResult()
    class Unavailable(val error: Throwable) : EmoticonBuildResult()
}

private fun nativePathOf(entry: ImageEntry, onError: (Throwable) -> Unit): String? {
    entry.localFile?.let { return it.absolutePath }
    val context = AppParasitics.appContext ?: return null
    return runCatching { ImageSendCache.fileFor(context, entry).absolutePath }
        .onFailure {
            Log.error("准备原生表情文件失败: ${entry.key}", it)
            onError(it)
        }
        .getOrNull()
}

/**
 * 逐张准备原生需要的文件。首帧不必等整个分类：第一张成功后立即通过 [onProgress] 发布，
 * 之后发布时间隔按“已准备前缀长度”倍增（至少再等 [FIRST_LOAD_MIN_STEP] 张），
 * 使前缀快照与签名串的累计复制量为 O(N)，不会在大分类上产生 O(N²) 开销。
 * 剩余条目继续处理，结束时统一发布完整快照。
 * 刷新（已有快照）时 [onProgress] 为 null，新列表完整准备好才整体替换，绝不提前丢掉未准备的尾部。
 *
 * [isCancelled] 或 [onProgress] 返回 false 时返回 [EmoticonBuildResult.Cancelled]，调用方必须放弃本次结果。
 * 反射对象整体不可用（类/构造器解析失败，或所有条目都无法创建）时返回
 * [EmoticonBuildResult.Unavailable]：调用方必须保留旧快照，绝不能发布空列表（会被当成图片全部删除）。
 * 单条目的创建/赋值异常按“跳过该条目”处理并报告，其余条目继续，最终发布实际成功的完整结果。
 * [onError] 抛出的异常（授权被撤销）会中止本次构建，交由调用方保留旧快照。
 */
private fun buildEmoticonInfos(
    entries: List<ImageEntry>,
    groupId: String,
    onError: (Throwable) -> Unit,
    isCancelled: () -> Boolean,
    onProgress: ((BuiltEmoticons) -> Boolean)?,
): EmoticonBuildResult {
    if (entries.isEmpty()) {
        return EmoticonBuildResult.Ready(BuiltEmoticons(emptyList(), ""))
    }
    val infoConstructor = runCatching {
        "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo"
            .toAppClass()
            .resolve()
            .firstConstructor()
    }.getOrElse {
        Log.error("无法解析收藏表情类", it)
        return EmoticonBuildResult.Unavailable(it)
    }
    val next = mutableListOf<ExtraEmoticon>()
    val signature = StringBuilder()
    var constructionError: Throwable? = null
    fun append(entry: ImageEntry) {
        val path = nativePathOf(entry, onError) ?: return
        val item = try {
            object : ExtraEmoticon() {
                val info = infoConstructor.create()
                init {
                    info.set("path", path)
                    info.set("actionData", "$groupId:${entry.key}")
                }
                override fun QQEmoticonObject(): Any = info
            }
        } catch (error: Throwable) {
            // 单条目构造失败：与 nativePath 失败同语义，跳过它继续其余条目。
            Log.error("创建表情对象失败: ${entry.key}", error)
            if (constructionError == null) constructionError = error
            onError(error)
            return
        }
        signature.append(entry.key).append('\u0001').append(path).append('\n')
        next.add(item)
    }
    var index = 0
    var nextPublishAt = 1
    while (index < entries.size) {
        if (isCancelled()) return EmoticonBuildResult.Cancelled
        append(entries[index++])
        if (
            index < entries.size &&
            onProgress != null &&
            next.isNotEmpty() &&
            next.size >= nextPublishAt
        ) {
            if (!onProgress(BuiltEmoticons(next.toList(), signature.toString()))) {
                return EmoticonBuildResult.Cancelled
            }
            // 已发布前缀为 s 时，下一个阈值至少 s+16 且按 2 倍增长：
            // 累计前缀复制量 ≤ 3N，避免每批都复制整个前缀。
            nextPublishAt = maxOf(next.size * 2, next.size + FIRST_LOAD_MIN_STEP)
        }
    }
    // 有条目却一个都创建不出：说明宿主对象整体不可用，保留旧快照而不是发布空列表。
    constructionError?.let { error ->
        if (next.isEmpty()) return EmoticonBuildResult.Unavailable(error)
    }
    return EmoticonBuildResult.Ready(BuiltEmoticons(next.toList(), signature.toString()))
}

/**
 * 单个面板的快照状态机，负责限流、generation 校验与发布。
 * 所有方法都可能从主线程（读）或加载线程（写）调用。
 */
private class SnapshotLoader(
    private val groupId: String,
    private val notifyChanged: () -> Unit,
    private val reportError: (Throwable) -> Unit,
    private val currentGeneration: () -> Int,
    private val submit: (() -> Unit) -> Unit,
) {
    private val lock = Any()
    private var snapshot: List<ExtraEmoticon> = emptyList()
    private var signature = ""
    private var loadedAt = 0L
    private var loading = false
    private var loadedOnce = false
    private var published = false

    val isReady: Boolean get() = synchronized(lock) { published }

    fun invalidate() {
        synchronized(lock) { loadedAt = 0L }
    }

    fun emoticons(load: () -> List<ImageEntry>): List<ExtraEmoticon> {
        requestRefresh(load)
        return synchronized(lock) { snapshot }
    }

    private fun requestRefresh(load: () -> List<ImageEntry>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (loadedOnce && now - loadedAt < REFRESH_INTERVAL_MS) return
            if (loading) return
            loading = true
        }
        submit { refresh(load) }
    }

    private fun refresh(load: () -> List<ImageEntry>) {
        val generation = currentGeneration()
        val entries = try {
            load()
        } catch (error: Throwable) {
            synchronized(lock) {
                loading = false
                loadedAt = System.currentTimeMillis()
                loadedOnce = true
            }
            reportError(error)
            return
        }
        val progressive = synchronized(lock) { !published }
        val onProgress: ((BuiltEmoticons) -> Boolean)? = if (progressive) {
            { partial -> publishPartial(partial, generation) }
        } else {
            null
        }
        val result = try {
            buildEmoticonInfos(
                entries = entries,
                groupId = groupId,
                onError = { error ->
                    // 授权在构建途中失效：中止本次构建，不能把读不到当成图片被删除。
                    if (error is ImageAccessException) throw error
                    reportError(error)
                },
                isCancelled = { generation != currentGeneration() },
                onProgress = onProgress,
            )
        } catch (error: Throwable) {
            synchronized(lock) {
                loading = false
                loadedAt = System.currentTimeMillis()
                loadedOnce = true
            }
            reportError(error)
            return
        }
        when (result) {
            EmoticonBuildResult.Cancelled -> {
                // generation 已变化：丢弃结果并允许下次查询立即重算。
                synchronized(lock) {
                    loading = false
                    loadedAt = 0L
                }
                notifyChanged()
            }
            is EmoticonBuildResult.Unavailable -> {
                // 宿主类不可用：保留上一份快照（没有快照就继续 not ready），
                // 绝不能把空列表发布成“图片被删除”。
                synchronized(lock) {
                    loading = false
                    loadedAt = System.currentTimeMillis()
                    loadedOnce = true
                }
            }
            is EmoticonBuildResult.Ready -> finish(result.built, generation)
        }
    }

    /** 首批增量发布：只在 generation 未变化时替换前缀快照，不设置完成标记。 */
    private fun publishPartial(built: BuiltEmoticons, generation: Int): Boolean {
        var changed = false
        synchronized(lock) {
            if (generation != currentGeneration()) return false
            if (built.signature != signature) {
                signature = built.signature
                snapshot = built.items
                changed = true
            }
            published = true
        }
        if (changed) notifyChanged()
        return true
    }

    private fun finish(built: BuiltEmoticons, generation: Int) {
        var changed = false
        var cancelled = false
        synchronized(lock) {
            if (generation != currentGeneration()) {
                loading = false
                loadedAt = 0L
                cancelled = true
            } else {
                if (built.signature != signature) {
                    signature = built.signature
                    snapshot = built.items
                    changed = true
                }
                published = true
                loadedAt = System.currentTimeMillis()
                loadedOnce = true
                loading = false
            }
        }
        if (cancelled) {
            notifyChanged()
            return
        }
        if (changed) notifyChanged()
    }
}

class LocalDocumentEmoticonProvider : ExtraEmoticonProvider() {
    private inner class Panel(
        private val entry: ImageEntry,
        private val panelId: String,
        initialImages: List<ImageEntry>,
        scanGeneration: Int,
    ) : ExtraEmoticonPanel() {
        private val stateLock = Any()
        private var iconPath: String? = null
        private var iconResolved = false
        private var iconGeneration = -1

        /** 分类扫描时已枚举的图片；首次打开面板直接复用，避免再列一次目录。 */
        private var pendingImages: List<ImageEntry>? = initialImages
        private var pendingGeneration: Int = scanGeneration
        private var knownImages: List<ImageEntry> = initialImages

        private val content = SnapshotLoader(
            groupId = panelId,
            notifyChanged = { notifyRefreshed() },
            reportError = { error -> reportAccessError(error) },
            currentGeneration = { providerGeneration },
            submit = { task -> submitLoad(task) },
        )

        fun invalidate() {
            content.invalidate()
            // 目录可能被改写（含新增/替换 __cover__）：允许下一次请求重新解析图标，
            // 同时保留旧路径继续显示，避免 tab 闪烁。
            synchronized(stateLock) { iconResolved = false }
        }

        /** 首次加载前用同一代的新扫描结果替换首帧快照；封面候选变化时失效已解析图标。 */
        fun updatePending(images: List<ImageEntry>, generation: Int) {
            var iconInvalidated = false
            synchronized(stateLock) {
                // 过期代的扫描结果不允许覆盖当前候选。
                if (generation != providerGeneration) return
                iconInvalidated = applyImagesLocked(images)
                if (pendingImages != null) {
                    pendingImages = images
                    pendingGeneration = generation
                }
            }
            if (iconInvalidated) notifyRefreshed()
        }

        /** 记录当前图片列表，返回封面候选是否发生变化（变化后需重解析一次图标）。 */
        private fun applyImagesLocked(images: List<ImageEntry>): Boolean {
            val previous = knownImages.minByOrNull { it.modified }?.key
            val next = images.minByOrNull { it.modified }?.key
            knownImages = images
            if (previous == next) return false
            iconResolved = false
            // 分类已无图片：旧封面不能再继续显示。
            if (next == null) iconPath = null
            return true
        }

        fun folderKey(): String = entry.key

        override fun isSnapshotReady(): Boolean = content.isReady

        override fun emoticons(): List<ExtraEmoticon> = content.emoticons { loadEntries() }

        override fun emoticonPanelIconURL(): String? {
            // 先按 iconResolved/代状态决定是否重解析（requestIcon 内部去重），
            // 再返回旧缓存；新图标完成会触发刷新，旧图标不会挡住替换。
            requestIcon()
            return synchronized(stateLock) { iconPath }?.takeIf { File(it).isFile }?.let { "file://$it" }
        }

        override fun uniqueId(): String = panelId

        private fun loadEntries(): List<ImageEntry> {
            val pending = synchronized(stateLock) {
                val cached = pendingImages
                pendingImages = null
                // 扫描快照只在同一代有效；generation 已变化时重新枚举，避免发布过期列表。
                cached?.takeIf { pendingGeneration == providerGeneration }
            }
            val entries = pending ?: ImageFolderStore.images(entry)
            val iconInvalidated = synchronized(stateLock) { applyImagesLocked(entries) }
            if (iconInvalidated) notifyRefreshed()
            return entries
        }

        private fun requestIcon() {
            var generation = providerGeneration
            synchronized(stateLock) {
                generation = providerGeneration
                // 同一代内已解析才去重；失效后（iconResolved=false 或代已变化）必须重解析，
                // 否则新增/替换的封面永远不会生效。
                if (iconResolved && iconGeneration == generation) {
                    val existing = iconPath
                    if (existing == null || File(existing).isFile) return
                    // 缓存文件已被清理，允许重新解析。
                    iconPath = null
                }
                iconResolved = true
                iconGeneration = generation
            }
            submitIconLoad {
                val icon = runCatching { resolveIconPath() }.getOrElse { error ->
                    synchronized(stateLock) {
                        // 只撤销本次请求自己的标记，不能踩掉更新代的请求。
                        if (iconGeneration == generation) iconResolved = false
                    }
                    if (error is ImageAccessException) reportAccessError(error)
                    else Log.warn("解析表情分类图标失败: ${entry.key}", error)
                    return@submitIconLoad
                }
                if (generation != providerGeneration) {
                    synchronized(stateLock) {
                        if (iconGeneration == generation) iconResolved = false
                    }
                    return@submitIconLoad
                }
                var changed = false
                synchronized(stateLock) {
                    if (icon != null && icon != iconPath) {
                        iconPath = icon
                        changed = true
                    }
                }
                if (changed) notifyRefreshed()
            }
        }

        /**
         * 一次枚举同时决定图标：先找 `__cover__.` 覆盖文件，否则使用分类扫描已得到的
         * 图片列表（等价于旧 coverFile/images 的最低 modified 项），不再重复列目录。
         */
        private fun resolveIconPath(): String? {
            val namedCover = try {
                entry.children().firstOrNull { it.isFile && it.name.startsWith("__cover__.") }
            } catch (error: Throwable) {
                if (error is ImageAccessException) throw error
                Log.warn("枚举分类封面失败: ${entry.key}", error)
                null
            }
            val fallback = synchronized(stateLock) { knownImages }.minByOrNull { it.modified }
            val target = namedCover ?: fallback ?: return null
            return nativePathOf(target) { error ->
                if (error is ImageAccessException) throw error
                reportAccessError(error)
            }
        }
    }

    private inner class HistoryPanel : ExtraEmoticonPanel() {
        private val content = SnapshotLoader(
            groupId = ImageFolderStore.HISTORY_DIR_NAME,
            notifyChanged = { notifyRefreshed() },
            reportError = { error -> reportAccessError(error) },
            currentGeneration = { providerGeneration },
            submit = { task -> submitLoad(task) },
        )

        override fun uniqueId(): String = ImageFolderStore.HISTORY_DIR_NAME

        override fun isSnapshotReady(): Boolean = content.isReady

        override fun emoticons(): List<ExtraEmoticon> = content.emoticons {
            ImageFolderStore.images(ImageFolderStore.historyFolder())
        }

        override fun emoticonPanelIconURL(): String? = null
    }

    private val panelsMap = ConcurrentHashMap<String, Panel>()
    private val historyPanel = HistoryPanel()
    private val panelsLock = Any()
    private val errorLock = Any()

    @Volatile
    private var panelSnapshot: List<ExtraEmoticonPanel> = emptyList()
    @Volatile
    private var panelsSignature = ""
    @Volatile
    private var panelsLoadedAt = 0L
    @Volatile
    private var panelsLoading = false
    @Volatile
    private var panelsLoadedOnce = false
    @Volatile
    private var panelsPublished = false
    @Volatile
    private var providerGeneration = 0

    private var lastErrorMessage: String? = null
    private var lastErrorAt = 0L
    private val refreshNotifyPending = AtomicBoolean(false)

    /** 宿主应在本回调里到主线程刷新表情面板适配器。 */
    @Volatile
    var onDataRefreshed: (() -> Unit)? = null

    /** 授权失效等错误，宿主应以 Toast/提示明确展示重新授权。 */
    @Volatile
    var onAccessError: ((String) -> Unit)? = null

    /** 分类列表是否已成功发布过；未发布时调用方必须保留现有内置/自定义面板。 */
    fun isPanelsSnapshotReady(): Boolean = panelsPublished

    /** 进程启动时预热分类列表，避免第一次打开面板只有“加载中”的空白。 */
    fun preload() {
        if (AppParasitics.appContext == null) return
        requestPanelsRefresh(force = true)
    }

    fun invalidateCache() {
        providerGeneration++
        synchronized(panelsLock) { panelsLoadedAt = 0L }
        panelsMap.values.forEach { it.invalidate() }
        requestPanelsRefresh(force = true)
        notifyRefreshed()
    }

    private fun notifyRefreshed() {
        if (!refreshNotifyPending.compareAndSet(false, true)) return
        mainHandler.post {
            refreshNotifyPending.set(false)
            onDataRefreshed?.invoke()
        }
    }

    private fun reportAccessError(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() }
        val message = when {
            error is ImageAccessException && detail != null -> detail
            detail != null -> detail
            else -> "图片目录授权已失效，请在模块设置中重新选择授权目录"
        }
        val now = System.currentTimeMillis()
        synchronized(errorLock) {
            if (message == lastErrorMessage && now - lastErrorAt < ERROR_NOTIFY_INTERVAL_MS) return
            lastErrorMessage = message
            lastErrorAt = now
        }
        Log.warn("表情面板目录不可用: $message", error)
        mainHandler.post { onAccessError?.invoke(message) }
    }

    /** 分类枚举在后台执行；主线程只读取上一份快照。 */
    private fun requestPanelsRefresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(panelsLock) {
            if (!force && panelsLoadedOnce && now - panelsLoadedAt < REFRESH_INTERVAL_MS) return
            if (panelsLoading) return
            panelsLoading = true
        }
        submitLoad { refreshPanels() }
    }

    private fun refreshPanels() {
        val generation = providerGeneration
        val result = runCatching { buildPanels(generation) }
        if (generation != providerGeneration) {
            synchronized(panelsLock) {
                panelsLoading = false
                panelsLoadedAt = 0L
            }
            return
        }
        val panels = result.getOrElse { error ->
            synchronized(panelsLock) {
                panelsLoading = false
                panelsLoadedAt = System.currentTimeMillis()
                panelsLoadedOnce = true
            }
            reportAccessError(error)
            return
        }
        val signature = panels.filterIsInstance<Panel>()
            .joinToString("\n") { it.folderKey() }
        var changed = false
        synchronized(panelsLock) {
            if (signature != panelsSignature || !panelsPublished) {
                panelsSignature = signature
                panelSnapshot = panels
                changed = true
            }
            panelsLoadedAt = System.currentTimeMillis()
            panelsLoadedOnce = true
            panelsPublished = true
            panelsLoading = false
        }
        if (changed) notifyRefreshed()
    }

    private fun buildPanels(generation: Int): List<ExtraEmoticonPanel> {
        val panels = mutableListOf<Pair<ExtraEmoticonPanel, ImageEntry>>()
        val seen = mutableSetOf<String>()
        // 一次扫描同时给出分类与各分类的图片；存在性判断复用这份快照，
        // 后续打开面板不再重复列目录。授权根或已配置来源失效时这里会抛
        // ImageAccessException，由刷新任务明确提示，绝不缓存成空列表。
        val scan = ImageFolderStore.scanFolders(includeExternal = true)
        for (folder in scan.folders) {
            if (ImageFolderStore.isHistoryFolder(folder)) continue
            if (!folder.isDirectory) continue
            val key = folder.key
            if (!seen.add(key)) continue
            val existing = panelsMap[key]
            if (existing != null) {
                // 尚未打开的首次加载快照跟随本次扫描换代；已加载的面板不受影响。
                scan.imagesByFolder[key]?.let { existing.updatePending(it, generation) }
                panels.add(existing to folder)
                continue
            }
            // 扫描未覆盖的目录（如历史占位、扫描后被删掉的目录）才回退到实时枚举。
            val images = scan.imagesByFolder[key]?.let { Result.success(it) }
                ?: runCatching { ImageFolderStore.images(folder) }
            if (images.isFailure) {
                images.exceptionOrNull()?.let { error -> reportAccessError(error) }
                continue
            }
            val available = images.getOrDefault(emptyList())
            if (available.isEmpty()) continue
            val panel = Panel(folder, folder.name, available, generation)
            panelsMap[key] = panel
            panels.add(panel to folder)
        }
        val sorted = panels.sortedByDescending { (_, folder) ->
            runCatching { ImageFolderStore.folderUsage(folder) }.getOrDefault(0)
        }.map { (panel, _) -> panel }
        return listOf(historyPanel) + sorted
    }

    override fun extraEmoticonList(): List<ExtraEmoticonPanel> {
        requestPanelsRefresh()
        return panelSnapshot.ifEmpty { listOf(historyPanel) }
    }

    override fun uniqueId(): String = "LocalDocumentEmoticonProvider"
}
