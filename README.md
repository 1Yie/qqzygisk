# qqhook

Zygisk 模块：在 QQ 和微信的聊天表情按钮上长按，打开本地图片面板。

各宿主分别保存自己的目录配置。所有宿主的 **QQ Zygisk → 表情目录** 都提供相同的三个入口：QQ Zygisk 图片目录、FunBox 表情目录、TGStickersExported 表情目录。三种来源都会被面板扫描；新建分类、保存图片使用第一个目录。

下表路径均相对于 `/storage/emulated/0/Android/media/`：

| 来源 | QQ 默认目录 | 微信默认目录 |
| --- | --- | --- |
| QQ Zygisk 图片 | `com.tencent.mobileqq/.qqzygisk` | `com.tencent.mm/.qqzygisk` |
| FunBox | `com.tencent.mobileqq/.fun/Sticker/Storage` | `com.tencent.mm/.fun/Sticker/Storage` |
| TGStickersExported | `com.tencent.mobileqq/TGStickersExported/v1` | `com.tencent.mm/TGStickersExported/v1` |

路径可独立修改。FunBox 分类名称从其 Storage 旁的 `packs` 文件读取。请使用当前宿主能访问的目录。

历史使用记录保存在当前宿主私有的 `files/qqzygisk/image-panel` 中，按图片根目录区分。升级时会读取可访问的旧 `.usage.json`，后续更新只写私有存储；上次选择的分类通过宿主 SharedPreferences 保存。图片来源可只读，使用记录写入失败不会使已提交的发送报错、重试或导致宿主退出。

QQ 发送前通过统一会话接口实时解析当前联系人，兼容面板添加的关闭保护包装。聊天切换、根视图失效或面板关闭时仍拒绝发送，不使用全局缓存联系人作为兜底。会话不可用时日志会标出绑定、根视图和 AIO 数据就绪状态，不记录联系人标识。

曾经的安装版本把微信 `.qqzygisk` 目录改为应用 UID 和应用组、权限 0700，可能阻断 Android 媒体服务访问，造成面板和文件管理器都无法创建目录。本版安装时会在底层 `/data/media/<用户>/Android/media/com.tencent.mm/.qqzygisk` 恢复应用 UID、`media_rw` 组和媒体目录权限，并恢复安全标签；保留已有图片。修复限于这个旧目录，不会改动微信其他数据或用户配置的其他路径。若仍无法创建，面板会显示失败的完整路径及错误类型。

微信 8.0.79 的「设置」页提供 **QQ Zygisk** 入口。微信面板默认使用原生表情发送：调用微信本地表情导入接口，校验原始 MD5、缓存大小和 GIF 类型后，通过原生 `sendEmoji` 链路提交。无扩展名 GIF 也按文件头识别，发送前不转换为静态 Bitmap。导入或校验失败时提示错误，用户可在发送方式菜单中手动选择普通图片模式。

更新模块后重新启动对应宿主以加载新代码。本项目的本地测试和 APK 签名核对无法代替实际收发验证。

本地验证与打包：

```sh
python3 -B -m unittest discover -s tests -p 'test_*.py'
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

## 鸣谢

- [Hakunm](https://github.com/Hakunm)
- [QAuxiliary](https://github.com/cinit/QAuxiliary)
