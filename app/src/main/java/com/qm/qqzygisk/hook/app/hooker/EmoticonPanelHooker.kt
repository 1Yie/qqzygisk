package com.qm.qqzygisk.hook.app.hooker

import android.app.Application
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.Toast
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.MethodCall
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.get
import com.qm.qqzygisk.hook.utils.set
import org.lsposed.lsparanoid.Obfuscate
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.properties.Delegates

object EmoticonPanelHooker : BaseHooker() {
    override val key = "emoticon_panel"
    override val name = "表情面板内置"

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    val EmoticonPackage = "com.tencent.mobileqq.data.EmoticonPackage".toAppClass()
    val EmotionPanelInfo = "com.tencent.mobileqq.emoticonview.EmotionPanelInfo".toAppClass()
    val EmoticonPanelController = "com.tencent.mobileqq.emoticonview.EmoticonPanelController".toAppClass()
    val EmotionPanelViewPagerAdapter = "com.tencent.mobileqq.emoticonview.EmotionPanelViewPagerAdapter".toAppClass()
    val FavoriteEmoticonInfo = "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo".toAppClass()
    val EmoticonTabAdapter = "com.tencent.mobileqq.emoticonview.EmoticonTabAdapter".toAppClass()

    val AbsBigEmotionUpdateAdapter = "com.tencent.mobileqq.emoticonview.AbsBigEmotionUpdateAdapter".toAppClass()

    var providers by Delegates.notNull<List<ExtraEmoticonProvider>>()
    var lastPanelDataSize = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastController: WeakReference<Any>? = null
    private var lastPanelData: WeakReference<MutableList<Any>>? = null
    private var lastAdapter: WeakReference<Any>? = null
    private val tabItemIcons = java.util.WeakHashMap<Any, String?>()
    private val tabItemConstructor by lazy {
        "com.tencent.mobileqq.emoticonview.EmoticonTabAdapter\$EmoticonTabItem"
            .toAppClass().resolve().firstConstructor { parameterCount = 0 }
    }
    private val pageLists = CopyOnWriteArrayList<RetainedPage>()
    private const val MAX_RETAINED_PAGES = 32
    private var historyTabIconState: Drawable.ConstantState? = null

    private data class RetainedPage(
        val providerId: String,
        val panelId: String,
        // 宿主复制后不再持有原列表；由弱 adapter 的生命周期约束这份对齐状态。
        val list: MutableList<Any>,
        val position: Int,
        val info: WeakReference<Any>,
        val pager: WeakReference<Any>,
        val adapter: WeakReference<Any>,
    )

    data class QZEpId (var providerId: String = "",var panelId: String)
    fun parseQZEpId(epId: String): QZEpId? {
        if (!epId.startsWith("qz:")) return null
        val data = epId.substring(3)
        val providerId = data.substring(0, data.indexOf(":"))
        val panelId = data.substring(data.indexOf(":") + 1)
        return QZEpId(providerId, panelId)
    }

    private fun generateTabUrl(method: MethodCall) {
        val epId = method.args[0] as? String? ?: return
        if (!epId.startsWith("qz:")) return
        val id = parseQZEpId(epId)
        if(id != null) {
            val provider = providers.find { it.uniqueId() == id.providerId }
            if(provider != null) {
                val panel = provider.extraEmoticonList().find { it.uniqueId() == id.panelId }
                if(panel != null) {
                    val url = panel.emoticonPanelIconURL()
                    method.result = url?.let(::URL)
                }
            }
        }
    }

    private fun getHistoryTabDrawable(method: MethodCall) {
        val item = method.args[0] ?: return
        val id = item.get<String>("epId")?.let(::parseQZEpId) ?: return
        if (id.panelId != ImageFolderStore.HISTORY_DIR_NAME) return
        if (providers.none { it is LocalDocumentEmoticonProvider && it.uniqueId() == id.providerId }) return
        val context = AppParasitics.appContext ?: return
        val state = historyTabIconState ?: run {
            AppParasitics.ensureModuleAppResources(context.resources)
            context.getDrawable(R.drawable.ic_history)?.constantState
                ?.also { historyTabIconState = it }
        } ?: return
        val drawable = state.newDrawable(context.resources).mutate()
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        drawable.setTint(if (night) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY)
        method.result = drawable
    }

    private fun getPanelDataList(method: MethodCall) {
        @Suppress("UNCHECKED_CAST")
        val result = method.result as? MutableList<Any>? ?: return

        filterPanelData(result)
        lastController = WeakReference(method.instance)
        lastPanelData = WeakReference(result)
        reconcileExtraPanels(result)
        if (lastPanelDataSize != result.size) {
            lastPanelDataSize = result.size
            notifyPanelViews(method.instance)
        }
        method.result = result
    }

