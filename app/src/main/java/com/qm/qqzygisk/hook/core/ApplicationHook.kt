package com.qm.qqzygisk.hook.core

import com.qm.qqzygisk.hook.app.DouyinEntry
import com.qm.qqzygisk.hook.app.QQEntry
import com.qm.qqzygisk.hook.app.WeChatEntry
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
object ApplicationHook {
    fun init(packageName: String, classloader: ClassLoader) {
        when (packageName) {
            "com.tencent.mobileqq" -> QQEntry.init(classloader, packageName)
            "com.tencent.mm" -> WeChatEntry.init(classloader, packageName)
            "com.ss.android.ugc.aweme" -> DouyinEntry.init(classloader, packageName)
        }
    }
}