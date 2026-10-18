package com.qm.qqzygisk.hook.app.hooker

/** Used for every build, including both builds in BaseSettingPrefUI.onCreate. */
internal fun <T : Any> insertWeChatSettingEntry(
    children: List<T>,
    entry: T,
    className: (T) -> String,
): List<T> {
    if (children.any { it === entry }) return children
    val anchor = children.indexOfFirst { className(it) == "SettingGroupPersonalInfo" }
    return children.toMutableList().apply {
        add(if (anchor >= 0) anchor + 1 else size, entry)
    }
}