    /** 只保留内置可用类型；模块自己的 qz 面板由 [reconcileExtraPanels] 对齐快照。 */
    private fun filterPanelData(result: MutableList<Any>) {
        val typeWhiteList = mutableSetOf(
//            13, // 表情商城,
            18, // 搜索表情,
            7, // Emoji 表情,
            4, // 收藏表情,
//            6, // 商店表情
//            12, // GIF
//            17, // QQ秀专属表情
            19, // 超级表情
//            20, // AI 表情
        )

        val iterator = result.iterator()
        while(iterator.hasNext()) {
            val element = iterator.next()
            val type = element.get<Int>("type")!!
            if(!typeWhiteList.contains(type)) {
                if (type == 6) {
                    val id: String = element.get<Any>("emotionPkg")!!.get("epId")!!
                    if(!id.startsWith("qz:")) iterator.remove()
                } else {
                    iterator.remove()
                }
            }
        }
    }

    /**
     * 只对模块自己拥有的 qz 面板做“对齐快照”：删除已消失的分类，补齐新分类；
     * 宿主内置/非模块面板原样保留。分类列表尚未发布（加载中或首次失败）时完全不动。
     */
    private fun reconcileExtraPanels(result: MutableList<Any>) {
        val desiredPanels = mutableListOf<Pair<String, ExtraEmoticonPanel>>()
        val desiredSet = linkedSetOf<String>()
        for (provider in providers) {
            for (panel in provider.extraEmoticonList()) {
                val epId = "qz:${provider.uniqueId()}:${panel.uniqueId()}"
                if (desiredSet.add(epId)) desiredPanels.add(epId to panel)
            }
        }
        // extraEmoticonList() 同时调度后台扫描，必须先触达再检查发布状态。
        // 启动预热可能因设置/Context 尚未就绪而跳过，不能用 not ready 挡住首次加载。
        if (providers.any { !providerSnapshotReady(it) }) return

        val iterator = result.iterator()
        while (iterator.hasNext()) {
            val epId = epIdOf(iterator.next()) ?: continue
            if (epId.startsWith("qz:") && !desiredSet.contains(epId)) {
                iterator.remove()
            }
        }

        val present = result.mapNotNullTo(mutableSetOf()) { epIdOf(it) }
        if (present.containsAll(desiredSet)) return

        val infoObj = EmotionPanelInfo.resolve().firstConstructor{
            parameterCount = 3
            parameters(Int::class.java, Int::class.java, EmoticonPackage)
        }

        val packObj = EmoticonPackage.resolve().firstConstructor()

        for ((epId, _) in desiredPanels) {
            if (!present.add(epId)) continue
            val pack = packObj.create()
            pack.set("epId", epId)
            pack.set("name", "ExtraSticker")
            pack.set("type", 3)
            pack.set("ipJumpUrl", "")
            pack.set("ipDetail", "QZ")
            pack.set("valid", true)
            pack.set("status", 2)
            pack.set("latestVersion", 1488377358)
            pack.set("aio", true)
            val info = infoObj.create(6, 4, pack)
            result.add(info)
        }
    }

    private fun providerSnapshotReady(provider: ExtraEmoticonProvider): Boolean =
        (provider as? LocalDocumentEmoticonProvider)?.isPanelsSnapshotReady() ?: true

    private fun epIdOf(element: Any): String? = runCatching {
        (element.get<Any>("emotionPkg") ?: return@runCatching null).get<String>("epId")
    }.getOrNull()

    private fun actionDataOf(element: Any): String? =
        runCatching { element.get<String>("actionData") }.getOrNull()

    private fun pathOf(element: Any): String? =
        runCatching { element.get<String>("path") }.getOrNull()

    private fun notifyPanelViews(controller: Any?) {
        runCatching {
            val adapter = if (controller != null) {
                controller.asResolver().firstMethod { name = "getPageAdapter" }.invoke()
            } else {
                lastAdapter?.get()
            } ?: return
            lastAdapter = WeakReference(adapter)
            adapter.asResolver()
                .firstMethod {
                    name = "notifyDataSetChanged"
                    superclass()
                }
                .invoke()
        }.onFailure { Log.warn("刷新表情面板视图失败", it) }
        refreshTabViews(controller)
    }

