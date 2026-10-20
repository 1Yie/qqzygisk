package com.qm.qqzygisk.saf

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Drives the real system DocumentsUI over UiAutomator. No production hook, no fake result:
 * the fixture root is selected in the system picker and the "Use this folder" confirmation is
 * accepted exactly like a user would.
 *
 * No global UiAutomation.waitForIdle gating is used; the robot always waits for the concrete
 * intended UI state (picker focus, selected folder's unique child, enabled UseFolder button).
 * Cached UiObject2 instances are never reused across asynchronous refreshes: selectors are
 * re-resolved immediately before clicking, with one shared bounded deadline per operation.
 */
class DocumentsPickerRobot {
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private val DOCUMENTS_UI_PACKAGES = arrayOf(
            "com.android.documentsui",
            "com.google.android.documentsui",
        )
        private val USE_FOLDER_EN = Pattern.compile("(?i)^Use this folder$")
        private val ALLOW_LABELS = arrayOf("Allow", "ALLOW", "允许")
        private val ROOTS_DRAWER_SELECTORS = arrayOf(
            By.desc("Show roots"),
            By.descContains("roots"),
            By.descContains("根目录"),
            By.res("com.android.documentsui", "roots_toolbar"),
        )
    }

    /** Waits until DocumentsUI is the focused app. */
    fun awaitPicker(): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkg = device.currentPackageName
            if (pkg in DOCUMENTS_UI_PACKAGES) return pkg
            Thread.sleep(100)
        }
        throw AssertionError("系统目录选择器未出现，当前前台包=${device.currentPackageName}")
    }

    /**
     * When the picker was launched with the fixture root as initial location, DocumentsUI is
     * already inside it: confirm directly. Verifies both the root title and a known child so a
     * different remembered location can never be granted by accident.
     */
    fun confirmAtCurrentFolder(rootTitle: String, childName: String): Boolean {
        if (!device.wait(Until.hasObject(By.text(rootTitle)), 4_000)) return false
        if (!device.wait(Until.hasObject(By.text(childName)), 4_000)) return false
        return clickUseFolder()
    }

    /**
     * Confirms the folder DocumentsUI was launched into via EXTRA_INITIAL_URI.
     *
     * At least one anchor from the expected path must be visible (folder title or a
     * breadcrumb segment) before the select button is pressed, so a picker that ignored the
     * initial URI cannot silently grant a remembered location.
     */
    fun confirmInitialFolder(vararg anchors: String): Boolean {
        val visible = anchors.any { anchor ->
            device.wait(Until.hasObject(By.textContains(anchor)), 6_000)
        }
        if (!visible) return false
        return clickUseFolder()
    }

    /**
     * Selects [title] as the current root and waits until [expectedChild] is visibly listed in
     * that folder with an enabled "Use this folder" button. If the child is already visible the
     * picker is already in the right folder and no title is clicked (the toolbar title can open
     * a menu and must never be used as a folder selector).
     */
    fun selectRoot(title: String, expectedChild: String) {
        awaitPicker()
        if (awaitFolderReady(expectedChild, 1_200)) return

        // Roots list may already be visible; otherwise open the navigation drawer. Toolbar
        // title matches are rejected by clickRootRow, so this never opens the toolbar menu.
        if (!clickRootRow(title, 1_200)) {
            clickAny(ROOTS_DRAWER_SELECTORS.toList(), 4_000)
        }
        // Row objects reload asynchronously: keep re-resolving within one deadline.
        if (!clickRootRow(title, TIMEOUT_MS)) {
            throw AssertionError("DocumentsUI 根目录列表中没有 $title")
        }
        if (!awaitFolderReady(expectedChild)) {
            throw AssertionError("选择根目录 $title 后未看到预期子项 $expectedChild")
        }
    }

    /** Presses the "Use this folder" button. */
    fun confirmFolder() {
        if (!clickUseFolder()) {
            throw AssertionError("DocumentsUI 未出现可用的“使用此文件夹”按钮")
        }
    }

    private fun clickUseFolder(): Boolean = clickAny(useFolderSelectors(), 6_000)

    /** Accepts the final "Allow ... access" dialog if the platform shows one. */
    fun allowAccess() {
        // All locale alternatives are probed on every polling iteration with one shared
        // deadline, so EN text can never consume the budget before 允许/button1 is seen.
        clickAny(allowSelectors(), 3_000)
    }

    fun waitForForegroundPackage(packageName: String, timeoutMs: Long = TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.currentPackageName == packageName) return
            Thread.sleep(100)
        }
        throw AssertionError(
            "等待 $packageName 回到前台超时，当前=${device.currentPackageName}",
        )
    }

    fun pressBackIfPickerVisible() {
        if (device.currentPackageName in DOCUMENTS_UI_PACKAGES) {
            device.pressBack()
        }
    }

    /**
     * Deterministically cancels/backs out of the picker using one overall deadline.
     *
     * After every Back it waits for a concrete focus change (grantee/other app) before doing
     * anything else; if DocumentsUI remains focused it waits for an observable window update
     * (a navigation step) before the next Back. It never presses a fixed number of times and
     * never returns silently while DocumentsUI is still focused.
     */
    fun dismissPicker() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val packageName = device.currentPackageName ?: break
            if (packageName !in DOCUMENTS_UI_PACKAGES) return
            device.pressBack()
            if (awaitNotDocumentsUi(2_000)) return
            // Still in DocumentsUI: require a real navigation/window update before Back again.
            val updated = runCatching {
                device.waitForWindowUpdate(packageName, 1_500)
            }.getOrDefault(false)
            if (!updated && device.currentPackageName in DOCUMENTS_UI_PACKAGES) {
                break
            }
        }
        if (device.currentPackageName in DOCUMENTS_UI_PACKAGES) {
            throw AssertionError("无法安全退出 DocumentsUI，当前=${device.currentPackageName}")
        }
    }

    private fun awaitNotDocumentsUi(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.currentPackageName !in DOCUMENTS_UI_PACKAGES) return true
            Thread.sleep(80)
        }
        return device.currentPackageName !in DOCUMENTS_UI_PACKAGES
    }

    private fun isFolderReady(expectedChild: String): Boolean =
        findVisibleText(expectedChild) != null && enabledUseFolder() != null

    private fun awaitFolderReady(expectedChild: String, timeoutMs: Long = TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isFolderReady(expectedChild)) return true
            Thread.sleep(120)
        }
        return isFolderReady(expectedChild)
    }

    /**
     * Clicks a root row from the drawer/list, never the toolbar title (which can open a menu).
     * When both match, the lower (drawer) node is the row; a single near-top match is treated
     * as the toolbar and rejected so the caller opens the drawer first.
     */
    private fun clickRootRow(title: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val minRowCenterY = device.displayHeight / 12
        while (System.currentTimeMillis() < deadline) {
            val candidates = runCatching { device.findObjects(By.text(title)) }.getOrDefault(emptyList())
                .filter { candidate ->
                    runCatching { candidate.isEnabled }.getOrDefault(false)
                }
            val row = candidates.maxByOrNull { candidate ->
                runCatching { candidate.visibleBounds.centerY() }.getOrDefault(0)
            }?.takeIf { candidate ->
                runCatching { candidate.visibleBounds.centerY() > minRowCenterY }.getOrDefault(false)
            }
            if (row != null) {
                try {
                    row.click()
                    return true
                } catch (_: StaleObjectException) {
                    // Adapter refreshed between lookup and click; retry within the deadline.
                }
            }
            Thread.sleep(120)
        }
        return false
    }

    private fun findVisibleText(text: String): UiObject2? =
        runCatching { device.findObject(By.text(text)) }.getOrNull()

    private fun enabledUseFolder(): UiObject2? {
        for (selector in useFolderSelectors()) {
            val found = runCatching { device.findObject(selector) }.getOrNull() ?: continue
            if (runCatching { found.isEnabled }.getOrDefault(false)) return found
        }
        return null
    }

    /**
     * Queries every alternative immediately on each polling iteration (no serial 1.5s waits),
     * shares one deadline, and only reports success for an enabled node.
     */
    private fun clickAny(selectors: List<BySelector>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (selector in selectors) {
                val found = runCatching { device.findObject(selector) }.getOrNull() ?: continue
                val enabled = runCatching { found.isEnabled }.getOrDefault(false)
                if (!enabled) continue
                try {
                    found.click()
                    return true
                } catch (_: StaleObjectException) {
                    // DocumentsUI refreshed between lookup and click; re-find next iteration.
                }
            }
            Thread.sleep(120)
        }
        return false
    }

    /**
     * Only the real footer control. Resource IDs cover both AOSP and Google DocumentsUI;
     * exact (case-insensitive EN) labels are fallbacks. Never textContains: the privacy warning
     * "无法使用此文件夹" also contains the Chinese label but is not the action button.
     */
    private fun useFolderSelectors(): List<BySelector> = listOf(
        By.res("com.android.documentsui", "action_menu_select"),
        By.res("com.google.android.documentsui", "action_menu_select"),
        By.text(USE_FOLDER_EN),
        By.text("使用此文件夹"),
    )

    private fun allowSelectors(): List<BySelector> =
        ALLOW_LABELS.map { By.text(it) } +
            By.res("android", "button1")
}
