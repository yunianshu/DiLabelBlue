# DiLabelBlue — Dlabel 云标签多厂家打印机连接技术研究与复刻

通过逆向分析 `Dlabel云标签.apk`（360 加固），学习并复刻其「搜索-识别-连接-适配多厂家打印机」的完整技术方案，并交付一个可运行、可二次开发的 Android 测试例子。

> ⚠️ 仅供学习与研究蓝牙打印协议及 Android BLE 开发，请勿用于侵犯他人知识产权的用途。

## 仓库结构

| 目录/文件 | 内容 |
|---|---|
| [`demo/`](demo/) | **完整可运行的测试例子**：多厂家蓝牙打印机连接 Demo（纯 Android SDK，零第三方依赖），含已构建的 `BlePrinterDemo.apk` |
| [`demo/README.md`](demo/README.md) | Demo 的构建、使用说明与 BLE 工程踩坑要点 |
| [分析报告](分析报告-Dlabel多厂家打印机连接技术.md) | APK 逆向分析全文：证据、机制、加固与脱壳尝试记录 |
| `scripts/` | 360 加固 dex 字节级补丁脚本（脱壳实验方法留档） |
| `decompiled/`、`apk_extracted/`、`unpack_attempt/`、`tools/` | 本地逆向产物与工具（体积大，未入库，按报告流程可重新生成） |

## 核心技术（四层机制）

1. **名称前缀识别厂商** — `assets/printlist.list` 存放型号前缀→协议类型映射表，运行时可热更新；
2. **BLE 无过滤扫描 + 回调分类** — 系统 ScanFilter 只支持全名匹配，前缀识别必须在回调里做；同时支持经典蓝牙 SPP 双通道切换；
3. **厂商 GATT UUID 候选表 + 自动降级** — 每厂商一组服务/特征 UUID 候选，未命中自动枚举所有可写+通知特征兜底；
4. **指令适配测试** — 连接后发探测指令判断协议族（ESC/POS、TSPL、CPCL、ZPL）。

## 快速开始

```bash
# 构建（无需 Gradle / Android Studio）
cd demo && ./build_apk.sh        # 产物 demo/BlePrinterDemo.apk

# 安装到手机（Android 5.0+，需真机蓝牙）
adb install demo/BlePrinterDemo.apk
```

使用流程：搜索打印机 → 选择设备（自动标注厂商）→ 连接 → 适配测试 → 打印测试标签。
详见 [demo/README.md](demo/README.md)。

## 脱壳说明

业务 dex 被 360 加固加密，模拟器上加固壳有反模拟器检测（详见分析报告第三章）。
拿真机（ARM）配合 frida-dexdump / BlackDex 可提取完整业务源码，再据此修正
`demo/src/.../VendorDB.java` 中的厂商 UUID 表即可精确适配更多机型。
