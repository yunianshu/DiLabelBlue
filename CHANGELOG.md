# 更新日志 (Changelog)

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- **BLE 连接前自动配对**：未 bond 的设备先 createBond，配对完成回调里继续
  GATT 连接（HPRT 等新机型要求 bond 后才认打印数据）
- **兼容模式分片**：固定 20 字节 + 15ms 包间隔（大包在小缓冲固件上会整包丢弃）；
  写入改为带响应优先（真实流控）；SPP 配对列表按「识别厂商置顶+名称排序」
- **SPP 通道新增「一键移除全部已配对设备」**：列出配对后显示红色警示按钮，
  二次确认后逐台反射调用隐藏 API `BluetoothDevice.removeBond()` 并逐条报告成功/失败
  （部分 ROM 可能限制该 API，失败时需到系统设置手动移除）
- **App 正式命名 + 图标**：更名「多平台蓝牙连接」（用户指定；曾用「DiLabel 控制台」）；新增雷达+打印机风格启动图标
  （传统 PNG 五密度 + Android 8.0+ 自适应图标），生成脚本 `scripts/make_icon.py` 可复现
- **深/浅主题切换**：雷达区右上角 ☀/☾ 按钮即点即切，日志与设备列表在切换后保留，
  主题选择持久化（SharedPreferences）；浅色主题状态栏图标自动转深色
- 修复：协议芯片（ESC/POS、TSPL、CPCL）创建时未套样式，用框架默认白字在浅色主题下
  融底不可读；主题按钮文字色随主题

- **通道选取功能**（依据 Dlabel APK 证据：BLE/SPP 双通道 + Manifest usb.host）：
  界面新增 BLE GATT / 经典蓝牙 SPP / USB (OTG) 三通道芯片，扫描按钮与连接/发送
  按通道自动路由；新增 `UsbPrinterConnection.java`（class 7 / bulk OUT 枚举、
  USB 权限流程、bulkTransfer 流式发送）；SPP 通道真机验证（100 台已配对设备、
  汉印机型徽章识别）、USB 通道枚举/空态验证
- 截图 `demo/demo_screenshot_channels.png`

- `各平台连接方式调研.md`：iOS/Android/Windows/macOS/Linux/Web/微信小程序/鸿蒙/
  云打印各平台的打印机连接方式、API 入口、限制与厂商多平台 SDK 一览

### 计划中

- 真机（ARM）脱壳提取 Dlabel 业务源码，确认 printlist.list 的 type 精确语义（见问题记录 K1）
- 用真实打印机验证并补全 VendorDB 厂商 UUID 候选表（K2）
- 补充分片写入流控参数与 Dlabel 精确一致（K3）

## [1.2.0] - 2026-10-28

### 新增

- **UI 全面重设计**（暗色控制台风格，纯代码零依赖）：
  扫描雷达动画（扇形扫掠+扩散波纹）、连接状态胶囊（呼吸灯/变色）、
  设备卡片列表（厂商徽章+实时 RSSI 信号条）、协议芯片选择器、
  2×2 渐变动作按钮（按压缩放+震动反馈）、彩色终端日志（自动滚动/清空）
- `RadarView.java`、`SignalBarsView.java` 两个自绘动画控件
- 真机（Pixel 3 / Android 12）全链路验证：扫描 → 前缀识别佳博 →
  GATT 命中 ff00/ff02 → MTU 247 → 通知就绪 → 探测 → ESC/POS 测试标签 121 字节发送成功
- 新增截图 `demo/demo_screenshot.png`（连接就绪态）、`demo/demo_screenshot_scan.png`（扫描态）

### 修复

- **找不到 HPRT T200**：扫描白名单与汉印前缀表补 `hprt`/`t200`（此前被名称
  过滤误杀）；汉印候选表补 ff00/ff02/ff01（T200 实际数据通道）；
  降级枚举跳过 SIG 标准特征（2A00 Device Name 标了可写导致指令写错通道）、
  优先 WRITE_NO_RESPONSE；写入类型随特征属性
- `MainActivity.java`：SPP 通道回调线程直接更新 UI 导致崩溃
  （CalledFromWrongThreadException，通道功能真机验证时暴露）
- `BlePrinterConnection.java`：GATT status=133（弱信号）自动重试 2 次
- `AndroidManifest.xml`：`BLUETOOTH_SCAN` 加 `neverForLocation`——修复 Android 12 上
  无定位权限时扫描结果被系统静默过滤（真机 0 台设备 → 3 台）
- `BleScanner.java`：实现 `onScanFailed` 错误回调（此前扫描失败黑盒）；
  RSSI 实时刷新；扫描模式改 `SCAN_MODE_LOW_LATENCY`
- `VendorDB.java`：厂商匹配改纯前缀（去掉 contains），消除随机广播名误报
- `MainActivity.java`：修复 buildUi 阶段 connection 未初始化的启动崩溃 NPE
- `demo/build_apk.sh`：改用 `python3`（Git Bash 裸 python 损坏）

## [1.1.0] - 2026-10-28

### 新增

- 根目录 `README.md`：项目介绍、结构、四层连接机制与快速开始
- `问题记录.md`：逆向全流程踩坑记录（加固反模拟器、dex 字符串池字典序、
  模拟器 ARM 支持移除、构建与安装问题）及遗留问题清单

## [1.0.1] - 2026-10-28

### 修复

- `demo/build_apk.sh`：Manifest 补充 `uses-sdk`（minSdk 21 / targetSdk 34），
  消除 Android 9 的「专为旧版 Android 打造」兼容警告
- `demo/build_apk.sh`：测试签名密钥改为首次构建自动生成，不再依赖外部文件
- `demo/MainActivity.java`：补全 `BlePrinterConnection.Listener.onData` 实现
  （蓝牙回包十六进制打印）

## [1.0.0] - 2026-10-28

### 新增

- **分析报告**：`Dlabel云标签.apk` 多厂家打印机连接技术逆向分析全文
  （`assets/printlist.list` 厂商前缀表、Manifest 权限矩阵、内嵌厂商 SDK 证据、
  UI 字符串还原的业务流程、360 加固与脱壳尝试记录）
- **demo/**：复刻四层连接机制的完整 Android 工程（纯 SDK 零依赖）
  - 名称前缀识别厂商（VendorDB，复用 Dlabel printlist.list）
  - BLE 无过滤扫描 + 回调分类（BleScanner）
  - GATT 连接：UUID 候选表命中 → 自动降级枚举 → MTU 协商 → CCCD 通知 → 分片流控（BlePrinterConnection）
  - 经典蓝牙 SPP 双通道（ClassicSppConnection）
  - ESC/POS、TSPL、CPCL 三族测试标签构建（LabelProtocols）
  - 无 Gradle 独立构建脚本（aapt2 + javac + d8 + apksigner）
- **scripts/**：360 加固 dex 字节级补丁工具（`patch_dex.py`、`find_classdefs.py`），
  含字符串池字典序、真实 class_defs 扫描等关键发现
- 已构建并验证的 `demo/BlePrinterDemo.apk`（LDPlayer Android 9 启动、权限流程正常）

[Unreleased]: https://github.com/yunianshu/DiLabelBlue/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/yunianshu/DiLabelBlue/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/yunianshu/DiLabelBlue/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/yunianshu/DiLabelBlue/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/yunianshu/DiLabelBlue/releases/tag/v1.0.0
