# Dlabel 云标签 APK 逆向分析报告 —— 多厂家打印机搜索与连接技术

> 分析对象：`Dlabel云标签.apk`（169MB，包名 `com.example.dudian.dudian`，360 加固）
> 分析日期：2026-10-28
> 产物：`demo/BlePrinterDemo.apk`（完整可运行的复刻测试例子）

---

## 一、总体结论

Dlabel 的「搜索连接多厂家打印机」由 **四层机制** 组成，本报告从 APK 中找到了每一层的直接证据，
并按同样架构实现了可运行的测试例子（`demo/` 目录）：

| 层 | 机制 | Dlabel 证据 | Demo 实现 |
|---|---|---|---|
| 1 识别 | 蓝牙名称**前缀表**匹配厂商 | `assets/printlist.list`（18 条型号前缀→协议 type 映射） | `VendorDB.matchByName()` |
| 2 扫描 | BLE **无过滤扫描 + 回调分类**（不用 ScanFilter，因系统只支持全名匹配） | 应用权限 `BLUETOOTH_SCAN` + 字符串「若无法找到打印机请切换蓝牙模式」 | `BleScanner` |
| 3 连接 | 每厂商一组 **GATT 服务/特征 UUID 候选表**，未命中时**自动降级枚举** | 内嵌厂商 SDK：FeasyCom、Zebra、S-Prt、ZGCode 等 41 个 .so | `BlePrinterConnection` |
| 4 适配 | 连接后发**探测指令**判断协议族（ESC/POS、TSPL、CPCL、ZPL） | 字符串「打印机适配测试」「打印机无法识别→指令测试」 | `LabelProtocols` + 适配测试按钮 |

---

## 二、直接证据

### 2.1 printlist.list —— 多厂家识别的核心表（assets 原文，未加密）

```xml
<PrinterList>
 <printer name="printer" type = "4"/>   <printer name="m320" type = "4"/>
 <printer name="1324d" type = "4"/>     <printer name="qr380a" type = "4"/>
 <printer name="3120tu" type = "4>      <printer name="zh" type = "4"/>
 <printer name="bhs" type = "4"/>       <printer name="rf" type = "4"/>
 <printer name="qr" type = "2"/>        <printer name="hm-a300" type = "3"/>
 <printer name="d31" type = "4"/>       <printer name="d21" type = "3"/>
 <printer name="mht-" type = "4"/>      <printer name="pd" type = "4"/>
 <printer name="km-202bt" type = "4"/>
 <printer name="k319" type = "5"/>  <printer name="k419" type = "5"/>  <printer name="k219" type = "5"/>
</PrinterList>
```

要点：
- 按**名称前缀**匹配（如 `mht-`、`hm-` 带连字符，说明是 `name.startsWith(prefix)` 式匹配）；
- `type` 字段把型号映射到指令族（2/3/4/5 四类，对应 CPCL/TSPL/ESC-POS/ZPL 一类的划分，
  精确语义在加密 dex 中，Demo 中按业界惯例映射并开放调整）；
- 这是**运行时加载的资产**，意味着厂商表可以**不改 APK 热更新**——这是多厂家适配的关键工程决策。

### 2.2 AndroidManifest.xml（jadx 解码）

- 权限矩阵同时声明了新旧两套蓝牙权限：
  `BLUETOOTH / BLUETOOTH_ADMIN`（Android 11 及以下）+
  `BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT`（Android 12+）+
  `ACCESS_FINE_LOCATION`（Android 6~11 BLE 扫描必需）；
- `android.hardware.usb.host / usb.accessory`：除蓝牙外还支持 USB 连接打印机；
- 硬件特性 `android.hardware.bluetooth_le` 未设 required=true（兼容无 BLE 的平板）。

### 2.3 内嵌厂商 SDK（lib/arm64-v8a 与 com/ 目录）

| 库/目录 | 厂商 | 作用 |
|---|---|---|
| `libfeasycom.so` | FeasyCom 飞易通 | BLE 模块 SDK（导出 `FscBleCentralApiImp`、`FscSppApiImp`——同时支持 BLE 和经典 SPP 双通道），含 TEA 加密配对 |
| `com/zebra` | Zebra 斑马 | Link-OS 打印 SDK |
| `lib_s_port_GF225.so` | S-Prt | GF225 便携打印机 SDK |
| `libZGCode.so` | — | 条码编码库 |
| `libDFunction.so` / `libdevInfo.so` | — | 设备信息/驱动辅助 |

