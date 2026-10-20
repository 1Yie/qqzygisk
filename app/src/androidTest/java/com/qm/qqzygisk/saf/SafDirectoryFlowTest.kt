package com.qm.qqzygisk.saf

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.qm.qqzygisk.hook.app.chat.ImageAccessException
import com.qm.qqzygisk.hook.app.chat.ImageDirectoryGrants
import com.qm.qqzygisk.hook.app.chat.ImageEntry
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.app.chat.ImageSendCache
import com.qm.qqzygisk.hook.app.host.HostRuntime
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.AnimatedImageLoader
import com.qm.qqzygisk.hook.utils.HookSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Field
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end SAF directory grant flow on a real device.
 *
 * The fixture DocumentsProvider is part of the androidTest APK (a different UID than the app
 * under test). A debug grantee Activity in the target app launches the production directory
 * picker, the system DocumentsUI selects the fixture root, and the production grant helper
 * persists the URI permission. Everything else runs through the production storage APIs.
 */
@RunWith(AndroidJUnit4::class)
class SafDirectoryFlowTest {

    private companion object {
        // Debug-only grantee Activity in the target APK. Referenced by name because
        // androidTest must not require the debug source set at compile time.
        const val PROBE_CLASS = "com.qm.qqzygisk.testing.SafGrantProbeActivity"
        const val PROBE_PREFS = "saf_grant_probe"
        const val PROBE_KEY_DONE = "done"
        const val PROBE_KEY_URI = "uri"
        const val PROBE_KEY_FLAGS = "flags"
        const val PROBE_KEY_READ = "read"
        const val PROBE_KEY_WRITE = "write"
        const val PROBE_KEY_ERROR = "error"
        const val PROBE_KEY_STATUS = "status"
        const val PROBE_EXTRA_INITIAL_URI = "com.qm.qqzygisk.testing.extra.INITIAL_URI"
        const val PROBE_EXTRA_UI_MODE = "com.qm.qqzygisk.testing.extra.UI_MODE"
        const val PROBE_PICKER_BUTTON = "启动目录选择"

        /** `ImageFolderStore` keeps the last selected category in this settings key. */
        const val LAST_FOLDER_KEY = "emoticon_panel_last_folder"

        /** Real platform ExternalStorageProvider selection: hidden QQ media directory. */
        const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
        const val QQ_MEDIA_RELATIVE = "Android/media/com.tencent.mobileqq/.qqzygisk"
    }

    private lateinit var instrumentation: Instrumentation
    private lateinit var target: Context
    private lateinit var device: UiDevice

    private data class SavedSetting(val key: String, val present: Boolean, val value: String?)

    private val savedSettings = mutableListOf<SavedSetting>()
    private var savedHostApplication: Application? = null
    private var hostRuntimeField: Field? = null
    private var savedHostRuntime: Any? = null

