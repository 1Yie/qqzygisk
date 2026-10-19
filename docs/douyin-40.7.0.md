# 抖音 40.7.0 接口核对

适配目标：`com.ss.android.ugc.aweme`，versionName `40.7.0`，versionCode `400701`。接口来自设备已安装 APK 的静态分析；没有执行聊天发送、上传或安装测试。

## 聊天入口

`com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.entrances.EmojiWidgetUI.LJJLIIIJL(ViewGroup): View` 返回私信输入区的外部容器。真实点击目标由继承的 `AbsWidgetUI.getChannelView(root)` 获取 `InputChannelItemView`，再取 `getMItemView()`，为空时使用 channel 本身。绑定外层 FrameLayout 无法收到内部按钮处理的长按。

模块在视图创建和 `AbsWidgetUI.onViewCreated(View)` 完成后绑定实际按钮，读取对象上 `SessionInfo` 类型字段。`SessionInfo.getConversationId()` 获取会话 ID。提交前重新校验原聊天，View 已解绑、Activity 结束、面板关闭或聊天改变时取消。

## 设置入口

`com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity` 为主设置页。列表容器 ID 为 `2131390851`；找不到时仅在原生 `CommonItemView` 的纵向父布局内插入。设置项使用宿主 `com.bytedance.ies.dmt.ui.common.views.CommonItemView(Context)`，标题接口为 `setLeftText(CharSequence)`。寄生 Activity 使用清单内标准启动模式的 `com.ss.android.ugc.aweme.setting.ui.AboutActivity`。

设置页曾因读取 `qqz_primary`（`0x150603ca`）发生 `Resources.NotFoundException`。模块 Activity 应用主题前、Compose 读取颜色前、设置入口点击前均确认模块资源可读，缺失时补载 APK 并检查补载结果。

## 普通图片发送

按用户决定，抖音只提供普通图片发送。发送方式使用独立键 `douyin_native_image_panel_send_type`，不再使用旧表情发送偏好。QQ 与微信发送方式不受此调整影响。

1. 根据文件头识别 GIF/PNG/JPEG/WebP，原样复制到宿主私有发送目录并补全扩展名，只读取尺寸，不把预览缩略图当成发送文件。
2. 创建 `com.ss.android.ugc.aweme.im.service.model.MediaModel(long)`，填写 `filePath/fileSize/mimeType/type/width/height/isQorLater`；`type=2` 是 GIF 标记，`type=0` 是图片。
3. 创建 `X.0sFw`；`LIZ` 为当前会话 ID，`LIZIZ` 为 MediaModel 列表，`LIZJ=true` 采用原图选项。
4. 通过 `X.0sFp.LIZJ(X.0sFw)` 提交抖音原生图片任务。

上传和消息创建由抖音原生任务负责。其下游为 `ChatRoomService.LJJJJIZL -> X.0sCp -> StoryPictureContent`，属于图片消息；不承诺 GIF 动画会按表情方式展示。模块不再自行执行表情上传、URI 解析、EmojiContent 消息构造或收藏登记，也不等待这些自定义阶段。提交成功后更新私有使用记录并关闭面板；实际网络发送结果以抖音聊天窗口为准。

## 验证边界

关键媒体模型字段、原生图片任务和按钮接口已与 APK 的 DEX 声明核对。回归检查确保抖音默认和可选类型均为图片，旧表情动作不能进入发送器，发送代码不依赖收藏创建或自定义表情上传服务。编译与本地测试不能替代实际收发验证；用户自行安装 ZIP 后确认结果，代理不使用真实好友会话自动发送。
