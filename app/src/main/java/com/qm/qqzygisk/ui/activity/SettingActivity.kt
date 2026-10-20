package com.qm.qqzygisk.ui.activity

import androidx.activity.compose.setContent
import com.qm.qqzygisk.hook.app.chat.ImageDirectoryGrants
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.ui.setting.SettingView
import com.qm.qqzygisk.ui.theme.AppTheme

class SettingActivity : BaseActivity() {
    override fun onCreate() {
        // 设置项和目录授权都属于宿主：先绑定宿主 SharedPreferences，再按已保存来源清理遗留授权。
        HookSettings.initialize(applicationContext)
        ImageDirectoryGrants.cleanupAbandonedProvisional(
            context = applicationContext,
            restoredFromState = createdFromSavedState,
        )
        setContent {
            AppTheme(dynamicColor = false) {
                SettingView()
            }
        }
    }
}
