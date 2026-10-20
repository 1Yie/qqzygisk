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

private val mainHandler = Handler(Looper.getMainLooper())

private fun submitLoad(task: () -> Unit) {
    runCatching { emoticonLoader.execute(task) }
        .onFailure { Log.error("无法调度表情加载任务", it) }
}

private class BuiltEmoticons(
    val items: List<ExtraEmoticon>,
    val signature: String,
)

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

private fun buildEmoticonInfos(
    entries: List<ImageEntry>,
    groupId: String,
    onError: (Throwable) -> Unit,
): BuiltEmoticons {
    if (entries.isEmpty()) return BuiltEmoticons(emptyList(), "")
    val infoConstructor = runCatching {
        "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo"
            .toAppClass()
            .resolve()
            .firstConstructor()
    }.getOrElse {
        Log.error("无法解析收藏表情类", it)
        return BuiltEmoticons(emptyList(), "")
    }
    val next = mutableListOf<ExtraEmoticon>()
    val signature = StringBuilder()
    for (entry in entries) {
        val path = nativePathOf(entry, onError) ?: continue
        signature.append(entry.key).append('\u0001').append(path).append('\n')
        next.add(
            object : ExtraEmoticon() {
                val info = infoConstructor.create()
                init {
                    info.set("path", path)
                    info.set("actionData", "$groupId:${entry.key}")
                }
                override fun QQEmoticonObject(): Any = info
            },
        )
    }
    return BuiltEmoticons(next, signature.toString())
}

class LocalDocumentEmoticonProvider : ExtraEmoticonProvider() {
    private inner class Panel(val entry: ImageEntry, val id: String) : ExtraEmoticonPanel() {
        private val lock = Any()
        private var snapshot: List<ExtraEmoticon> = emptyList()
        private var signature = ""
        private var loadedAt = 0L
        private var loading = false
        private var loadedOnce = false
        private var published = false
        private var iconPath: String? = null
        private var iconLoading = false

        fun invalidate() {
            synchronized(lock) { loadedAt = 0L }
        }

        override fun isSnapshotReady(): Boolean = synchronized(lock) { published }

        override fun emoticons(): List<ExtraEmoticon> {
            requestRefresh()
            return synchronized(lock) { snapshot }
        }

        override fun emoticonPanelIconURL(): String? {
            synchronized(lock) { iconPath?.takeIf { File(it).isFile } }?.let { return "file://$it" }
            requestIcon()
            return synchronized(lock) {
                iconPath?.takeIf { File(it).isFile }?.let { "file://$it" }
            }
        }

        override fun uniqueId(): String = id

        private fun requestRefresh() {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (loadedOnce && now - loadedAt < REFRESH_INTERVAL_MS) return
                if (loading) return
                loading = true
            }
            submitLoad { refresh() }
        }

        private fun refresh() {
            val generation = providerGeneration
            val entries = runCatching { ImageFolderStore.images(entry) }.getOrElse { error ->
                synchronized(lock) {
                    loading = false
                    loadedAt = System.currentTimeMillis()
                    loadedOnce = true
                }
                reportAccessError(error)
                return
            }
            val built = buildEmoticonInfos(entries, id) { reportAccessError(it) }
            val icon = runCatching { resolveIconPath() }.getOrNull()
            if (generation != providerGeneration) {
                // 期间目录被改写：丢弃旧结果，下次查询立即重算。
                synchronized(lock) {
                    loading = false
                    loadedAt = 0L
                }
                return
            }
            var changed = false
            synchronized(lock) {
                if (built.signature != signature) {
                    signature = built.signature
                    snapshot = built.items
                    changed = true
                }
                if (icon != null && icon != iconPath) {
                    iconPath = icon
                    changed = true
                }
                loadedAt = System.currentTimeMillis()
                loadedOnce = true
                published = true
                loading = false
            }
            if (changed) notifyRefreshed()
        }

        private fun requestIcon() {
            synchronized(lock) {
                if (iconLoading) return
                iconLoading = true
            }
            submitLoad {
                val icon = runCatching { resolveIconPath() }.getOrNull()
                var changed = false
                synchronized(lock) {
                    if (icon != null && icon != iconPath) {
                        iconPath = icon
                        changed = true
                    }
                    iconLoading = false
                }
                if (changed) notifyRefreshed()
            }
        }

