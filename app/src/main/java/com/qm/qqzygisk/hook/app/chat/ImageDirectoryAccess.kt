package com.qm.qqzygisk.hook.app.chat

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NotDirectoryException
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.FileSystemException
import java.nio.file.Files

internal object ImageDirectoryAccess {
    fun ensureDirectory(directory: File) {
        try {
            Files.createDirectories(directory.toPath())
        } catch (error: IOException) {
            throw creationFailure(directory, error)
        } catch (error: ReadOnlyFileSystemException) {
            throw IOException("无法创建目录：${directory.absolutePath}\n原因：文件系统只读", error)
        }
    }

    fun createFolder(root: File, name: String): File {
        ensureDirectory(root)
        val folder = File(root, name)
        try {
            Files.createDirectory(folder.toPath())
        } catch (error: FileAlreadyExistsException) {
            throw IOException("路径已存在：${folder.absolutePath}", error)
        } catch (error: IOException) {
            throw creationFailure(folder, error)
        }
        return folder
    }

    private fun creationFailure(directory: File, error: IOException): IOException {
        val reason = when (error) {
            is AccessDeniedException -> "访问被拒绝，请检查目录权限"
            is NotDirectoryException -> "路径中的某一项不是目录"
            is FileAlreadyExistsException -> "路径已存在且不是可用目录"
            else -> (error as? FileSystemException)?.reason ?: error.javaClass.simpleName
        }
        return IOException("无法创建目录：${directory.absolutePath}\n原因：$reason", error)
    }
}
