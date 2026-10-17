package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.AioSessionBinder
import com.qm.qqzygisk.hook.app.chat.ChatImageSender
import com.qm.qqzygisk.hook.app.chat.ImagePanelAction
import com.qm.qqzygisk.hook.app.chat.SaveImagePanel
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log

/**
 * 长按 QQ 聊天输入栏表情图标，打开自定义图片面板。
 *
 * 这里只安装框架层（View/Activity）的图标发现 hook；聊天会话绑定 hook 由
 * [installHostHooks] 在宿主类可加载、聊天尚未创建时安装。
 */
object EmoticonButtonHooker : BaseHooker() {
    override val key = "emoticon_image_panel"
    override val name = "长按表情按钮"
    override val description = "长按聊天框表情图标打开图片面板"
    override val defaultEnabled = false

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)
    private const val PANEL_ACTION_KEY = "emoticon_panel_send_type"
    private const val ATTACHED_TAG = 0x51A1E201

    override fun initOnce() {
        View::class.resolve()
            .firstMethod {
                name = "setOnClickListener"
                parameters(View.OnClickListener::class)
            }
            .hook {
                after {
                    attachIfChatEmoticonIcon(instance as? View ?: return@after)
                }
            }

        View::class.resolve()
            .firstMethod {
                name = "setContentDescription"
                parameters(CharSequence::class)
            }
            .hook {
                after {
                    attachIfChatEmoticonIcon(instance as? View ?: return@after)
                }
            }

        Activity::class.resolve()
            .firstMethod { name = "onResume" }
            .hook {
                after {
                    val activity = instance as? Activity ?: return@after
                    attachById(activity)
                    activity.window?.decorView?.let(::scanTree)
                }
            }
    }

    /**
     * 在宿主类加载器可用、聊天尚未创建时安装会话绑定 hook。
     * 由宿主生命周期入口在首个 Activity onCreate 前调用；安装失败会抛出，
     * 且不会置位，宿主后续生命周期入口可以重试。
     */
    fun installHostHooks() {
        AioSessionBinder.install()
    }

    private fun attachById(activity: Activity) {
        val packageName = activity.packageName
        listOf("emo_btn", "input_emotion", "emotion_btn", "qq_aio_panel_emotion").forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", packageName)
            if (id == 0) return@forEach
            activity.findViewById<View>(id)?.let(::forceAttach)
        }
    }

    private fun scanTree(root: View) {
        attachIfChatEmoticonIcon(root)
        if (resourceName(root) == "emo_btn") forceAttach(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                scanTree(root.getChildAt(index))
            }
        }
    }

    private fun forceAttach(view: View) {
        view.setTag(ATTACHED_TAG, null)
        attachIfChatEmoticonIcon(view, force = true)
    }

    private fun attachIfChatEmoticonIcon(
        view: View,
        force: Boolean = false,
    ) {
        if (view.getTag(ATTACHED_TAG) == true) return
        if (!force && !isChatInputEmoticonIcon(view)) return
        view.setTag(ATTACHED_TAG, true)
        view.isLongClickable = true
        view.setOnLongClickListener {
            if (!enabled) {
                Log.warn("长按表情图标时开关是关闭的")
                return@setOnLongClickListener false
            }
            runCatching {
                SaveImagePanel.show(
                    host = it.context,
                    actions = panelActions(AioSessionBinder.sessionFor(it)),
                    initialActionId = HookSettings.getString(
                        PANEL_ACTION_KEY,
                        ChatImageSender.SendType.IMAGE.name,
                    ),
                    onActionSelected = { actionId ->
                        HookSettings.setString(PANEL_ACTION_KEY, actionId)
                    },
                )
            }.onFailure { error ->
                Log.error("打开图片面板失败", error)
            }
            true
        }
        Log.info(
            "已挂钩聊天框表情图标: class=${view.javaClass.name} " +
                "id=${resourceName(view)} desc=${view.contentDescription}",
        )
    }

    private fun panelActions(session: AioSessionBinder.BoundSession?): List<ImagePanelAction> =
        ChatImageSender.SendType.entries.map { type ->
            val isEmoticon = type == ChatImageSender.SendType.EMOTICON
            ImagePanelAction(
                id = type.name,
                label = type.label,
                iconRes = if (isEmoticon) {
                    R.drawable.ic_send_emoticon
                } else {
                    R.drawable.ic_send_image
                },
                contentDescription = "发送为${type.label}",
                perform = { file -> ChatImageSender.sendImage(file, type, session) },
            )
        }

    private fun isChatInputEmoticonIcon(view: View): Boolean {
        if (view !is ImageView && view !is ImageButton) return false
        val description = view.contentDescription?.toString()?.trim().orEmpty()
        if (description.contains("商城") || description.contains("搜索") || description.contains("回复")) {
            return false
        }
        if (description == "表情" || description.endsWith("表情")) return true
        val name = resourceName(view)
        return name == "emo_btn" ||
            name.contains("emo_btn") ||
            name.contains("emotion", ignoreCase = true) ||
            name.contains("emoticon", ignoreCase = true)
    }

    private fun resourceName(view: View): String {
        return runCatching {
            if (view.id == View.NO_ID) return@runCatching ""
            view.resources.getResourceEntryName(view.id)
        }.getOrNull().orEmpty()
    }
}