        private fun resolveIconPath(): String? {
            val namedCover = runCatching {
                entry.children().firstOrNull {
                    it.isFile && it.name.startsWith("__cover__.")
                }
            }.getOrNull()
            val target = namedCover
                ?: runCatching { ImageFolderStore.coverFile(entry) }.getOrNull()
                ?: runCatching { ImageFolderStore.images(entry).firstOrNull() }.getOrNull()
            return target?.let { nativePathOf(it) { error -> reportAccessError(error) } }
        }
    }

    private inner class HistoryPanel : ExtraEmoticonPanel() {
        private val lock = Any()
        private var snapshot: List<ExtraEmoticon> = emptyList()
        private var signature = ""
        private var loadedAt = 0L
        private var loading = false
        private var loadedOnce = false
        private var published = false

        override fun uniqueId(): String = ImageFolderStore.HISTORY_DIR_NAME

        override fun isSnapshotReady(): Boolean = synchronized(lock) { published }

        override fun emoticons(): List<ExtraEmoticon> {
            requestRefresh()
            return synchronized(lock) { snapshot }
        }

        override fun emoticonPanelIconURL(): String? = null

        private fun requestRefresh() {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (loadedOnce && now - loadedAt < REFRESH_INTERVAL_MS) return
                if (loading) return
                loading = true
            }
            submitLoad { refresh() }
        }

        private fun refresh() {
            val generation = providerGeneration
            val entries = runCatching {
                ImageFolderStore.images(ImageFolderStore.historyFolder())
            }.getOrElse { error ->
                synchronized(lock) {
                    loading = false
                    loadedAt = System.currentTimeMillis()
                    loadedOnce = true
                }
                reportAccessError(error)
                return
            }
            val built = buildEmoticonInfos(entries, uniqueId()) { reportAccessError(it) }
            if (generation != providerGeneration) {
                synchronized(lock) {
                    loading = false
                    loadedAt = 0L
                }
                return
            }
            var changed = false
            synchronized(lock) {
                if (built.signature != signature) {
                    signature = built.signature
                    snapshot = built.items
                    changed = true
                }
                loadedAt = System.currentTimeMillis()
                loadedOnce = true
                published = true
                loading = false
            }
            if (changed) notifyRefreshed()
        }
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
        val result = runCatching { buildPanels() }
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
            .joinToString("\n") { it.entry.key }
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

    private fun buildPanels(): List<ExtraEmoticonPanel> {
        val panels = mutableListOf<ExtraEmoticonPanel>()
        val seen = mutableSetOf<String>()
        // 授权根或已配置来源失效时这里会抛 ImageAccessException，由刷新任务明确提示，绝不缓存成空列表。
        for (folder in ImageFolderStore.folders(includeExternal = true)) {
            if (ImageFolderStore.isHistoryFolder(folder)) continue
            if (!folder.isDirectory) continue
            val key = folder.key
            if (!seen.add(key)) continue
            val existing = panelsMap[key]
            if (existing != null) {
                panels.add(existing)
                continue
            }
            val images = runCatching { ImageFolderStore.images(folder) }
            if (images.isFailure) {
                // 目录暂时不可读：保留上次发布的面板（含陈旧快照），并明确提示重新授权，
                // 不能把错误当成“分类被删除”。
                images.exceptionOrNull()?.let { error -> reportAccessError(error) }
                panelsMap[key]?.let { panels.add(it) }
                continue
            }
            val available = images.getOrDefault(emptyList())
            if (available.isEmpty()) continue
            val panel = Panel(folder, folder.name)
            panelsMap[key] = panel
            panels.add(panel)
        }
        val sorted = panels.sortedByDescending { panel ->
            val entry = (panel as? Panel)?.entry ?: return@sortedByDescending 0
            runCatching { ImageFolderStore.folderUsage(entry) }.getOrDefault(0)
        }
        return listOf(historyPanel) + sorted
    }

    override fun extraEmoticonList(): List<ExtraEmoticonPanel> {
        requestPanelsRefresh()
        return panelSnapshot.ifEmpty { listOf(historyPanel) }
    }

    override fun uniqueId(): String = "LocalDocumentEmoticonProvider"
}