### 2.4 UI 字符串还原的完整业务流程（res/values*/strings.xml）

```
选择打印机品牌(cloud_device_pick) → 扫描 → 连接(失败可切换 BLE/经典蓝牙 conn_switch_desc)
→ 指令适配测试(brand_cmd_not_pair: "打印机无法识别，是否进行适配测试")
→ 测试引导("打印机应处于待机状态"/"打印机是否打出样签"/"请重启打印机后继续指令测试")
→ 异常状态识别("上盖未关闭"/"打印头过热，暂停5分钟")
```

说明 Dlabel 不是「连接即用」：连接成功后会发送各协议族的探测/测试指令，
**根据回包判断机型与指令族**，这一步是它能覆盖大量白牌机的原因。

---

## 三、加固与脱壳尝试记录（业务 dex 为什么没拿到）

APK 使用 **360 加固**（`com.stub.StubApp` + `assets/libjiagu*.so`，classes.dex 内嵌 36.8MB 加密载荷，
0x18000 之后熵值 7.98，为 AES 级加密，密钥在 native 层）。为拿到业务代码做了如下尝试：

1. **LDPlayer9 (Android 9 x86_64) + frida-dexdump**：应用在 `StubApp.attachBaseContext` 即崩溃，
   `libX86Bridge.so`（实为藏在 arm64 目录的 **x86_64** ELF）的 `JNI_OnLoad` 主动返回 JNI_ERR——
   加固壳检测到模拟器/houdini 转译环境，与 frida 无关（杀掉 frida 仍崩）。
2. **ARM64 AVD**：新版 Android 模拟器（37.x）已移除 x86 主机跑 ARM 镜像的支持，旧版模拟器不再提供下载。
3. **dex 字节级补丁**（`scripts/patch_dex.py`，已入库）：把 x86 检测方法 `com.tianyu.util.a.a()Z`
   补丁为 `return false`，并把字符串 `"64"` 改为 `"12"`（保持字符串池字典序——第一次改成 "zz"
   破坏排序导致 ART 拒载，报 ClassNotFound，这是本次逆向中最有价值的一个坑）。
   补丁后成功绕过 X86Bridge 分支，但 libjiagu_a64.so 是**手工拼接的多 PT_LOAD 非标准 ELF**，
   houdini 转译器报 `missing PT_DYNAMIC / ELF program header is invalid` 拒绝加载——模拟器路线到合理投入上限。

**后续如需完整源码**：在真机（ARM）上用 frida-dexdump / BlackDex 脱壳即可，补丁脚本与流程都已备好。

---

## 四、测试例子（demo/）

复刻上述四层机制的完整 Android 工程（纯 Android SDK，零第三方依赖）：

```
demo/
├── AndroidManifest.xml        # 新旧两套蓝牙权限并存（与 Dlabel 相同策略）
├── assets/printlist.list      # 原样复用 Dlabel 的厂商前缀表
├── src/com/example/bleprinter/
│   ├── VendorDB.java          # 层1: 前缀识别 + 层3: 各厂商 GATT UUID 候选表
│   ├── BleScanner.java        # 层2: 无过滤 BLE 扫描 + 回调分类
│   ├── BlePrinterConnection.java  # 层3: GATT 连接/MTU/CCCD/分片流控 + 自动降级
│   ├── ClassicSppConnection.java  # 层2补充: 经典蓝牙 SPP 双通道（对应"切换蓝牙模式"）
│   ├── LabelProtocols.java    # 层4: ESC/POS、TSPL、CPCL 测试标签构建
│   └── MainActivity.java      # 流程编排 UI
└── build_apk.sh               # 无 Gradle 独立构建脚本（aapt2+javac+d8+apksigner）
```

已验证：`demo/BlePrinterDemo.apk` 在模拟器安装启动正常（含权限申请流程）。
连上真实打印机后：选设备 → 连接 → 适配测试 → 打印测试标签。

参考（厂商 UUID 核实）：
- NETUM/汉印系 E7810A71 服务确认: https://manuals.plus/qa/9000717508/what-are-the-bluetooth-ble-service-and-characteristic-uuids-for-the-netum-nt-1809-printer
- Android BLE 官方文档: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
- Nordic 蓝牙 UUID 数据库: https://github.com/nordicsemi/bluetooth-numbers-database
