# 更新日志 (Changelog)

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 真机（ARM）脱壳提取 Dlabel 业务源码，确认 printlist.list 的 type 精确语义（见问题记录 K1）
- 用真实打印机验证并补全 VendorDB 厂商 UUID 候选表（K2）
- 补充分片写入流控参数与 Dlabel 精确一致（K3）

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

[Unreleased]: https://github.com/yunianshu/DiLabelBlue/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/yunianshu/DiLabelBlue/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/yunianshu/DiLabelBlue/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/yunianshu/DiLabelBlue/releases/tag/v1.0.0
