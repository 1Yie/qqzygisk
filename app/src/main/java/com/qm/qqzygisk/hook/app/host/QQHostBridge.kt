package com.qm.qqzygisk.hook.app.host

import android.app.Activity
import android.view.View
import com.qm.qqzygisk.hook.app.chat.AioSessionBinder
import com.qm.qqzygisk.hook.app.chat.ChatImageSender
import com.qm.qqzygisk.hook.app.hooker.SettingHooker
import java.io.File

/** QQ 的宿主实现：会话走 AIO 绑定，发送走 NT 的 IMsgService/IMsgUtilApi。 */
internal object QQHostBridge : HostBridge {
    override val packageName: String = "com.tencent.mobileqq"

    override val supportedKinds: List<SendKind> = listOf(SendKind.IMAGE, SendKind.EMOTICON)

    override val proxyActivityClass: Class<*>
        get() = Class.forName("com.tencent.mobileqq.activity.photo.CameraPreviewActivity", false, appLoader())

    override fun installBeforeChat() {
        AioSessionBinder.install()
    }

    override fun sessionFor(view: View): ChatSessionRef? =
        AioSessionBinder.sessionFor(view)?.let(::QqSessionRef)

    override fun send(file: File, kind: SendKind, session: ChatSessionRef?): Result<Unit> =
        ChatImageSender.sendToContact(file, kind.toSendType()) { contactFor(session) }

    /** The panel may decorate the session to enforce cancellation; do not downcast it. */
    internal fun contactFor(session: ChatSessionRef?): Any =
        when (val current = session?.resolve() ?: SessionTarget.Unavailable) {
            is SessionTarget.Ready -> current.target
            SessionTarget.Changed -> error("聊天会话已切换，请重新打开面板")
            SessionTarget.Unavailable -> error("没有可用的聊天会话，请重新进入聊天页面")
        }

    override fun installSettingsEntry(activity: Activity): Boolean = SettingHooker.load()

    private fun appLoader(): ClassLoader =
        com.qm.qqzygisk.hook.app.data.HostData.appClassLoader

    private fun SendKind.toSendType(): ChatImageSender.SendType = when (this) {
        SendKind.IMAGE -> ChatImageSender.SendType.IMAGE
        SendKind.EMOTICON -> ChatImageSender.SendType.EMOTICON
    }

    private class QqSessionRef(private val session: AioSessionBinder.BoundSession) : ChatSessionRef {
        override fun resolve(): SessionTarget = when (val live = session.liveContact()) {
            is AioSessionBinder.LiveContact.Ready -> SessionTarget.Ready(live.contact)
            AioSessionBinder.LiveContact.Changed -> SessionTarget.Changed
            AioSessionBinder.LiveContact.Unavailable -> SessionTarget.Unavailable
        }
    }
}
