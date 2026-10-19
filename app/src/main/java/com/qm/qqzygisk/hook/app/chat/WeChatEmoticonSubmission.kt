package com.qm.qqzygisk.hook.app.chat

internal data class ImportedWeChatEmoticon(
    val md5: String,
    val size: Long,
    val nativeType: Int,
    val readable: Boolean,
)

/** The backend exclusively imports and submits native WeChat emoji messages. */
internal interface WeChatEmoticonApi {
    fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon?
    fun submit(talker: String, md5: String): Boolean
}

internal class WeChatEmoticonSubmission(private val api: WeChatEmoticonApi) {
    fun send(file: java.io.File, talker: String?): Result<WeChatEmoticonFile> = runCatching {
        require(!talker.isNullOrBlank()) { "没有可用的聊天会话，请重新进入聊天页面" }
        val source = WeChatEmoticonFile.inspect(file)
        val imported = checkNotNull(api.importFile(source)) { "微信未能导入这个表情文件" }
        check(imported.md5.equals(source.md5, ignoreCase = true)) {
            "表情文件导入前后不一致，请重新打开面板后重试"
        }
        check(imported.readable && imported.size == source.size) {
            "微信表情缓存不可用，请检查微信存储权限"
        }
        if (source.format == WeChatEmoticonFormat.GIF) {
            check(imported.nativeType == 2) { "微信没有将此 GIF 识别为动态表情，已取消发送" }
        }
        check(api.submit(talker, imported.md5)) { "微信拒绝了此次表情发送" }
        source
    }
}