    /** tab 的 mData 是页面列表的独立副本；后台发布后必须同步，不能只刷新 pager。 */
    private fun refreshTabViews(controller: Any?) {
        if (controller == null || providers.any { !providerSnapshotReady(it) }) return
        val panelData = lastPanelData?.get() ?: return
        runCatching {
            val adapter = controller.asResolver()
                .firstMethod { name = "getEmoticonTabAdapter" }.invoke() ?: return
            val current = adapter.get<List<Any>>("mData") ?: return
            val existing = HashMap<String, Any>()
            val next = ArrayList<Any>(panelData.size)
            for (item in current) {
                val epId = item.get<String>("epId")
                if (epId?.startsWith("qz:") == true) existing[epId] = item
                else next.add(item)
            }
            val panels = HashMap<String, ExtraEmoticonPanel>()
            for (provider in providers) {
                for (panel in provider.extraEmoticonList()) {
                    panels["qz:${provider.uniqueId()}:${panel.uniqueId()}"] = panel
                }
            }
            for (info in panelData) {
                val epId = epIdOf(info) ?: continue
                val panel = panels[epId] ?: continue
                val icon = panel.emoticonPanelIconURL()
                val old = existing[epId]
                val item = if (old != null && tabItemIcons.containsKey(old) && tabItemIcons[old] == icon) {
                    old
                } else {
                    tabItemConstructor.create().also {
                        it.set("type", info.get<Int>("type")!!)
                        it.set("epId", epId)
                        it.set("description", if (panel.uniqueId() == ImageFolderStore.HISTORY_DIR_NAME) "历史" else panel.uniqueId())
                        // 新图标就绪时重建模块 tab，清除宿主首次空 URL 的 completed 状态。
                        tabItemIcons[it] = icon
                    }
                }
                next.add(item)
            }
            if (current.size == next.size && current.indices.all { current[it] === next[it] }) return
            adapter.asResolver().firstMethod { name = "updateData" }.invoke(next)
            adapter.asResolver().firstMethod {
                name = "notifyDataSetChanged"
                superclass()
            }.invoke()
        }.onFailure { Log.warn("同步表情 tab 数据失败", it) }
    }

    /** 后台快照就绪后到主线程重建数据源并刷新；加载中的空列表只是瞬时状态。 */
    private fun onProviderDataReady() {
        mainHandler.post {
            lastPanelDataSize = -1
            lastPanelData?.get()?.let { data ->
                runCatching {
                    reconcileExtraPanels(data)
                    lastPanelDataSize = data.size
                }.onFailure { Log.warn("重建表情面板列表失败", it) }
            }
            refreshPageEmoticons()
            notifyPanelViews(lastController?.get())
        }
    }

