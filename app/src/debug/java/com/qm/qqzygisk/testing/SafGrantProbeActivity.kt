package com.qm.qqzygisk.testing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.chat.ImageDirectoryGrants
import com.qm.qqzygisk.hook.app.host.HostRuntime
import com.qm.qqzygisk.hook.app.host.QQHostBridge
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.ui.setting.PathSettingsPage
import com.qm.qqzygisk.ui.theme.AppTheme

/**
 * Debug-only grantee used by the SAF instrumentation flow.
 *
 * Default mode is a real second-UID consumer of the fixture DocumentsProvider: the production
 * directory-picker contract is launched, the user (UiAutomator) picks a folder in the system
 * DocumentsUI, and the production grant helper persists the URI permission. The result is
 * written into the target app's private preferences so the instrumentation process can read it
 * back without sharing objects with this Activity.
 *
 * With [EXTRA_UI_MODE] the Activity instead renders the production PathSettingsPage under the
 * real app theme and real QQHostBridge, so tests can drive the actual chooser button, draft
 * state and 保存路径 commit path.
 */
class SafGrantProbeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_URI = "com.qm.qqzygisk.testing.extra.INITIAL_URI"
        /** Render the production directory-settings page instead of launching the picker directly. */
        const val EXTRA_UI_MODE = "com.qm.qqzygisk.testing.extra.UI_MODE"
        /** Stable accessibility hook for the direct-picker probe button. */
        const val PICKER_BUTTON_DESCRIPTION = "启动目录选择"
        const val PREFS_NAME = "saf_grant_probe"
        const val KEY_DONE = "done"
        const val KEY_URI = "uri"
        const val KEY_FLAGS = "flags"
        const val KEY_READ = "read"
        const val KEY_WRITE = "write"
        const val KEY_ERROR = "error"
        const val KEY_STATUS = "status"
    }

    private lateinit var launcher: ActivityResultLauncher<Uri?>
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val uiMode by lazy { intent.getBooleanExtra(EXTRA_UI_MODE, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_AppDefault)
        preferences.edit().clear().commit()
        if (uiMode) {
            // Real production settings page, real host bridge (no stub), normal app theme.
            HostRuntime.install(QQHostBridge)
            HookSettings.initialize(applicationContext)
            setContent {
                AppTheme(dynamicColor = false) {
                    PathSettingsPage(modifier = Modifier.fillMaxSize())
                }
            }
            return
        }
        launcher = registerForActivityResult(ImageDirectoryGrants.OpenDirectoryTreeContract()) { picked ->
            if (picked == null) {
                publish(error = "cancelled")
                finish()
                return@registerForActivityResult
            }
            val outcome = ImageDirectoryGrants.takePersistedGrant(this, picked)
            val status = ImageDirectoryGrants.status(this, picked.uri.toString())
            publish(
                uri = picked.uri,
                flags = picked.flags,
                read = outcome.read,
                write = outcome.write,
                error = outcome.error,
                status = status.state.name,
            )
            finish()
        }
        // Never start DocumentsUI from onCreate: instrumentation must first observe the
        // resumed probe. The test clicks this real button to invoke the production contract.
        val initial = intent.getStringExtra(EXTRA_INITIAL_URI)?.let(Uri::parse)
        setContent {
            AppTheme(dynamicColor = false) {
                var launched by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = {
                            launched = true
                            launcher.launch(initial)
                        },
                        enabled = !launched,
                        modifier = Modifier.semantics {
                            contentDescription = PICKER_BUTTON_DESCRIPTION
                        },
                    ) {
                        Text("选择目录")
                    }
                }
            }
        }
    }

    private fun publish(
        uri: Uri? = null,
        flags: Int? = null,
        read: Boolean? = null,
        write: Boolean? = null,
        error: String? = null,
        status: String? = null,
    ) {
        preferences.edit().apply {
            putBoolean(KEY_DONE, true)
            uri?.let { putString(KEY_URI, it.toString()) }
            flags?.let { putInt(KEY_FLAGS, it) }
            read?.let { putBoolean(KEY_READ, it) }
            write?.let { putBoolean(KEY_WRITE, it) }
            putString(KEY_ERROR, error)
            status?.let { putString(KEY_STATUS, it) }
        }.commit()
    }
}
