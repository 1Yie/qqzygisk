package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.InvocationTargetException

/** WeChat 8.0.79: EmojiMgrImpl.importEmojiFromFile -> EmojiMgrImpl.sendEmoji. */
internal object WeChatEmoticonSender {
    fun send(file: File, talker: String?): Result<Unit> = runCatching {
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        val result = WeChatEmoticonSubmission(NativeApi(context, appClassLoader))
            .send(file, talker).getOrThrow()
        Log.info("已提交微信原生表情发送: format=${result.format} bytes=${result.size} md5=${result.md5.take(8)}")
    }.recoverCatching { error ->
        throw if (error is InvocationTargetException) error.targetException else error
    }

    private class NativeApi(private val context: Context, loader: ClassLoader) : WeChatEmoticonApi {
        private val apiClass = Class.forName("com.tencent.mm.feature.emoji.api.z5", false, loader)
        private val registry = Class.forName("ni5.n0", false, loader)
        private val manager = checkNotNull(
            registry.getMethod("c", Class::class.java).invoke(null, apiClass),
        ) { "微信原生表情服务尚未就绪" }
        private val importMethod = manager.javaClass.getMethod("zj", Context::class.java, String::class.java)
        private val lookupMethod = manager.javaClass.getMethod("Bj", String::class.java)
        private val sendMethod = manager.javaClass.getMethod("Fj", Context::class.java, String::class.java, String::class.java)
        private val vfs = Class.forName("com.tencent.mm.vfs.z6", false, loader)
        private val exists = vfs.getMethod("j", String::class.java)
        private val length = vfs.getMethod("k", String::class.java)

        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon? {
            // Native q72.h.I copies all original bytes, identifies GIF from the header and
            // registers the MD5 as catalog=65/type=2. No bitmap or thumbnail is re-encoded.
            val md5 = importMethod.invoke(manager, context, file.file.absolutePath) as? String
            if (md5.isNullOrBlank()) return null
            val emoji = lookupMethod.invoke(manager, md5) ?: return null
            val path = emoji.javaClass.getMethod("d1").invoke(emoji) as? String
                ?: return null
            val nativeType = (emoji.javaClass.getMethod("getType").invoke(emoji) as Number).toInt()
            return ImportedWeChatEmoticon(
                md5 = md5,
                size = (length.invoke(null, path) as Number).toLong(),
                nativeType = nativeType,
                readable = exists.invoke(null, path) == true,
            )
        }

        override fun submit(talker: String, md5: String): Boolean =
            // Native s0.Fj -> q72.h.W sends an EmojiInfo to WeChat's emoji message engine.
            sendMethod.invoke(manager, context, talker, md5) == true
    }
}
