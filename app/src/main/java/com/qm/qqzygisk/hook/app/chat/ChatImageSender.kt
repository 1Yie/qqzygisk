package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList

/**
 * 把本地文件作为图片/表情发送到 [AioSessionBinder.BoundSession] 绑定的聊天。
 *
 * 会话由调用方显式传入（长按时从被触摸的 View 解析，并记录当时的会话身份），这里不做任何
 * “当前会话”全局缓存；发送前会重新解析并与面板打开时的身份比对，聊天已切换则明确失败，
 * 因此切换聊天或复用 View 时不会把图片发到上一个会话，也不会静默发给新会话。
 */
object ChatImageSender {
    private const val Q_ROUTE_CLASS = "com.tencent.mobileqq.qroute.QRoute"
    private const val MSG_UTIL_API_CLASS = "com.tencent.qqnt.msg.api.IMsgUtilApi"
    private const val MSG_SERVICE_CLASS = "com.tencent.qqnt.msg.api.IMsgService"

    /** Contact.chatType == 4 为公会会话，据此选择公会版图片元素工厂。 */
    private const val CHAT_TYPE_GUILD = 4

    enum class SendType(val subType: Int, val summary: String, val label: String) {
        IMAGE(0, "[图片]", "图片"),
        EMOTICON(1, "[动画表情]", "表情"),
    }

    /** 发送本地文件。默认按普通图片，type 可选表情。 */
    internal fun sendImage(
        file: File,
        type: SendType,
        session: AioSessionBinder.BoundSession?,
    ): Result<Unit> = sendToContact(file, type) {
        when (val live = session?.liveContact() ?: AioSessionBinder.LiveContact.Unavailable) {
            is AioSessionBinder.LiveContact.Ready -> live.contact
            is AioSessionBinder.LiveContact.Changed -> error("聊天会话已切换，请重新打开面板")
            is AioSessionBinder.LiveContact.Unavailable -> error("没有可用的聊天会话，请重新进入聊天页面")
        }
    }

    /** Resolve at submission time so decorated panel sessions retain their safety checks. */
    internal fun sendToContact(
        file: File,
        type: SendType,
        resolveContact: () -> Any,
    ): Result<Unit> = runCatching {
        check(file.isFile && file.canRead()) { "图片文件不可用" }
        val contact = resolveContact()

        val msgUtilType = loadClass(MSG_UTIL_API_CLASS)
        val msgUtil = qRouteApi(msgUtilType)
        val element = createPicElement(
            msgUtilType,
            msgUtil,
            file,
            isGuild = contactChatType(contact) == CHAT_TYPE_GUILD,
            subType = type.subType,
        )
        markPicKind(element, type)

        val msgServiceType = loadClass(MSG_SERVICE_CLASS)
        val msgService = qRouteApi(msgServiceType)
        val sendMethod = findSendMethod(msgServiceType, msgService)
        check(sendMethod.parameterTypes[0].isInstance(contact)) {
            "QQ 会话类型不匹配: ${sendMethod.parameterTypes[0].name}"
        }
        val callback = createCallback(sendMethod.parameterTypes[2], file)

        sendMethod.isAccessible = true
        sendMethod.invoke(msgService, contact, arrayListOf(element), callback)
        Log.info("已提交聊天${type.label}发送: ${file.absolutePath}")
    }

    /** 会话类型取自 Contact 的稳定 getter（JNI ABI，非混淆目标）。 */
    private fun contactChatType(contact: Any): Int {
        val getter = contact.javaClass.methods
            .firstOrNull { it.name == "getChatType" && it.parameterTypes.isEmpty() }
            ?: error("QQ 联系人缺少 getChatType")
        return (runCatching { getter.invoke(contact) }.getOrNull() as? Number)?.toInt()
            ?: error("QQ 联系人会话类型不可用")
    }

    /**
     * 图片元素工厂按稳定方法名选择：`createPicElement` / `createPicElementForGuild` 同为
     * (String,boolean,int)，签名无法区分，因此不做签名猜测；公会工厂缺失时退回普通工厂。
     */
    private fun createPicElement(
        apiType: Class<*>,
        api: Any,
        file: File,
        isGuild: Boolean,
        subType: Int,
    ): Any {
        val preferredName = if (isGuild) "createPicElementForGuild" else "createPicElement"
        val methods = (apiType.methods.asSequence() + api.javaClass.methods.asSequence())
            .distinctBy(Method::toGenericString)
        val method = methods.firstOrNull { it.isPicElementFactory(preferredName) }
            ?: (if (isGuild) {
                methods.firstOrNull { it.isPicElementFactory("createPicElement") }
            } else {
                null
            })
            ?: error("QQ 图片消息构造接口不可用")
        method.isAccessible = true
        return method.invoke(api, file.absolutePath, true, subType)
            ?: error("QQ 图片消息构造失败")
    }

    private fun Method.isPicElementFactory(expectedName: String): Boolean {
        val types = parameterTypes
        return name == expectedName && types.size == 3 &&
            types[0] == String::class.java &&
            types[1] == Boolean::class.javaPrimitiveType &&
            types[2] == Int::class.javaPrimitiveType
    }

    private fun findSendMethod(apiType: Class<*>, api: Any): Method {
        return (apiType.methods.asSequence() + api.javaClass.methods.asSequence())
            .distinctBy(Method::toGenericString)
            .firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "sendMsg" && types.size == 3 &&
                    types[0].simpleName == "Contact" &&
                    List::class.java.isAssignableFrom(types[1]) &&
                    types[2].isInterface
            }
            ?: error("QQ 图片发送接口不可用")
    }

    private fun createCallback(type: Class<*>, file: File): Any {
        return Proxy.newProxyInstance(type.classLoader ?: appClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "ImageSendCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    val resultCode = args?.firstOrNull() as? Number
                    if (resultCode != null && resultCode.toInt() != 0) {
                        val detail = args.getOrNull(1)?.toString().orEmpty()
                        Log.error("聊天发送失败: code=$resultCode message=$detail file=${file.absolutePath}")
                    }
                    defaultValue(method.returnType)
                }
            }
        }
    }

    private fun markPicKind(element: Any, type: SendType) {
        val pic = invokeNoArg(element, "getPicElement")
        if (pic == null) {
            Log.warn("图片元素缺少 PicElement，已按 createPicElement(subType=${type.subType}) 提交")
            return
        }
        runCatching {
            invokeOneArg(pic, "setPicSubType", Int::class.javaPrimitiveType!!, type.subType)
        }.onFailure { Log.warn("设置 picSubType 失败", it) }
        runCatching {
            invokeOneArg(pic, "setSummary", String::class.java, type.summary)
        }.onFailure { Log.warn("设置消息外显文案失败", it) }
    }

    private fun qRouteApi(apiType: Class<*>): Any {
        val qRoute = loadClass(Q_ROUTE_CLASS)
        val method = qRoute.declaredMethods.firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.name == "api" &&
                candidate.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: error("QRoute.api(Class) 不可用")
        method.isAccessible = true
        return method.invoke(null, apiType) ?: error("QRoute 未返回 ${apiType.name} 实例")
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun invokeOneArg(target: Any, name: String, argType: Class<*>, arg: Any?) {
        val method = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(argType)) }
            ?: error("未找到 ${target.javaClass.name}.$name(${argType.simpleName})")
        method.isAccessible = true
        method.invoke(target, arg)
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private fun loadClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)
}