    /** 宿主网格复制了返回列表，后台快照必须重新走宿主的数据完成回调。 */
    private fun refreshPageEmoticons() {
        pageLists.removeAll { it.adapter.get() == null || it.pager.get() == null }
        val controller = lastController?.get() ?: return
        val root = controller.asResolver().firstMethod { name = "getViewPager" }.invoke() as? android.view.View ?: return
        fun findGrid(view: android.view.View, adapter: Any): android.widget.ListView? {
            if (view is android.widget.ListView && view.adapter === adapter) return view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findGrid(view.getChildAt(i), adapter)?.let { return it }
                }
            }
            return null
        }
        for (page in pageLists) {
            val list = page.list
            runCatching {
                if (!reconcileEmoticons(page.providerId, page.panelId, list)) return@runCatching
                val pager = page.pager.get() ?: return@runCatching
                val adapter = page.adapter.get() ?: return@runCatching
                val info = page.info.get() ?: return@runCatching
                val grid = findGrid(root, adapter) ?: return@runCatching
                pager.asResolver().firstMethod { name = "onGetPanelDataResult" }
                    .invoke(page.position, info, adapter, grid, list.toList())
            }.onFailure { Log.warn("刷新表情图片网格失败", it) }
        }
    }

    /**
     * 只替换模块拥有的页面条目（actionData 前缀 = “panelId:”）：
     * 已删除的图片移除，内容更新（新的实质化路径）替换旧对象，非模块条目原样保留。
     * 面板快照尚未成功发布时保留现有列表，加载/错误不会被当成删除。
     */
    private fun reconcileEmoticons(providerId: String, panelId: String, list: MutableList<Any>): Boolean {
        val provider = providers.find { it.uniqueId() == providerId } ?: return false
        val panel = provider.extraEmoticonList().find { it.uniqueId() == panelId }
        if (panel == null) {
            val oldSize = list.size
            if (providerSnapshotReady(provider)) removeOwnedEmoticons(list, panelId)
            return list.size != oldSize
        }
        val prefix = "$panelId:"
        // 先触达 emoticons() 以调度首次异步加载；加载中不修改宿主列表。
        val desired = panel.emoticons().map { it.QQEmoticonObject() }
        if (!panel.isSnapshotReady()) return false
        val desiredSignature = desired.map { "${actionDataOf(it).orEmpty()}|${pathOf(it).orEmpty()}" }

        val ownedIndexes = mutableListOf<Int>()
        val currentSignature = mutableListOf<String>()
        list.forEachIndexed { index, item ->
            val action = actionDataOf(item) ?: return@forEachIndexed
            if (!action.startsWith(prefix)) return@forEachIndexed
            ownedIndexes.add(index)
            currentSignature.add("$action|${pathOf(item).orEmpty()}")
        }
        if (currentSignature == desiredSignature) return false

        for (index in ownedIndexes.asReversed()) {
            list.removeAt(index)
        }
        list.addAll(desired)
        return true
    }

    private fun removeOwnedEmoticons(list: MutableList<Any>, panelId: String) {
        val prefix = "$panelId:"
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val action = actionDataOf(iterator.next()) ?: continue
            if (action.startsWith(prefix)) iterator.remove()
        }
    }

    private fun getEmotionPanelData(method: MethodCall) {
        val emotionPanelInfo = method.args[2]!!
        @Suppress("UNCHECKED_CAST")
        val list = method.result as MutableList<Any>
        val pkg = emotionPanelInfo.get<Any>("emotionPkg") ?: return
        val epId = pkg.get<String>("epId") ?: return
        val id = parseQZEpId(epId)
        if(id != null) {
            pageLists.removeAll { it.adapter.get() == null || it.adapter.get() === method.args[1] }
            pageLists.add(RetainedPage(
                id.providerId, id.panelId, list,
                method.args[0] as Int, WeakReference(emotionPanelInfo),
                WeakReference(method.instance), WeakReference(method.args[1]!!),
            ))
            while (pageLists.size > MAX_RETAINED_PAGES) {
                pageLists.removeAt(0)
            }
            reconcileEmoticons(id.providerId, id.panelId, list)
            method.result = list
        }
    }

    private fun handleIPSite(method: MethodCall) {
        val emotionPanelInfo = method.args[0]
        if(emotionPanelInfo != null && parseQZEpId(emotionPanelInfo.get<String>("epId")!!) != null) {
            method.result = null
            return
        }
    }

    private fun getDrawable(method: MethodCall) {
        val getZoomDrawable = method.instance.asResolver().firstMethod { name = "getZoomDrawable" }
        method.result = getZoomDrawable.invoke<Drawable>(method.args[0], method.args[1], 170, 170)
    }

    private fun updateBigEmotionContentViewData(method: MethodCall) {
        val view = method.args[0] as RelativeLayout
        val imageView = view.getChildAt(1) as ImageView
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    override fun initOnce() {
        val provider = LocalDocumentEmoticonProvider()
        providers = listOf(provider)
        provider.onDataRefreshed = { if (enabled) onProviderDataReady() }
        provider.onAccessError = { message ->
            if (enabled) {
                val host = AppParasitics.appContext
                if (host != null) {
                    Toast.makeText(host, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        if (enabled) provider.preload()
        ImageFolderStore.addListener {
            provider.invalidateCache()
            lastPanelDataSize = -1
        }

        EmoticonPanelController
            .resolve()
            .firstMethod { name = "getPanelDataList" }
            .hook {
                after {
                    if (enabled) getPanelDataList(this)
                }
            }

        EmotionPanelViewPagerAdapter
            .resolve()
            .firstMethod { name = "getEmotionPanelData" }
            .hook {
                after {
                    if (enabled) getEmotionPanelData(this)
                }
            }

        EmotionPanelViewPagerAdapter
            .resolve()
            .firstMethod { name = "handleIPSite" }
            .hook {
                before {
                    if (enabled) handleIPSite(this)
                }
            }

        FavoriteEmoticonInfo
            .resolve()
            .firstMethod { name = "getDrawable" }
            .hook {
                before {
                    if (enabled) getDrawable(this)
                }
            }


        EmoticonTabAdapter
            .resolve()
            .firstMethod { name = "generateTabUrl" }
            .hook {
                before {
                    if (enabled) generateTabUrl(this)
                }
            }

        EmoticonTabAdapter
            .resolve()
            .firstMethod { name = "getTabDrawable" }
            .hook {
                before {
                    if (enabled) getHistoryTabDrawable(this)
                }
            }

        AbsBigEmotionUpdateAdapter
            .resolve()
            .firstMethod { name = "updateBigEmotionContentViewData" }
            .hook {
                after {
                    if (enabled) updateBigEmotionContentViewData(this)
                }
            }

        if (android.app.Application.getProcessName().contains(':')) return
        runCatching {
            FavoriteEmoticonInfo
                .resolve()
                .method {
                    name = "send"
                }
                .hookAll {
                    after {
                        if (!enabled) return@after
                        val path = instance.get<String>("path") ?: return@after
                        recordEmoticonSendUsage(path)
                    }
                }
        }.onFailure {
            Log.warn("挂钩 FavoriteEmoticonInfo.send 失败", it)
        }
    }
}
