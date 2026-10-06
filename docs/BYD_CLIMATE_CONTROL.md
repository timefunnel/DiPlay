# 比亚迪空调面板与自定义方控

这套控制仅在 CarPlay 实际连接期间生效。DiPlay 自绘空调面板从 CarPlay 画面底部滑出，面板显示时启用空调方控绑定，关闭后恢复普通绑定。

## 配置与使用

1. 在 DiPlay 设置页的「DiPlay 方向盘按键」区域开启「自定义方向盘按键」。该区域独立于仪表地图配置；功能默认关闭，所有绑定初始为空。
2. 按现有提示启用方控无障碍服务；车机没有无障碍设置页时可使用已授权的网络 ADB。
3. 点击「唤出／关闭空调面板」，再按希望使用的方向盘按键。学习等待时间为 10 秒，学习时无需连接 CarPlay。
4. 分别学习普通绑定和空调面板绑定。例如滚轮向上在普通模式绑定「音量加」，在空调面板内绑定「温度加」。
5. 连接 CarPlay 后，按唤出键打开面板。同一键再次按下、返回键、面板关闭按钮或面板外点击均可关闭。

CarPlay 的会话设置菜单也有「空调」入口，可先使用触摸验证控制，再配置方控。

普通动作包括播放／暂停、上一首、下一首、音量加减和 Siri。空调动作包括驾驶席温度加减、风量加减、总开关、A/C 压缩机、内外循环和 AUTO。首版温度控制采用摄氏单位，每次增加或减少一个车机整数温度档位。

同一个按键可以在两种模式绑定不同动作。同一模式内重新分配会移除原绑定，并明确显示被替换的动作。唤出／关闭键保留在两种模式中，不能同时承担另一个空调动作。自定义绑定优先于旧地图缩放／摇杆绑定，面板显示时暂停这两种旧模式。

输入复用 `WheelKeyService` 的无障碍过滤入口，以 keyCode、scanCode、设备名记录绑定。一次按下执行一次，连续重复与松开保持原先的消费决定；固件将长按发为独立键码时可学习该键码。部分原车按键在更早的系统层执行，能否抑制原动作必须实车验证。

## 紧凑浮层与主题

采用已确认的紧凑布局：温度和风量并排，下方为总开关、A/C、循环和 AUTO。界面显示「温度」，实际控制仍是驾驶席温度。风量条根据当前固件返回的档位绘制，胶囊内部左右各留 12dp；零档和未知状态不点亮格子。

主题使用 DiPlay 现有的 CarPlay 昼夜模式控制器，面板没有独立的主题偏好。打开时使用当前生效的深浅色；跟随系统或环境光产生的变化会更新已打开的面板。点击面板的「浅色／深色」快捷键，会同步设置 DiPlay 的「始终白天／始终夜晚」，保存到原有设置并通过原有事件通道通知 CarPlay。重新启用自动切换时，在 DiPlay 显示设置中选择「跟随系统」或「环境光」。是否已经改变 iPhone 的显示仍以实际画面为准，发出事件不代表 iPhone 已确认。

使用原生 View、矢量图标、静态渐变和圆角高光，不抓取 CarPlay 视频，不执行实时模糊或折射，也不引入新的 UI 库。入场沿用 220ms 位移动画；状态轮询不重建视图，只有状态或主题改变时更新相应内容。性能取舍面向 Android 9/10，实际帧率和车机负载仍需在 2024 款驱逐舰 05 上验证。

浮层底部居中，宽度最大 760dp，高度按内容和当前屏幕可用高度测量。窄于 560dp 时温度、风量上下排列，开关改为两列；较矮屏幕的内容可滚动，主题、刷新和关闭入口保持可见。触摸目标至少 44dp。

## 生命周期与状态

- 面板关闭、退出 CarPlay 前台、失去窗口焦点、通话开始或 CarPlay 断开，立即退出空调模式并停止面板任务。
- 排队中的控制在写入前再次检查面板与会话状态；关闭面板后尚未开始的指令不再执行。已被车辆接受的指令无法撤销。
- 面板初次打开只读取车辆，不发送控制指令。未知值显示「—」或「未知」，相应控制禁用。
- 触摸和方控共用串行执行通道，每次相对调节先读取车辆当前值，避免根据旧面板值计算。
- 最多排队八项操作；超过时明确提示稍候，避免快速滚轮积累过长的控制队列。
- 命令返回 `0` 只表示接受；最多等待约 1.5 秒回读对应状态。只有回读一致才显示「车辆已确认生效」。拒绝、权限错误、未确认和范围边界均有独立提示，失败不会重发写指令。
- A/C 只控制压缩机，与空调总开关使用不同字段。

## 接口来源与适配边界

采用比亚迪通用接口 `android.hardware.bydauto.BYDAutoDeviceManager` 的 `getInt` / `setInt`。从当前固件的 `BYDAutoFeatureIds$Ac` 动态读取字段 ID，从 `BYDAutoAcDevice` 读取状态和范围常量。

