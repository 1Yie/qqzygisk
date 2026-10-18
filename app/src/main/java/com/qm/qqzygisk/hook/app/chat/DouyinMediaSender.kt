package com.qm.qqzygisk.hook.app.chat

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/** Submit local files to Douyin 40.7.0's native photo-message task, which owns the upload. */
internal object DouyinMediaSender {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "QQZygisk-DouyinImage").apply { isDaemon = true }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun send(
        file: File,
        target: DouyinSessionTarget,
        liveTarget: () -> DouyinSessionTarget,
        complete: (Result<Unit>) -> Unit,
    ): Result<Unit> = runCatching {
        check(file.isFile && file.canRead()) { "图片文件不可用" }
        worker.execute {
            runCatching {
                val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
                val original = LocalEmoticonFile.inspect(file)
                val suffix = original.format.name.lowercase().replace("jpeg", "jpg")
                val directory = File(context.filesDir, "qqzygisk/douyin-media")
                ImageDirectoryAccess.ensureDirectory(directory)
                val staged = File(directory, "${original.md5}.$suffix")
                if (!staged.isFile || staged.length() != original.size) {
                    val pending = File.createTempFile(original.md5, ".tmp", directory)
                    try {
                        file.inputStream().use { input -> pending.outputStream().use { output -> input.copyTo(output) } }
                        check(LocalEmoticonFile.inspect(pending).md5 == original.md5) { "文件在准备过程中发生变化" }
                        check(pending.renameTo(staged)) { "无法准备抖音图片文件" }
                    } finally {
                        pending.delete()
                    }
                }
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(staged.absolutePath, dimensions)
                check(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "无法读取图片尺寸" }
                val ready = original.copy(file = staged)
                main.post {
                    MediaSendGate(target.conversationId, { liveTarget().conversationId }, { true }, complete)
                        .submit { id -> sendNativeImage(ready, dimensions.outWidth, dimensions.outHeight, id) }
                }
            }.onFailure { error -> main.post { complete(Result.failure(unwrap(error))) } }
        }
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is InvocationTargetException) error.targetException else error

    private fun sendNativeImage(file: LocalEmoticonFile, width: Int, height: Int, conversationId: String) {
        val loader = appClassLoader
        val mediaClass = Class.forName("com.ss.android.ugc.aweme.im.service.model.MediaModel", false, loader)
        val media = mediaClass.getConstructor(Long::class.javaPrimitiveType).newInstance(0L)
        for ((name, value) in mapOf<String, Any>(
            "filePath" to file.file.absolutePath,
            "fileSize" to file.size,
            "width" to width,
            "height" to height,
            "type" to if (file.format == LocalEmoticonFormat.GIF) 2 else 0,
            "mimeType" to "image/${file.format.name.lowercase()}",
            "isQorLater" to false,
        )) mediaClass.getField(name).set(media, value)
        val paramsClass = Class.forName("X.0sFw", false, loader)
        val params = paramsClass.getConstructor().newInstance()
        paramsClass.getField("LIZ").set(params, conversationId)
        paramsClass.getField("LIZIZ").set(params, listOf(media))
        paramsClass.getField("LIZJ").setBoolean(params, true)
        val senderClass = Class.forName("X.0sFp", false, loader)
        senderClass.getMethod("LIZJ", paramsClass).invoke(null, params)
        Log.info("已提交抖音原生图片发送: format=${file.format} bytes=${file.size}")
    }
}
