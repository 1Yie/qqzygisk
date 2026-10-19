package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.Method

/**
 * 微信本地的图片发送。
 *
 * 走微信自己的图片编辑发送接口（类名/方法名都未被混淆，是微信「编辑后发送」的正式入口）：
 *
 * ```
 * com.tencent.mm.ui.PhotoEditProxy#sendImageInMM(customSendText, imagePath, selfUsername, talker)
 * ```
 *
 * 该方法内部构造 `la0.g` 发送请求（图片路径 / 自己 wxid / 会话 talker / `ea0.i0` 图片类型）并
 * 通过 `pa0.w.Oj(request)` 直接发起发送协程，不使用实例字段；所以这里用「构造器传 null 组件」
 * 拿到实例即可（基类构造器只保存组件引用，不做非空校验）。
 *
 * 发送协程在微信侧异步执行，本方法返回只代表已提交。
 */
internal object WeChatImageSender {
    private const val PROXY_CLASS = "com.tencent.mm.ui.PhotoEditProxy"
    private const val SEND_METHOD = "sendImageInMM"
    private const val SELF_USERNAME_METHOD = "getSelfUsernameInMM"

    @Volatile
    private var loaderLogged = false

    fun send(file: File, talker: String?): Result<Unit> = runCatching {
        check(file.isFile && file.canRead()) { "图片文件不可用" }
        check(!talker.isNullOrBlank()) { "没有可用的聊天会话，请重新进入聊天页面" }

        if (!loaderLogged) {
            loaderLogged = true
            Log.info(
                "微信发送使用的宿主 ClassLoader: ${appClassLoader.javaClass.name}" +
                    "@${System.identityHashCode(appClassLoader)}",
            )
        }
        val proxyClass = Class.forName(PROXY_CLASS, false, appClassLoader)
        val proxy = newProxy(proxyClass)
        val selfUsername = runCatching { invokeSelfUsername(proxy) }.getOrNull().orEmpty()
        val send = findSendMethod(proxyClass)

        // 参数顺序与微信自己的调用点一致（MMNewPhotoEditUI.onActivityResult）：
        // (随图自定义文本, 图片路径, 自己 wxid, 会话 talker)
        send.invoke(proxy, "", file.absolutePath, selfUsername, talker)
        Log.info("已提交微信图片发送: talker=${mask(talker)} path=${file.absolutePath}")
    }

    private fun newProxy(proxyClass: Class<*>): Any {
        val ctor = proxyClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
            ?: error("微信 $PROXY_CLASS 构造器不可用")
        ctor.isAccessible = true
        return ctor.newInstance(null as Any?)
            ?: error("微信 $PROXY_CLASS 实例创建失败")
    }

    private fun invokeSelfUsername(proxy: Any): String? {
        val method = proxy.javaClass.methods.firstOrNull {
            it.name == SELF_USERNAME_METHOD && it.parameterTypes.isEmpty()
        } ?: return null
        method.isAccessible = true
        return method.invoke(proxy) as? String
    }

    private fun findSendMethod(proxyClass: Class<*>): Method {
        val shape = arrayOf(String::class.java, String::class.java, String::class.java, String::class.java)
        return proxyClass.methods.firstOrNull {
            it.name == SEND_METHOD && it.parameterTypes.contentEquals(shape)
        } ?: error("微信 $PROXY_CLASS.$SEND_METHOD 不可用")
    }

    /** 会话标识属于隐私，日志里只留尾部几位。 */
    private fun mask(talker: String): String =
        if (talker.length <= 4) "***" else "${talker.take(3)}***${talker.takeLast(2)}"
}