| 操作 | 读取符号 | 写入符号 |
|---|---|---|
| 驾驶席温度 | `AC_TEMP_MAIN` | `AC_TEMP_MAIN_SET` |
| 风量 | `AC_WIND_LEVEL` | `AC_WIND_LEVEL_SET` |
| 总开关 | `AC_POWER_STATE` | `AC_POWER_STATE_SET` |
| A/C | `AC_COMPRESSOR_MODE` | `AC_COMPRESSOR_MODE_SET` |
| 循环 | `AC_CYCLE_MODE` | `AC_CYCLE_MODE_SET` |
| AUTO | `AC_CTRL_MODE` | `AC_CTRL_MODE_SET` |

不存在的字段／常量、未识别的返回值和权限拒绝会明确报告。接口不会猜测其他车型的 FID、数值编码或 Binder 事务号，也不会转用另一套后台或其他车辆功能。当前实现尚未在 2024 款驱逐舰 05 上实车验证。

接口识别参考 [ESMER-UI 的 BydFeatures](https://github.com/akyuznet/ESMER-UI/blob/69f65853a158123783a9576a2f73c38bc13ca030/app/src/main/java/com/example/esmerui/car/BydFeatures.kt) 和 [CarBus](https://github.com/akyuznet/ESMER-UI/blob/69f65853a158123783a9576a2f73c38bc13ca030/app/src/main/java/com/example/esmerui/car/CarBus.kt)，按键执行流程参考 [BYD Kachi 的 KeyCtlDispatch](https://github.com/dangkhoi/byd-kachi/blob/d320ecef81c017fb0b859d578162dc6f26e5c95f/app/src/main/java/com/byd/clusternav/modules/voicekey/KeyCtlDispatch.kt)。实现使用 DiPlay 原有输入与 UI 结构。

## 验证

针对性测试：

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests '*BydClimateBackendTest' --tests '*WheelCustomControlTest' --tests '*CarPlayClimatePanelTest' --tests '*ClimatePanelAppearanceTest' --tests '*WheelKeyServiceTest' --tests '*WheelKeyPermissionSetupTest' --tests '*WheelZoomKeysTest' --tests '*WheelJoystickTest' --tests '*WheelJoystickSessionTest' --tests '*CarPlayHostSettingsTest' --tests '*BydAdbSettingsUiTest' --tests '*CarPlayNightModeControllerTest' --tests '*CarPlayNightModePersistenceTest' --tests '*CarPlayHostThemeDiagnosticsTest' --tests '*NightModeSettingsTest' --tests '*NightModeResourcesTest' --tests '*NightModeDialogsTest'
```

回到车辆旁后，先停车核对面板读数与原车界面，再逐项测试控制是否回读生效；最后验证按键原动作是否被抑制，以及关闭面板、切换应用、通话、断开 CarPlay 后是否恢复原有方控。诊断日志标签为 `DiPlay-Climate` 和 `DiPlay-WheelKeys`。

2026-10-07 本地验证已通过：上述 166 个单元／回归测试、`:mobile:lintDebug`（0 个错误，18 个已有警告）和 `:mobile:assembleDebug`。新增验证覆盖 Android 9/10 的原生布局、窄屏与矮屏滚动、风量条留白、未知与零档显示，以及面板和 DiPlay 的共享主题设置、系统切换和 CarPlay 事件发送。这些结果不代表实车接口权限、原车按键动作或车机流畅度已经验证。

原生渲染预览由布局测试生成到 `common/build/reports/climate-ui/`，包括 `compact-dark.png`、`compact-light.png` 和窄屏滚动前后的图片。预览使用测试车辆状态，不是实车截图。

普通 `:mobile:assembleDebug` 包沿用项目的无认证素材源码构建约定。独立车测包需要项目规定的 `DIPLAY_AUTH_ASSETS_DIR`，并使用 `:mobile:assembleStandaloneDebug`；见 [原构建说明](BUILD.md)。

2026-10-07 另完成本地独立车测包 `DiPlay-0.2.13-byd-climate-test.apk`。认证输入来自官方 [0.2.13 发布 APK](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.13)，其文件 SHA-256 与发布记录一致，APK 签名验证通过。仅选择项目要求的两份认证文件，保存在 Git 与同步工作区外，通过上述显式构建输入打包；成品中的两份文件与官方 APK 逐一校验一致。项目实际认证加载代码通过证书／私钥一致性和挑战签名验证，另有 11 项认证回归测试通过，Lint 无错误。打包校验记录随 APK 保存为 `DiPlay-0.2.13-byd-climate-test-verification.json`。

该包使用独立的 `com.shihab.diplay.hudtest` 包名和本地调试签名，可与官方版并存，但不继承官方版的私有设置及配对记录，也不能覆盖官方版。保留官方安装和数据，测试版单独配置、授权与配对，测试时仅运行一套 CarPlay 接收端。认证与打包验证通过不代表已完成 iPhone 连接或实车空调测试。
