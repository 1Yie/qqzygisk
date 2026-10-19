package com.qm.qqzygisk.hook.parasitic

/** A Resources object may get a new backing implementation after its first injection. */
internal fun ensureModuleResource(
    isReadable: () -> Boolean,
    attach: () -> Int,
) {
    if (isReadable()) return
    check(attach() > 0) { "模块资源 APK 未能加载（addAssetPath 返回 0）" }
    check(isReadable()) { "模块资源 APK 已挂载，但模块颜色资源仍不可读" }
}
