# 更新日志 (Changelog)

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

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