    private data class Grant(
        val uri: Uri,
        val flags: Int,
        val read: Boolean,
        val write: Boolean,
        val error: String?,
        val status: String?,
    )

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        target = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)

        // Snapshot process-global state this suite touches so teardown restores another
        // test's prior state instead of assuming production defaults.
        savedHostApplication = AppParasitics.hostApplication
        hostRuntimeField = HostRuntime::class.java.getDeclaredField("installed").apply {
            isAccessible = true
        }
        savedHostRuntime = hostRuntimeField?.get(HostRuntime)
        savedSettings.clear()
        val preferences = target.getSharedPreferences("qqzygisk_hook_settings", Context.MODE_PRIVATE)
        listOf(
            ImageFolderStore.pathKey,
            ImageFolderStore.FUNBOX_PATH_KEY,
            ImageFolderStore.TG_STICKERS_PATH_KEY,
            LAST_FOLDER_KEY,
        ).forEach { key ->
            savedSettings += SavedSetting(
                key = key,
                present = preferences.contains(key),
                value = preferences.getString(key, null),
            )
        }

        @Suppress("UNCHECKED_CAST")
        AppParasitics.hostApplication = target.applicationContext as Application
        HookSettings.initialize(target)
        HookSettings.setString(LAST_FOLDER_KEY, "")
    }

    @After
    fun tearDown() {
        if (::device.isInitialized) {
            runCatching { DocumentsPickerRobot().dismissPicker() }
        }
        // Drop every fixture grant left behind by a failed assertion.
        target.contentResolver.persistedUriPermissions
            .filter { it.uri.authority == SafFixtureProvider.AUTHORITY }
            .forEach { permission ->
                releaseGrant(permission.uri, permission.isReadPermission, permission.isWritePermission)
            }
        restoreSettings()
        hostRuntimeField?.set(HostRuntime, savedHostRuntime)
        AppParasitics.hostApplication = savedHostApplication
    }

    @Test
    fun writableTreeGrantSupportsBrowseSaveSendHistoryAndRootProtection() {
        assertFixtureIsCrossUidAndRequiresGrant()

        // Start from an unsaved local path and no fixture grant, then drive the real
        // production directory-settings page (chooser button -> DocumentsUI -> draft -> save).
        val initialLocal = File(target.filesDir, "ui-initial-images").apply { mkdirs() }.absolutePath
        val emptyScanRoot = File(target.filesDir, "saf-empty-scan-root").apply { mkdirs() }.absolutePath
        releaseTree(SafFixtureProvider.AUTHORITY, SafFixtureProvider.ROOT_IMAGES)
        HookSettings.setString(ImageFolderStore.pathKey, initialLocal)
        HookSettings.setString(ImageFolderStore.FUNBOX_PATH_KEY, emptyScanRoot)
        HookSettings.setString(ImageFolderStore.TG_STICKERS_PATH_KEY, emptyScanRoot)
        HookSettings.setString(LAST_FOLDER_KEY, "")

        val grantUri = acquireFixtureGrantThroughSettingsUi(initialLocal)
        assertEquals("必须选中 fixture provider", SafFixtureProvider.AUTHORITY, grantUri.authority)
        assertEquals(
            SafFixtureProvider.ROOT_IMAGES,
            DocumentsContract.getTreeDocumentId(grantUri),
        )
        assertTrue(ImageDirectoryGrants.hasPersistedRead(target, grantUri.toString()))
        assertTrue(
            "系统必须记录该目录树的持久读授权",
            target.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && sameTree(it.uri, grantUri)
            },
        )

        // Same document tree ID under a different authority must never inherit the grant.
        val foreignTree = DocumentsContract.buildTreeDocumentUri(
            "some.ungranted.authority",
            SafFixtureProvider.ROOT_IMAGES,
        )
        assertFalse(
            "不同 authority 不得共享同一 tree ID 的授权",
            ImageEntry.hasPersistedRead(target, foreignTree.toString()),
        )
        val foreignEntry = runCatching { ImageEntry.from(foreignTree.toString(), target) }
        assertTrue(
            "未授权 authority 必须在查询 provider 前抛出 ImageAccessException，实际：${foreignEntry.exceptionOrNull()}",
            foreignEntry.exceptionOrNull() is ImageAccessException,
        )

        val root = ImageFolderStore.root()
        assertTrue("授权根必须是可读目录", root.isDirectory && root.exists())
        assertNotNull("SAF 根必须保留文档身份而不是退回文件路径", root.documentUri)
        assertNull("SAF 根不能伪装成本地文件", root.localFile)
        assertTrue("可写授权根应可写", root.canWrite)
        assertFalse("授权根自身绝不允许删除", ImageFolderStore.canDelete(root))

        // Flat selected tree is itself a browsable category (no subfolder required).
        val ownedCategories = ImageFolderStore.scanFolders(includeExternal = false).folders
        val rootCategory = ownedCategories.firstOrNull { it.key == root.key }
        assertTrue("扁平授权根必须作为分类出现", rootCategory != null)
        assertFalse("授权根分类绝不允许删除", ImageFolderStore.canDelete(rootCategory!!))
        val browsableCategories = ImageFolderStore.scanFolders(includeExternal = true).folders
        assertTrue("带外部来源的分类列表必须包含授权根", browsableCategories.any { it.key == root.key })
        assertEquals(
            "没有保存上次分类时应默认选中扁平授权根",
            root.key,
            ImageFolderStore.lastFolderFrom(browsableCategories)?.key,
        )

        // Browse + decode PNG and GIF through the provider.
        val children = root.children()
        val names = children.map { it.name }
        assertTrue("缺少 PNG：$names", SafFixtureProvider.PNG_NAME in names)
        assertTrue("缺少 GIF：$names", SafFixtureProvider.GIF_NAME in names)
        assertTrue("缺少子目录：$names", SafFixtureProvider.SUB_FOLDER in names)

        val png = root.findChild(SafFixtureProvider.PNG_NAME) ?: error("未找到 PNG 文档")
        assertTrue(png.isFile)
        png.inputStream().use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull("SAF 文档必须能被真实解码为图片", bitmap)
            assertEquals(8, bitmap!!.width)
            assertEquals(6, bitmap.height)
        }

        val gif = root.findChild(SafFixtureProvider.GIF_NAME) ?: error("未找到 GIF 文档")
        // Exercise the production URI-based animated preview path, not only raw ImageDecoder.
        val gifDrawable = AnimatedImageLoader.decode(target, gif.documentUri!!, 0, gif.name)
        assertTrue(
            "生产 AnimatedImageLoader 必须保留动图 Drawable，实际：$gifDrawable",
            gifDrawable is AnimatedImageDrawable,
        )
        val animated = gifDrawable as AnimatedImageDrawable
        assertEquals(1, animated.intrinsicWidth)
        assertEquals(1, animated.intrinsicHeight)

        // Remove leftovers from an earlier failed run so the flow stays repeatable.
        runCatching { root.findChild("direct-create.png")?.delete() }
        runCatching {
            root.findChild("测试分类")?.let { leftover ->
                if (leftover.isDirectory) leftover.children().forEach { it.delete() }
                leftover.delete()
            }
        }
        runCatching {
            root.findChild("nested-destination")?.let { leftover ->
                if (leftover.isDirectory) leftover.children().forEach { it.delete() }
                leftover.delete()
            }
        }
        runCatching {
            root.findChild(SafFixtureProvider.FUNBOX_PACK)?.let { leftover ->
                if (leftover.isDirectory) leftover.children().forEach { it.delete() }
                leftover.delete()
            }
        }

        val subFolder = root.findChild(SafFixtureProvider.SUB_FOLDER) ?: error("未找到子目录")
        assertTrue(subFolder.isDirectory)
        assertEquals(
            listOf(SafFixtureProvider.SUB_IMAGE_NAME),
            subFolder.children().map { it.name },
        )

        // Directory that supports creating children but not its own deletion.
        val protectedDir = root.findChild(SafFixtureProvider.PROTECTED_FOLDER)
            ?: error("未找到 ${SafFixtureProvider.PROTECTED_FOLDER}")
        assertTrue(protectedDir.isDirectory)
        assertFalse(
            "provider 不声明 SUPPORTS_DELETE 的目录不能被当作可删除分类",
            ImageFolderStore.canDelete(protectedDir),
        )
        val guard = protectedDir.findChild(SafFixtureProvider.PROTECTED_IMAGE_NAME)
            ?: error("Protected 缺少子图片")
        assertTrue("Protected 的子图片本身应可删除", guard.canDelete)
        val protectedAttempt = runCatching { ImageFolderStore.deleteFolder(protectedDir) }
        assertTrue(
            "删除 Protected 目录必须被拒绝，实际：${protectedAttempt.exceptionOrNull()}",
            protectedAttempt.isFailure,
        )
        assertNotNull("Protected 目录不能被删除", root.findChild(SafFixtureProvider.PROTECTED_FOLDER))
        val guardAfter = protectedDir.findChild(SafFixtureProvider.PROTECTED_IMAGE_NAME)
        assertNotNull("Protected 下的子图片必须保持不变", guardAfter)
        guardAfter!!.inputStream().use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull("Protected 子图片仍必须可读可解码", bitmap)
            assertEquals(3, bitmap!!.width)
            assertEquals(4, bitmap.height)
        }

        // Create + save a new image, then verify bytes through a fresh document query.
        val newImageBytes = pngBytes(7, 9)
        val saved = ImageFolderStore.saveImage(root, newImageBytes, "png")
        assertTrue("保存的图片必须真实存在", saved.exists())
        assertTrue(saved.isFile)
        assertEquals("png", saved.name.substringAfterLast('.').lowercase())
        assertArrayEquals(newImageBytes, saved.inputStream().use { it.readBytes() })
        assertEquals(saved.key, root.findChild(saved.name)?.key)
        assertTrue(ImageFolderStore.images(root).any { it.key == saved.key })

        // Direct document creation through the grant also works.
        val direct = root.createFile("direct-create.png", "image/png")
        assertTrue(direct.exists())
        direct.outputStream().use { it.write(newImageBytes) }
        assertArrayEquals(newImageBytes, direct.inputStream().use { it.readBytes() })
        assertTrue(direct.delete())
        assertNull("删除后的文档必须从 provider 消失", root.findChild("direct-create.png"))

        // New category, then delete only that empty category.
        val category = ImageFolderStore.createFolder("测试分类")
        assertTrue(category.isDirectory && category.exists())
        assertTrue(ImageFolderStore.isOwned(category))
        assertTrue(ImageFolderStore.canDelete(category))
        ImageFolderStore.deleteFolder(category)
        assertNull("空分类应被删除", root.findChild("测试分类"))
        assertFalse(ImageFolderStore.scanFolders().folders.any { it.key == category.key })

        // FunBox migration containment: destination nested inside the source tree.
        val nested = ImageFolderStore.createFolder("nested-destination")
        val pngBeforeMigration = png.inputStream().use { it.readBytes() }
        val rootChildrenBeforeMigration = root.children().map { it.key }.toSet()
        val containment = runCatching {
            ImageFolderStore.migrateFunBoxToQqZygisk(grantUri.toString(), nested.key)
        }
        assertTrue(
            "源目录树内部的迁移目标必须被拒绝，实际：${containment.exceptionOrNull()}",
            containment.isFailure,
        )
        assertTrue(
            "拒绝必须发生在创建任何嵌套子内容之前",
            nested.children().isEmpty(),
        )
        assertEquals(
            "拒绝后授权根内容不得改变",
            rootChildrenBeforeMigration,
            root.children().map { it.key }.toSet(),
        )
        assertArrayEquals(
            "源图片不能因被拒绝的迁移而改变",
            pngBeforeMigration,
            root.findChild(SafFixtureProvider.PNG_NAME)!!.inputStream().use { it.readBytes() },
        )
        assertTrue(ImageFolderStore.canDelete(nested))
        ImageFolderStore.deleteFolder(nested)
        assertNull("只删除本次创建的嵌套目标目录", root.findChild("nested-destination"))

        // Positive tree-to-tree migration: a real FunBox tree grant into the images tree.
        val funboxGrant = pickFolder(
            SafFixtureProvider.rootInitialUri(SafFixtureProvider.ROOT_FUNBOX),
        ) { robot ->
            if (!robot.confirmAtCurrentFolder(
                    SafFixtureProvider.ROOT_FUNBOX_TITLE,
                    SafFixtureProvider.FUNBOX_PACK,
                )
            ) {
                robot.selectRoot(
                    SafFixtureProvider.ROOT_FUNBOX_TITLE,
                    SafFixtureProvider.FUNBOX_PACK,
                )
                robot.confirmFolder()
            }
        }
        assertNull("FunBox 目录授权失败：${funboxGrant.error}", funboxGrant.error)
        assertEquals(SafFixtureProvider.AUTHORITY, funboxGrant.uri.authority)
        assertEquals(
            SafFixtureProvider.ROOT_FUNBOX,
            DocumentsContract.getTreeDocumentId(funboxGrant.uri),
        )
        assertTrue(funboxGrant.read)
        val funboxRoot = ImageEntry.from(funboxGrant.uri.toString(), target)
        val sourcePack = funboxRoot.findChild(SafFixtureProvider.FUNBOX_PACK)
            ?: error("FunBox fixture 缺少 ${SafFixtureProvider.FUNBOX_PACK}")
        val sourceImage = sourcePack.findChild(SafFixtureProvider.FUNBOX_IMAGE_NAME)
            ?: error("FunBox fixture 缺少 ${SafFixtureProvider.FUNBOX_IMAGE_NAME}")
        val sourceImageBytes = sourceImage.inputStream().use { it.readBytes() }

        val firstMigration = ImageFolderStore.migrateFunBoxToQqZygisk(
            funboxGrant.uri.toString(),
            grantUri.toString(),
        )
        assertEquals(1, firstMigration.packs)
        assertEquals(1, firstMigration.copied)
        assertEquals(0, firstMigration.skipped)
        val migratedPack = root.findChild(SafFixtureProvider.FUNBOX_PACK)
            ?: error("迁移后缺少目标表情包")
        assertTrue(migratedPack.isDirectory)
        val migratedImage = migratedPack.findChild(SafFixtureProvider.FUNBOX_IMAGE_NAME)
            ?: error("迁移后缺少目标图片")
        assertArrayEquals(
            "迁移图片字节必须与源文件一致",
            sourceImageBytes,
            migratedImage.inputStream().use { it.readBytes() },
        )

        val secondMigration = ImageFolderStore.migrateFunBoxToQqZygisk(
            funboxGrant.uri.toString(),
            grantUri.toString(),
        )
        assertEquals(1, secondMigration.packs)
        assertEquals(0, secondMigration.copied)
        assertEquals(1, secondMigration.skipped)
        assertEquals(
            listOf(SafFixtureProvider.FUNBOX_IMAGE_NAME),
            migratedPack.children().map { it.name },
        )
        assertArrayEquals(
            "重复迁移不得改变源图片",
            sourceImageBytes,
            sourceImage.inputStream().use { it.readBytes() },
        )

        // Remove only the destination pack created by this flow.
        assertTrue(ImageFolderStore.canDelete(migratedPack))
        ImageFolderStore.deleteFolder(migratedPack)
        assertNull(root.findChild(SafFixtureProvider.FUNBOX_PACK))
        assertNotNull(
            "FunBox 源表情包必须保持不变",
            funboxRoot.findChild(SafFixtureProvider.FUNBOX_PACK)
                ?.findChild(SafFixtureProvider.FUNBOX_IMAGE_NAME),
        )

        // Recursive deletion preflight: a deletable parent containing an undeletable
        // Protected descendant (CREATE-only at any nesting) must be rejected before any child
        // (including the deletable sibling) is removed.
        val recursiveParentName = "delete-parent-${System.currentTimeMillis()}-${android.os.Process.myPid()}"
        val recursiveParent = root.createDirectory(recursiveParentName)
        try {
            val nestedProtected = recursiveParent.createDirectory(SafFixtureProvider.PROTECTED_FOLDER)
            val protectedChildBytes = pngBytes(2, 3)
            val protectedChild = nestedProtected.createFile("guard2.png", "image/png")
            protectedChild.outputStream().use { it.write(protectedChildBytes) }
            val siblingBytes = pngBytes(3, 2)
            val sibling = recursiveParent.createFile("sibling.png", "image/png")
            sibling.outputStream().use { it.write(siblingBytes) }
            val seededGuard = root.findChild(SafFixtureProvider.PROTECTED_FOLDER)
                ?.findChild(SafFixtureProvider.PROTECTED_IMAGE_NAME)
                ?: error("缺少种子 Protected 图片")
            val seededGuardBytes = seededGuard.inputStream().use { it.readBytes() }

            assertTrue("普通父目录本身应可删除", ImageFolderStore.canDelete(recursiveParent))
            val childrenBefore = recursiveParent.children().map { it.key }.toSet()
            val preflight = runCatching { ImageFolderStore.deleteFolder(recursiveParent) }
            assertTrue(
                "包含不可删除 Protected 子目录的父目录必须在删除任何子项前被拒绝，实际：${preflight.exceptionOrNull()}",
                preflight.isFailure,
            )
            assertTrue(
                "预检失败必须明确报告 ImageAccessException，实际：${preflight.exceptionOrNull()}",
                preflight.exceptionOrNull() is ImageAccessException,
            )
            assertTrue("父目录必须仍然存在", recursiveParent.exists())
            assertTrue("嵌套 Protected 目录必须仍然存在", nestedProtected.exists())
            assertTrue("Protected 子项必须仍然存在", protectedChild.exists())
            assertEquals(
                "拒绝必须发生在删除任何子项之前",
                childrenBefore,
                recursiveParent.children().map { it.key }.toSet(),
            )
            val protectedAfter = recursiveParent.findChild(SafFixtureProvider.PROTECTED_FOLDER)
                ?: error("嵌套 Protected 目录不能消失")
            val protectedChildAfter = protectedAfter.findChild("guard2.png")
                ?: error("嵌套 Protected 子图片不能消失")
            assertArrayEquals(
                "嵌套 Protected 子图片字节不能改变",
                protectedChildBytes,
                protectedChildAfter.inputStream().use { it.readBytes() },
            )
            val siblingAfter = recursiveParent.findChild("sibling.png")
                ?: error("可删除兄弟项不能被提前删除")
            assertArrayEquals(
                "兄弟项字节不能改变",
                siblingBytes,
                siblingAfter.inputStream().use { it.readBytes() },
            )
            assertArrayEquals(
                "种子 guard.png 不能因其他删除尝试而改变",
                seededGuardBytes,
                root.findChild(SafFixtureProvider.PROTECTED_FOLDER)!!
                    .findChild(SafFixtureProvider.PROTECTED_IMAGE_NAME)!!
                    .inputStream().use { it.readBytes() },
            )
        } finally {
            // The provider correctly refuses to delete this tree; remove only this fixture-owned
            // temp directory through the test APK's own run-as identity.
            removeFixtureTreeViaAdmin("saf-fixture/images/$recursiveParentName")
        }
        assertNull(
            "递归父目录必须已被测试专用清理移除",
            root.findChild(recursiveParentName),
        )

        // Usage / history keep the original document key (never a cache path).
        // Usage is persisted in the host-private metadata directory, not in the granted tree.
        val identity = ImageFolderStore.ROOT_PATH
        val usageFile = File(
            File(target.filesDir, "qqzygisk/image-panel"),
            "${sha256(identity.toByteArray(Charsets.UTF_8))}.usage.json",
        )
        val folderUsageBefore = ImageFolderStore.folderUsage(root)
        val fileUsageBefore = if (usageFile.isFile) {
            JSONObject(usageFile.readText())
                .optJSONObject("files")
                ?.optJSONObject(png.key)
                ?.optInt("c", 0) ?: 0
        } else {
            0
        }

        ImageFolderStore.recordUsage(png)
        assertEquals("文件夹计数必须真实 +1", folderUsageBefore + 1, ImageFolderStore.folderUsage(root))
        val history = ImageFolderStore.historyFolder()
        val historyKeys = ImageFolderStore.images(history).map { it.key }
        assertTrue("历史必须记录原始文档 key：$historyKeys", png.key in historyKeys)
        assertTrue("使用记录必须落盘持久化：$usageFile", usageFile.isFile)
        val persisted = JSONObject(usageFile.readText())
        assertEquals(
            "图片使用计数必须真实 +1",
            fileUsageBefore + 1,
            persisted.getJSONObject("files").getJSONObject(png.key).getInt("c"),
        )
        assertEquals(
            "父目录使用计数必须真实 +1",
            folderUsageBefore + 1,
            persisted.getJSONObject("folders").getJSONObject(root.key).getInt("c"),
        )

        // Sending materializes bytes into the host-private cache, identity preserved.
        val cached = ImageSendCache.fileFor(target, png)
        assertTrue(cached.isFile)
        assertTrue(cached.canonicalPath.startsWith(target.filesDir.canonicalPath))
        assertTrue(cached.canonicalPath.contains("send-cache"))
        assertNotEquals(png.key, cached.absolutePath)
        assertEquals(sha256(png.inputStream().use { it.readBytes() }), sha256(cached.readBytes()))
        assertEquals(
            "缓存文件必须能还原原始来源身份",
            png.key,
            ImageSendCache.sourceFor(target, cached)?.key,
        )
        val cachedAgain = ImageSendCache.fileFor(target, png)
        assertEquals("再次发送必须命中同一缓存文件", cached.canonicalPath, cachedAgain.canonicalPath)
        assertTrue("缓存文件不能在发送后立即删除", cachedAgain.isFile)

        // Root protection: deleting the selected root must not delete the tree.
        runCatching { ImageFolderStore.deleteFolder(root) }
        assertTrue("授权根仍然存在", root.exists())
        assertTrue(root.children().isNotEmpty())

        // Delete the saved image through the production API.
        ImageFolderStore.deleteImage(saved)
        assertNull("删除图片后文档必须消失", root.findChild(saved.name))
        assertFalse(ImageFolderStore.images(root).any { it.key == saved.key })

        // Revoked persisted grant must be reported, never silently treated as a local path.
        releaseGrant(persistedFixtureUri(grantUri) ?: grantUri, read = true, write = true)
        assertFalse(ImageDirectoryGrants.hasPersistedRead(target, grantUri.toString()))
        assertEquals(
            ImageDirectoryGrants.GrantState.NEEDS_REAUTHORIZATION.name,
            ImageDirectoryGrants.status(target, grantUri.toString()).state.name,
        )
        val restored = runCatching { ImageFolderStore.root() }
        assertTrue(
            "撤销授权后不能再读取 SAF 目录，实际：${restored.exceptionOrNull()}",
            restored.exceptionOrNull() is ImageAccessException,
        )
        val recreated = runCatching { ImageEntry.from(grantUri.toString(), target) }
        assertTrue(
            "撤销授权后构造 ImageEntry 必须明确失败",
            recreated.exceptionOrNull() is ImageAccessException,
        )
    }

    @Test
    fun readOnlyTreeGrantCanBrowseButCannotMutate() {
        val grant = pickFixtureRoot(
            SafFixtureProvider.ROOT_READONLY_TITLE,
            SafFixtureProvider.rootInitialUri(SafFixtureProvider.ROOT_READONLY),
            SafFixtureProvider.READONLY_PNG_NAME,
        )
        assertNull("只读目录授权失败：${grant.error}", grant.error)
        assertEquals("必须选中 fixture provider", SafFixtureProvider.AUTHORITY, grant.uri.authority)
        assertEquals(
            SafFixtureProvider.ROOT_READONLY,
            DocumentsContract.getTreeDocumentId(grant.uri),
        )
        assertTrue("至少应有读授权", grant.read)
        assertTrue(
            ImageDirectoryGrants.status(target, grant.uri.toString()).state in setOf(
                ImageDirectoryGrants.GrantState.READ_ONLY,
                ImageDirectoryGrants.GrantState.GRANTED,
            ),
        )

        configureImageSource(grant.uri)
        val root = ImageFolderStore.root()
        assertTrue(root.isDirectory && root.exists())
        assertFalse("只读 provider 根即使收到写 URI flag 也不可写", root.canWrite)
        assertFalse(root.canDelete)

        val ownedCategories = ImageFolderStore.scanFolders(includeExternal = false).folders
        val rootCategory = ownedCategories.firstOrNull { it.key == root.key }
        assertTrue("只读扁平授权根必须作为分类出现", rootCategory != null)
        assertFalse("授权根分类绝不允许删除", ImageFolderStore.canDelete(rootCategory!!))
        val browsableCategories = ImageFolderStore.scanFolders(includeExternal = true).folders
        assertTrue(browsableCategories.any { it.key == root.key })
        assertEquals(
            "没有保存上次分类时应默认选中只读授权根",
            root.key,
            ImageFolderStore.lastFolderFrom(browsableCategories)?.key,
        )

        val image = root.findChild(SafFixtureProvider.READONLY_PNG_NAME)
            ?: error("只读根缺少 ${SafFixtureProvider.READONLY_PNG_NAME}")
        assertFalse(image.canWrite)
        assertFalse(image.canDelete)
        image.inputStream().use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull(bitmap)
            assertEquals(4, bitmap!!.width)
        }

        val save = runCatching { ImageFolderStore.saveImage(root, pngBytes(3, 3), "png") }
        assertTrue("只读目录不得保存图片：${save.exceptionOrNull()}", save.isFailure)
        val folder = runCatching { ImageFolderStore.createFolder("只读分类") }
        assertTrue("只读目录不得新建分类：${folder.exceptionOrNull()}", folder.isFailure)
        val deleted = runCatching { image.delete() }
        assertTrue(
            "只读文档的删除尝试绝不能报告成功，实际：${deleted.exceptionOrNull() ?: deleted.getOrNull()}",
            deleted.getOrNull() != true,
        )
        assertTrue(
            "只读图片必须仍然存在",
            root.findChild(SafFixtureProvider.READONLY_PNG_NAME) != null,
        )

        releaseGrant(persistedFixtureUri(grant.uri) ?: grant.uri, read = true, write = false)
    }

    @Test
    fun externalStorageProviderGrantCoversRealHiddenAndroidMediaDirectory() {
        val unique = "qqzygisk-saf-test-${System.currentTimeMillis()}-${android.os.Process.myPid()}"
        val absoluteTest = "/sdcard/$QQ_MEDIA_RELATIVE/$unique"
        val seededName = "seeded.png"
        val seededBytes = pngBytes(6, 5)
        val treeId = "primary:$QQ_MEDIA_RELATIVE"
        val treeUri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_AUTHORITY, treeId)
        val initialUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val externalFiles = requireNotNull(target.getExternalFilesDir(null)) {
            "应用专属外部目录不可用，无法播种测试图片"
        }
        val externalSeed = File(externalFiles, "saf-seed-$unique.png")
        externalSeed.writeBytes(seededBytes)

        try {
            // Seed only our uniquely named subfolder; pre-existing contents are never touched.
            // UiAutomation shell calls are single commands: no pipes, && or redirection.
            // UiAutomationConnection executes the raw string via Runtime.exec: no shell
            // quoting/expansion. Paths are controlled ASCII without whitespace.
            val testPath = shellSafePath(absoluteTest)
            val seedPath = shellSafePath(externalSeed.absolutePath)
            shell("mkdir -p $testPath")
            shell("cp $seedPath $testPath/$seededName")
            val copiedSize = shell("stat -c %s $testPath/$seededName").trim().toLongOrNull()
            assertEquals(
                "shell 播种图片大小不符：$copiedSize",
                seededBytes.size.toLong(),
                copiedSize,
            )

            // Real production picker contract opened at the platform ExternalStorageProvider
            // document for the hidden Android/media QQ directory.
            val grant = pickFolder(initialUri) { robot ->
                assertTrue(
                    "DocumentsUI 未按 initial URI 打开 $QQ_MEDIA_RELATIVE",
                    robot.confirmInitialFolder(".qqzygisk", "com.tencent.mobileqq"),
                )
            }
            assertNull("目录授权失败：${grant.error}", grant.error)
            assertEquals(EXTERNAL_AUTHORITY, grant.uri.authority)
            assertEquals(treeId, DocumentsContract.getTreeDocumentId(grant.uri))
            assertTrue("必须获得读授权", grant.read)
            assertTrue("ExternalStorageProvider 授权目录应可写", grant.write)
            assertTrue(
                target.contentResolver.persistedUriPermissions.any {
                    it.isReadPermission && sameTree(it.uri, grant.uri)
                },
            )

            configureImageSource(grant.uri)
            val root = ImageFolderStore.root()
            assertTrue("真实 Android/media 目录必须可浏览", root.isDirectory && root.exists())
            assertEquals(
                treeId,
                DocumentsContract.getTreeDocumentId(Uri.parse(root.key)),
            )
            val before = root.children().map { it.key }.toSet()

            val folder = root.findChild(unique) ?: error("授权根中缺少唯一测试目录 $unique")
            assertTrue(folder.isDirectory)
            val seededDocument = folder.findChild(seededName) ?: error("缺少 shell 播种的图片")
            assertArrayEquals(seededBytes, seededDocument.inputStream().use { it.readBytes() })
            seededDocument.inputStream().use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                assertNotNull("Android/media 播种图片必须可解码", bitmap)
                assertEquals(6, bitmap!!.width)
                assertEquals(5, bitmap.height)
            }

            // All mutations happen strictly inside the unique test subfolder.
            val secondBytes = pngBytes(4, 4)
            val saved = ImageFolderStore.saveImage(folder, secondBytes, "png")
            assertTrue(saved.exists())
            assertArrayEquals(secondBytes, saved.inputStream().use { it.readBytes() })
            val inner = folder.createDirectory("inner")
            assertTrue(inner.isDirectory && inner.exists())
            ImageFolderStore.deleteImage(saved)
            assertNull("新建图片必须已删除", folder.findChild(saved.name))
            assertTrue(inner.delete())
            assertNull("新建目录必须已删除", folder.findChild("inner"))

            // Root and every pre-existing sibling remain untouched.
            assertEquals(
                "授权根中其他内容不能被修改或删除",
                before,
                root.children().map { it.key }.toSet(),
            )
            assertTrue(root.exists())
            assertNotNull("播种图片不能被删除", folder.findChild(seededName))
        } finally {
            releaseTree(EXTERNAL_AUTHORITY, treeId)
            shell("rm -rf $absoluteTest")
            externalSeed.delete()
        }
    }

    private fun assertFixtureIsCrossUidAndRequiresGrant() {
        val providerInfo = instrumentation.context.packageManager
            .resolveContentProvider(SafFixtureProvider.AUTHORITY, 0)
        assertNotNull("fixture DocumentsProvider 未安装", providerInfo)
        val providerUid = providerInfo!!.applicationInfo.uid
        val targetUid = target.applicationInfo.uid
        assertNotEquals("fixture provider 必须与目标应用处于不同 UID", targetUid, providerUid)

        val rootsUri = DocumentsContract.buildRootsUri(SafFixtureProvider.AUTHORITY)
        val denied = runCatching {
            target.contentResolver.query(rootsUri, null, null, null, null)?.use { it.count }
        }
        assertTrue(
            "未授权目标应用不应能直接查询 fixture provider，实际：${denied.exceptionOrNull()}",
            denied.exceptionOrNull() is SecurityException || denied.getOrNull() == null,
        )
    }

    private fun configureImageSource(uri: Uri) {
        HookSettings.setString(ImageFolderStore.pathKey, uri.toString())
        val emptyRoot = File(target.filesDir, "saf-empty-scan-root").apply { mkdirs() }
        HookSettings.setString(ImageFolderStore.FUNBOX_PATH_KEY, emptyRoot.absolutePath)
        HookSettings.setString(ImageFolderStore.TG_STICKERS_PATH_KEY, emptyRoot.absolutePath)
    }

    /**
     * Drives the real production PathSettingsPage: chooser button, system DocumentsUI,
     * cancel, recreation while the picker is open, draft state, then the explicit 保存路径
     * commit. The grantee is launched and observed exactly like the other debug probe flow;
     * DocumentsUI is never started from the Activity's own lifecycle.
     */
    private fun acquireFixtureGrantThroughSettingsUi(initialLocalPath: String): Uri {
        val robot = DocumentsPickerRobot()
        val intent = Intent()
            .setClassName(target.packageName, PROBE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(PROBE_EXTRA_UI_MODE, true)
        val activity = instrumentation.startActivitySync(intent)
        val current = AtomicReference(activity)
        var picked: Uri? = null
        try {
            assertTrue(
                "生产目录设置页未渲染",
                device.wait(Until.hasObject(By.descContains("选择目录")), 20_000),
            )
            assertEquals(
                "页面刚打开时不能改动已保存配置",
                initialLocalPath,
                savedSource(ImageFolderStore.pathKey),
            )

            // First attempt is cancelled: cancel must not touch draft/saved configuration.
            clickImageChooser()
            robot.awaitPicker()
            robot.dismissPicker()
            assertTrue(
                "取消选择后未返回设置页",
                device.wait(Until.hasObject(By.descContains("选择目录")), 20_000),
            )
            assertEquals(
                "取消选择不得覆盖已保存配置",
                initialLocalPath,
                savedSource(ImageFolderStore.pathKey),
            )
            assertFalse(
                "取消选择不应产生 fixture 持久授权",
                target.contentResolver.persistedUriPermissions.any {
                    it.uri.authority == SafFixtureProvider.AUTHORITY
                },
            )

            // Second attempt is the real grant, with a real recreation while DocumentsUI is
            // foreground: the pending chooser slot and draft state must survive.
            clickImageChooser()
            robot.awaitPicker()
            current.set(recreateStoppedGrantee(activity))
            robot.awaitPicker()
            robot.selectRoot(
                SafFixtureProvider.ROOT_IMAGES_TITLE,
                SafFixtureProvider.PNG_NAME,
            )
            robot.confirmFolder()
            robot.allowAccess()

            assertTrue(
                "选择目录后未返回设置页",
                device.wait(Until.hasObject(By.descContains("选择目录")), 20_000),
            )
            val persisted = target.contentResolver.persistedUriPermissions.firstOrNull {
                it.uri.authority == SafFixtureProvider.AUTHORITY &&
                    runCatching { DocumentsContract.getTreeDocumentId(it.uri) }.getOrNull() ==
                    SafFixtureProvider.ROOT_IMAGES
            }
            assertNotNull("选择目录后必须取得持久授权", persisted)
            val uri = persisted!!.uri
            picked = uri
            assertTrue("目录授权必须可读", persisted.isReadPermission)
            assertTrue("目录授权必须可写", persisted.isWritePermission)
            assertTrue(
                "草稿必须显示所选目录",
                device.wait(Until.hasObject(By.textContains(uri.toString())), 10_000),
            )
            assertEquals(
                "未点击保存前配置必须保持不变",
                initialLocalPath,
                savedSource(ImageFolderStore.pathKey),
            )
            assertTrue("未找到可用的“保存路径”按钮", clickText("保存路径"))
            awaitSavedSource(ImageFolderStore.pathKey, uri.toString())
            assertEquals(
                ImageDirectoryGrants.GrantState.GRANTED.name,
                ImageDirectoryGrants.status(target, uri.toString()).state.name,
            )

            // Live transient-grant lifecycle, still inside the running grantee Activity:
            // dropping the persisted grant must invalidate already-created production handles
            // even though the raw resolver can read via the activity's transient result grant.
            val snapshotRoot = ImageEntry.from(uri.toString(), target)
            val snapshotPng = snapshotRoot.findChild(SafFixtureProvider.PNG_NAME)
                ?: error("授权根缺少 ${SafFixtureProvider.PNG_NAME}")
            val snapshotBytes = snapshotPng.inputStream().use { it.readBytes() }
            val persistedPermission = target.contentResolver.persistedUriPermissions.firstOrNull {
                sameTree(it.uri, uri) && it.isReadPermission
            }
            assertNotNull("缺少可释放的持久授权", persistedPermission)
            releaseGrant(
                persistedPermission!!.uri,
                persistedPermission.isReadPermission,
                persistedPermission.isWritePermission,
            )
            assertFalse(
                "释放后不得再报告持久读授权",
                ImageDirectoryGrants.hasPersistedRead(target, uri.toString()),
            )
            val rawTransientBytes = target.contentResolver
                .openInputStream(snapshotPng.documentUri!!)
                ?.use { it.readBytes() }
            assertNotNull("活动存活期间临时结果授权必须仍能读取原始文档", rawTransientBytes)
            assertArrayEquals(snapshotBytes, rawTransientBytes!!)

            val staleInput = runCatching { snapshotPng.inputStream().use { it.readBytes() } }
            assertTrue(
                "旧图片句柄必须拒绝失效的持久授权，实际：${staleInput.exceptionOrNull()}",
                staleInput.exceptionOrNull() is ImageAccessException,
            )
            val staleChildren = runCatching { snapshotRoot.children() }
            assertTrue(
                "旧目录句柄必须拒绝枚举，实际：${staleChildren.exceptionOrNull()}",
                staleChildren.exceptionOrNull() is ImageAccessException,
            )
            val staleExists = runCatching { snapshotPng.exists() }
            assertTrue(
                "旧句柄 exists 必须明确失败，实际：${staleExists.exceptionOrNull()}",
                staleExists.exceptionOrNull() is ImageAccessException,
            )
            assertTrue(
                "释放后 store 不得退回 File 路径",
                runCatching { ImageFolderStore.root() }.exceptionOrNull() is ImageAccessException,
            )

            // Restore the same persisted read/write while the transient result grant is live.
            target.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            assertTrue(
                "活动结束前必须恢复持久读授权",
                ImageDirectoryGrants.hasPersistedRead(target, uri.toString()),
            )
            assertEquals(
                ImageDirectoryGrants.GrantState.GRANTED.name,
                ImageDirectoryGrants.status(target, uri.toString()).state.name,
            )
            val restored = target.contentResolver.persistedUriPermissions.firstOrNull {
                sameTree(it.uri, uri) && it.isReadPermission
            }
            assertNotNull("恢复后必须存在持久读授权", restored)
            assertTrue("恢复后必须存在持久写授权", restored!!.isWritePermission)
        } finally {
            robot.dismissPicker()
            val instance = current.get()
            runCatching {
                instrumentation.runOnMainSync {
                    if (!instance.isFinishing) instance.finish()
                }
            }
        }
        val uri = picked ?: throw AssertionError("设置页没有完成目录授权")
        assertEquals(
            "页面结束后已保存配置必须保留",
            uri.toString(),
            savedSource(ImageFolderStore.pathKey),
        )
        return uri
    }

    private fun clickImageChooser() {
        val chooser = device.findObjects(By.descContains("图片目录 选择目录")).firstOrNull()
            ?: device.findObjects(By.descContains("选择目录")).firstOrNull()
        assertNotNull("未找到图片目录的“选择目录”按钮", chooser)
        chooser!!.click()
    }

    /**
     * Recreates the stopped grantee directly on the main thread (ActivityScenario.recreate
     * would try to force RESUMED while DocumentsUI is foreground) and observes the replacement
     * instance through application lifecycle callbacks, without foregrounding it.
     */
    private fun recreateStoppedGrantee(activity: Activity): Activity {
        val application = target.applicationContext as Application
        val latch = CountDownLatch(1)
        val replacement = AtomicReference<Activity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(created: Activity, savedInstanceState: Bundle?) {
                if (created.javaClass.name == PROBE_CLASS && created !== activity) {
                    replacement.set(created)
                    latch.countDown()
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            instrumentation.runOnMainSync { activity.recreate() }
            assertTrue(
                "recreate 后未观察到新的 grantee 实例",
                latch.await(15, TimeUnit.SECONDS),
            )
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        return replacement.get()!!
    }

    private fun pickFixtureRoot(rootTitle: String, initialUri: Uri, currentFolderMarker: String): Grant =
        pickFolder(initialUri) { robot ->
            if (!robot.confirmAtCurrentFolder(rootTitle, currentFolderMarker)) {
                robot.selectRoot(rootTitle, currentFolderMarker)
                robot.confirmFolder()
            }
        }

    private fun pickFolder(initialUri: Uri, confirmAtInitial: (DocumentsPickerRobot) -> Unit): Grant {
        val preferences = target.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val intent = Intent()
            .setClassName(target.packageName, PROBE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(PROBE_EXTRA_INITIAL_URI, initialUri.toString())
        val activity = instrumentation.startActivitySync(intent)
        val robot = DocumentsPickerRobot()
        try {
            val pickerButton = device.wait(
                Until.findObject(By.descContains(PROBE_PICKER_BUTTON)),
                15_000,
            )
            assertNotNull("调试授权探针的选择按钮未出现", pickerButton)
            pickerButton!!.click()
            robot.awaitPicker()
            confirmAtInitial(robot)
            robot.allowAccess()

            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (preferences.getBoolean(PROBE_KEY_DONE, false)) break
                SystemClock.sleep(100)
            }
            assertTrue(
                "等待目录选择结果超时（当前前台=${device.currentPackageName}）",
                preferences.getBoolean(PROBE_KEY_DONE, false),
            )
            val uriString = preferences.getString(PROBE_KEY_URI, null)
            val error = preferences.getString(PROBE_KEY_ERROR, null)
            return Grant(
                uri = uriString?.let(Uri::parse) ?: Uri.EMPTY,
                flags = preferences.getInt(PROBE_KEY_FLAGS, 0),
                read = preferences.getBoolean(PROBE_KEY_READ, false),
                write = preferences.getBoolean(PROBE_KEY_WRITE, false),
                error = error,
                status = preferences.getString(PROBE_KEY_STATUS, null),
            )
        } finally {
            robot.dismissPicker()
            runCatching {
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing) activity.finish()
                }
            }
        }
    }

    private fun persistedFixtureUri(uri: Uri): Uri? =
        target.contentResolver.persistedUriPermissions
            .firstOrNull { sameTree(it.uri, uri) }
            ?.uri

    private fun sameTree(first: Uri, second: Uri): Boolean {
        if (first.authority != second.authority) return false
        val firstTree = runCatching { DocumentsContract.getTreeDocumentId(first) }.getOrNull()
        val secondTree = runCatching { DocumentsContract.getTreeDocumentId(second) }.getOrNull()
        return firstTree != null && firstTree == secondTree
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    /** Fixture-only admin cleanup: run-as executes argv directly, no shell quoting involved. */
    private fun removeFixtureTreeViaAdmin(relativeUnderFiles: String) {
        val packageName = instrumentation.context.packageName
        shell("run-as $packageName rm -rf files/$relativeUnderFiles")
    }

    /** executeShellCommand(String) uses Runtime.exec without a shell: reject unsafe paths. */
    private fun shellSafePath(path: String): String {
        assertTrue(
            "shell 路径必须是无空白/无引号的受控 ASCII：$path",
            path.isNotEmpty() && path.all { it.code in 0x21..0x7e && it != '\'' },
        )
        return path
    }

    private fun restoreSettings() {
        val preferences = target.getSharedPreferences("qqzygisk_hook_settings", Context.MODE_PRIVATE)
        savedSettings.forEach { saved ->
            if (saved.present) {
                HookSettings.setString(saved.key, saved.value ?: "")
            } else {
                preferences.edit().remove(saved.key).commit()
                clearSettingsCache(saved.key)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearSettingsCache(key: String) {
        val field = HookSettings::class.java.getDeclaredField("stringValues").apply {
            isAccessible = true
        }
        (field.get(HookSettings) as MutableMap<String, String>).remove(key)
    }

    private fun savedSource(key: String): String? =
        target.getSharedPreferences("qqzygisk_hook_settings", Context.MODE_PRIVATE)
            .getString(key, null)

    private fun awaitSavedSource(key: String, expected: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (savedSource(key) == expected) return
            SystemClock.sleep(100)
        }
        assertEquals("保存配置超时：$key", expected, savedSource(key))
    }

    private fun clickText(text: String): Boolean {
        var node = device.wait(Until.findObject(By.text(text)), 3_000)
        if (node == null) {
            val centerX = device.displayWidth / 2
            device.swipe(
                centerX,
                (device.displayHeight * 0.85f).toInt(),
                centerX,
                (device.displayHeight * 0.25f).toInt(),
                20,
            )
            node = device.wait(Until.findObject(By.text(text)), 3_000)
        }
        node?.click()
        return node != null
    }

    private fun releaseTree(authority: String, treeId: String) {
        target.contentResolver.persistedUriPermissions.forEach { permission ->
            if (permission.uri.authority != authority) return@forEach
            val id = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
            if (id != treeId) return@forEach
            releaseGrant(permission.uri, permission.isReadPermission, permission.isWritePermission)
        }
    }

    private fun releaseGrant(uri: Uri, read: Boolean, write: Boolean) {
        var flags = 0
        if (read) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags == 0) return
        runCatching { target.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.rgb(200, 40 + x * 10, 30 + y * 10))
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }


    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
