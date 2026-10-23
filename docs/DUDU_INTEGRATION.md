# 嘟嘟桌面：完整 CarPlay 悬浮和音乐同步

基于原仓库 `shihabal3amri/DiPlay` 的 `main`：`4487c689bfdd3baab12de67a993a671b67939eb7`（0.2.21）。2026-10-23 已将 fork `timefunnel/DiPlay` 的 `main` 同步到相同提交；本分支不包含旧的 `codex/byd-climate-wheel` 定制。

此功能默认关闭。在 DiPlay 设置的「高级」→「嘟嘟桌面（实验性）」中开启「在嘟嘟地图卡片显示完整 CarPlay」，并授予 DiPlay 悬浮窗权限。

## 车机设置

1. 在嘟嘟比亚迪版的高德悬浮插件中，将目标应用选择为当前安装的 DiPlay。调试版包名是 `com.shihab.diplay.hudtest`。
2. 在嘟嘟音乐控件中将音乐来源设为 DiPlay，为**嘟嘟**开启通知读取权限。
3. 通过 DiPlay 正常连接手机，再返回嘟嘟桌面。悬浮指令只管理已经启动的画面，不会自动发起连接或把 DiPlay 拉到前台。
4. 音乐在手机中播放。嘟嘟音乐控件读取歌曲、歌手、专辑、封面、时长、进度和播放状态，通过同一个标准媒体会话发送播放、暂停、上一首、下一首。控件具体展示哪些字段取决于嘟嘟。
5. 音乐方控继续使用嘟嘟的设置。此模式下 DiPlay 放行全局方控、通话键及语音键；媒体会话仍接收嘟嘟转发的音乐命令。音量保持车机的处理方式。

## 窗口和日夜行为

- 使用主画面 Stream 110，移动原有视频视图和触摸层到一个悬浮窗口，不创建第二路视频镜像。Android 8 及以上使用 `TYPE_APPLICATION_OVERLAY`；Android 7 使用系统对应的 `TYPE_PHONE` 窗口类型。
- 兼容 `com.autonavi.plus.showmap` / `com.autonavi.plus.closemap`。`x/y/w/h` 分别为左、上、**右、下**坐标；拒绝缺失、负值、倒置及屏幕外的矩形。
- 更新矩形时调整同一窗口。画面等比缩放，比例不同时保留黑边。触摸使用相同的画面变换，黑边内起始的手势被忽略。
- 保留上游的 TextureView 和 SurfaceView 输出路径，视频视图更换父容器时 Android 可以重建 Surface，原解码器重新绑定输出；窗口切换和尺寸变化不会触发 CarPlay 会话重新协商。
- 打开全屏 CarPlay 或 DiPlay 设置时，视图回到原容器。DiPlay 菜单或安全区域编辑尚未关闭时暂停悬浮。关闭悬浮只隐藏窗口，连接与音乐继续。
- 断开后移除悬浮窗口。重连收到主视频流后，仅在嘟嘟仍有显示请求、且 DiPlay 不在前台时恢复显示。
- 沿用 DiPlay 设置中的日夜来源。监测在后台连接期间继续，当前连接移交到新 Activity 时停止旧监测，防止重复来源。
- 通过定向广播向 `com.dudu.autoui` 发送 `AUTONAVI_STANDARD_BROADCAST_SEND`，`KEY_TYPE=10019`，`EXTRA_STATE=37`（日间）或 `38`（夜间），重复状态去重。新的显示请求会补发当前状态。
- 不接收嘟嘟反向的 `10048` 日夜命令；避免双方互相驱动。手机与嘟嘟使用同一个 DiPlay 日夜结果。
- 嘟嘟模式优先于原有「桌面仪表地图」悬浮卡片。独立仪表输出保持原设置。

## 验收

自动化检查覆盖协议矩形、窗口复用和原视图恢复、触摸监听保留、悬浮权限失败、日夜广播去重、暂停状态发现媒体会话、媒体控制命令转发，以及暂停/恢复/跳转时的进度锚点。

以下必须在实际车机验证，单元测试不能代替：

1. 嘟嘟卡片内显示整个 CarPlay，地图、桌面、音乐应用均能准确点击和拖动。
2. 连续切换全屏/卡片并调整卡片尺寸，无手机断连或重复悬浮窗口。
3. 打开其他应用、倒车、关闭卡片后音乐继续；返回桌面按嘟嘟请求恢复。
4. 车机日夜变化在 CarPlay 全屏、悬浮和隐藏状态均同步到手机与嘟嘟。
5. 手机切歌、暂停、恢复时，嘟嘟控件同步；嘟嘟控件和方控的每次操作只执行一次。
6. 手机暂停状态下连接，嘟嘟仍可发现 DiPlay，点击播放能够恢复手机音乐；断开后不保留失效会话。

进度支持同步显示，暂未提供拖动跳转和歌词。无线连接方式沿用原有设置，不依赖 Wi-Fi Direct，也不改变手机/车机的互联网路由；手机通过车机热点连接时是否继续使用蜂窝数据，需要另行实测。

普通源码 `assembleDebug` 不含本地 MFi 认证资产，只用于构建验证。独立车机测试包须显式提供既有本地认证资产并运行 `assembleStandaloneDebug`。

## 协议依据

悬浮视图迁移参考用户提供的「原包_9.1.87原版天空_内置猴哥悬浮大插件.apk.1」中的 `floatapp.FloatWindowsManage` / `AMapViewManage`，复用原视图和触摸监听。DiPlay 使用 TextureView，输出 Surface 的生命周期仍由 Android 回调管理，见 [Android SurfaceTextureListener 文档](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener)。

2026-10-23 静态核对嘟嘟[官方比亚迪版 APK](https://file-qiniu2.dudu-lucky.com/filemanage/202610130725523e2ab74d4664412582bd751d555bdc6b.apk)，SHA-256 `6956D38A41A50CB98BC30FAB7A617A1278EA1B7C69D237F81DA221C935DD3199`：

- `GetMusicInfoService` 按包名选择活动媒体会话，通用音乐来源偏好为 `ZDATA_NORMAL_MUSIC_USE_CLAZZ`。
- `GetMusicInfoService$a.onEvent(musicInfo.i)` 将 1/2/3/4 转为选中会话的 `play` / `pause` / `skipToNext` / `skipToPrevious`。
- `GetMusicInfoService$b` 读取标准 TITLE、ARTIST、DURATION 和 ALBUM_ART / DISPLAY_ICON 封面。
- 该播放状态回调读取 `getPosition()` 后使用自己的时钟推进进度，未读取 `lastPositionUpdateTime`。DiPlay 每次发布状态时从手机的进度锚点换算出当前播放位置，与标准 Android 控件和嘟嘟均保持一致。

以上是静态协议及本地单元测试证据，不能替代实际车机的跨应用、渲染和方控验证。
