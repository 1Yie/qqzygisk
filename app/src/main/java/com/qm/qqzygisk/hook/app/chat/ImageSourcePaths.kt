package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.host.HostType

/** Each host uses the same three sources, with defaults inside its own media directory. */
internal data class ImageSourcePaths(
    val images: String,
    val funBox: String,
    val tgStickers: String,
) {
    val scanRoots: List<String> get() = listOf(funBox, tgStickers, images).distinct()
}

internal object HostImagePaths {
    const val QQ_IMAGES = "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqzygisk"
    const val QQ_FUNBOX = "/storage/self/primary/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage"
    const val QQ_TG = "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1"
    const val WECHAT_IMAGES = "/storage/emulated/0/Android/media/com.tencent.mm/.qqzygisk"
    const val WECHAT_FUNBOX = "/storage/self/primary/Android/media/com.tencent.mm/.fun/Sticker/Storage"
    const val WECHAT_TG = "/storage/self/primary/Android/media/com.tencent.mm/TGStickersExported/v1"
    const val DOUYIN_IMAGES = "/storage/emulated/0/Android/media/com.ss.android.ugc.aweme/.qqzygisk"
    const val DOUYIN_FUNBOX = "/storage/self/primary/Android/media/com.ss.android.ugc.aweme/.fun/Sticker/Storage"
    const val DOUYIN_TG = "/storage/self/primary/Android/media/com.ss.android.ugc.aweme/TGStickersExported/v1"

    fun resolve(isWeChat: Boolean, images: String, funBox: String, tgStickers: String): ImageSourcePaths =
        resolve(if (isWeChat) HostType.WECHAT else HostType.QQ, images, funBox, tgStickers)

    fun resolve(host: HostType, images: String, funBox: String, tgStickers: String): ImageSourcePaths {
        val defaults = defaults(host)
        // Earlier WeChat settings saved QQ's default extra sources. Migrate only those defaults;
        // keep explicitly configured custom locations as entered by the user.
        fun extra(path: String, default: String, legacyQqDefault: String): String {
            val trimmed = path.trim().ifEmpty { default }
            val normalized = trimmed.replace("/storage/emulated/0/", "/storage/self/primary/")
            return if (host == HostType.WECHAT && normalized == legacyQqDefault) default else trimmed
        }
        return ImageSourcePaths(
            images.trim().ifEmpty { defaults.images },
            extra(funBox, defaults.funBox, QQ_FUNBOX),
            extra(tgStickers, defaults.tgStickers, QQ_TG),
        )
    }

    fun defaults(isWeChat: Boolean): ImageSourcePaths = defaults(if (isWeChat) HostType.WECHAT else HostType.QQ)

    fun defaults(host: HostType): ImageSourcePaths = when (host) {
        HostType.QQ -> ImageSourcePaths(QQ_IMAGES, QQ_FUNBOX, QQ_TG)
        HostType.WECHAT -> ImageSourcePaths(WECHAT_IMAGES, WECHAT_FUNBOX, WECHAT_TG)
        HostType.DOUYIN -> ImageSourcePaths(DOUYIN_IMAGES, DOUYIN_FUNBOX, DOUYIN_TG)
    }
}
