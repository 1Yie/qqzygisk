package com.qm.qqzygisk.hook.app.host

internal enum class HostType(
    val packageName: String,
    val imagePathKey: String,
    val enablePanelByDefault: Boolean,
) {
    QQ("com.tencent.mobileqq", "qq_zygisk_image_path", false),
    WECHAT("com.tencent.mm", "wx_zygisk_image_path", true),
    DOUYIN("com.ss.android.ugc.aweme", "douyin_zygisk_image_path", true);

    companion object {
        fun fromPackageName(packageName: String?): HostType =
            entries.firstOrNull { it.packageName == packageName } ?: QQ
    }
}
