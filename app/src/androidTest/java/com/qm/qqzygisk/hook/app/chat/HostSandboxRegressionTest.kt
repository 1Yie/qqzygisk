package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qm.qqzygisk.R
import dalvik.system.DexClassLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HostSandboxRegressionTest {
    @Test
    fun createFolderDialogIgnoresTheHostsAndroidXFactory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val intent = Intent().setClassName(target.packageName, "com.qm.qqzygisk.testing.DialogProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                // Model QQ/WeChat's own AndroidX copy in a different loader.
                val hostLoader = DexClassLoader(target.applicationInfo.sourceDir, target.codeCacheDir.absolutePath, null, target.classLoader.parent)
                val hostClass = hostLoader.loadClass("androidx.appcompat.widget.ContentFrameLayout")
                assertNotSame(androidx.appcompat.widget.ContentFrameLayout::class.java, hostClass)
                val host = ContextThemeWrapper(activity, R.style.Theme_QQZygisk_MaterialDialog)
                LayoutInflater.from(host).factory2 = object : LayoutInflater.Factory2 {
                    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                        if (name == "androidx.appcompat.widget.ContentFrameLayout") {
                            hostClass.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(context, attrs) as View
                        } else null
                    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)
                }
                val wrapper = Class.forName("com.qm.qqzygisk.hook.app.chat.ModuleContext")
                    .getDeclaredConstructor(Context::class.java, Int::class.javaPrimitiveType, ClassLoader::class.java)
                    .apply { isAccessible = true }
                    .newInstance(host, R.style.Theme_QQZygisk_MaterialDialog, target.classLoader) as Context
                val dialog = MaterialAlertDialogBuilder(wrapper)
                    .setTitle("新建文件夹").setMessage("验证宿主工厂隔离")
                    .setPositiveButton("创建", null).setNegativeButton("取消", null).create()
                dialog.show()
                assertTrue(dialog.isShowing)
                dialog.dismiss()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                activity.finish()
            }
        }
        failure.get()?.let { throw AssertionError("新建文件夹弹窗不能使用宿主的 AndroidX View", it) }
    }
}
