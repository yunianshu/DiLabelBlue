# printer-sdk — 统一多平台打印机连接 SDK

一个零第三方依赖的打印机连接 SDK（纯 Java / Android 兼容，minSdk 21），
整合主流打印机品牌的 SDK 连接方式（依据[调研报告](../主流打印机SDK连接方式调研.md)）：

- **四通道传输**：BLE GATT、经典蓝牙 SPP、USB (OTG)、WiFi/TCP RAW 9100
- **多品牌识别**：名称前缀 → 厂商 Profile → GATT UUID 候选表 → 自动降级枚举
- **多指令族**：ESC/POS、TSPL、CPCL、ZPL（指令层与通道无关，流式字节）
- **机型识别**：BLE 连接后自动读 SIG `0x180A` 全特征（型号/厂商/固件/序列号…）+ 逐族探测

## 构建与测试

```bash
cd sdk
./build_sdk.sh                       # 产物 build/printer-sdk.jar

# 纯 JVM 冒烟测试（无需 Android 设备：品牌识别回归 + 四指令族 + TCP loopback 实测）
javac -encoding UTF-8 -cp build/printer-sdk.jar -d build/test test/JvmSmokeTest.java
java -cp "build/printer-sdk.jar;build/test" JvmSmokeTest    # Windows 分隔符
# java -cp "build/printer-sdk.jar:build/test" JvmSmokeTest  # Linux/macOS
```

## API 一览

| 类 | 职责 | 平台 |
|---|---|---|
| `PrinterClient` | **统一门面**：四通道一个入口、统一状态码/回调 | Android |
| `BleScanner` | BLE 无过滤扫描 + 厂商识别（`PrinterDevice.profile`） | Android |
| `BlePrinterConnection` | BLE GATT：候选表选特征→降级枚举→配对→MTU→CCCD→20 字节分片流控 | Android |
| `ClassicSppConnection` | 经典蓝牙 SPP（TSC/得力/Star 等台式机） | Android |
| `UsbPrinterConnection` | USB OTG bulk 传输 | Android |
| `TcpPrinterConnection` | **WiFi/TCP RAW 9100**（Zebra/Epson/Star/TSC/佳博/汉印/芯烨/得力台式通用） | **任意 JVM** |
| `VendorDB` | 品牌库：前缀识别 + GATT UUID 候选 + 协议族提示 | 平台无关 |
| `LabelProtocols` | ESC/POS / TSPL / CPCL / ZPL 指令构建 + 逐族探测指令 | 平台无关 |

状态码统一复用 `BlePrinterConnection.STATE_*`（`TcpPrinterConnection` 内置同值常量）：
`0 DISCONNECTED / 1 CONNECTING / 2 CONNECTED / 3 READY`。

## 快速上手（Android）

```java
// 1. 创建统一客户端（四通道统一回调）
PrinterClient client = new PrinterClient(this, new PrinterClient.Listener() {
    @Override public void onLog(String msg) { /* 日志 */ }
    @Override public void onState(int state) { /* 通道状态 */ }
    @Override public void onData(byte[] data) { /* 打印机回包（探测/状态） */ }
});
client.registerUsb();   // 界面 onCreate 时注册 USB 权限广播

// 2a. BLE：扫描结果带厂商识别
//     device = BleScanner.PrinterDevice；profile 为 null 表示未知厂商
client.setProfile(device.profile);
client.connectBle(device.device);

// 2b. 台式机 WiFi：一条 TCP 直接写指令，无分片心智负担
client.connectTcp("192.168.1.100", TcpPrinterConnection.DEFAULT_PORT); // 9100

// 2c. 经典蓝牙 SPP（已配对设备）/ USB OTG
// client.connectSpp(bondedDevice);
// client.connectUsb(usbDevice);

// 3. 发送指令（指令族与通道无关；状态 READY 后）
client.sendData(LabelProtocols.tsplTest("标题", "内容"));   // TSPL
client.sendData(LabelProtocols.zplTest("标题", "内容"));    // ZPL（Zebra 系）

// 4. 断开
client.close();
```

低层 API（与各厂商 SDK 对应关系）：

```java
// BLE 逐层控制（对应 Zebra BluetoothConnection / 佳博 GpDevice / 汉印 PortOpen）
BlePrinterConnection ble = new BlePrinterConnection(context, listener);
ble.setProfile(VendorDB.matchByName("ME35PLUS"));   // 候选 UUID 表
ble.connect(bluetoothDevice);                        // 自动配对→服务发现→选特征→就绪
ble.sendData(LabelProtocols.tsplTest("标题", "内容"));

// TCP（对应 Zebra TcpConnection / Epson "TCP:IP"）
TcpPrinterConnection tcp = new TcpPrinterConnection(listener);
tcp.connect("192.168.1.100", TcpPrinterConnection.DEFAULT_PORT);
tcp.sendData(LabelProtocols.escPosTest("收据", "明细"));
```

纯 JVM / 服务器端（Windows/Linux）：核心类 + `TcpPrinterConnection` 不依赖 Android，
可直接 `java -cp printer-sdk.jar` 使用（平台无关验证见 `test/JvmSmokeTest.java`）。

## 品牌覆盖与候选 UUID

品牌 Profile 表在 `VendorDB.java`（依据调研报告 §二/§三，2026-11-12）：

| 品牌 | 协议提示 | BLE 候选 |
|---|---|---|
| 佳博 Gprinter | ESC_POS | ff00/ff02（真机验证）+ ffe0 |
| 汉印 HPRT | ESC_POS | ffe0 / e7810a71(128bit) / ff00（ME35 真机验证 TSPL）|
| 汉印 ME35 系列 | TSPL | ff00 置顶（真机验证）|
| 芯烨 Xprinter | ESC_POS | ffe0 / 49535343 / 18f0 |
| BMA 系列 | CPCL | ff00（真机验证）|
| **斑马 Zebra** | ZPL | **38eb4a80 官方 Parser 服务**（唯一官方文档化 GATT）|
| **TSC** | TSPL | ff00/ffe0/49535343（官方未公开 BLE，SPP/TCP 为主）|
| **霍尼韦尔 Honeywell** | UNKNOWN（DPL 未实现） | 通用候选 + 降级枚举 |
| **爱普生 Epson** | ESC_POS | 通用候选；**TCP 9100 最稳** |
| **Star Micronics** | UNKNOWN（StarPRNT 专有） | 通用候选 |
| **Brother** | UNKNOWN（私有光栅） | 通用候选 |
| **得力 Deli** | TSPL | 通用候选（SPP 为主）|
| 精臣 NIIMBOT | **UNKNOWN（私有二进制协议，防盲发）** | ffe0/fff0/NUS |

未知品牌兜底：降级枚举所有 service/characteristic，选「可写+可通知」特征对
（跳过 SIG 标准特征 0x2A00~0x2AFF，优先 WRITE_NO_RESPONSE）。
指令族最终以探测为准：AUTO 模式优先级「厂商徽章 > 探测命中 > ESC/POS」。

## 已验证范围

- ✅ JVM 冒烟 32/32：品牌识别（含误报回归 #24）、四指令族字节、探测指令、TCP loopback 全双工
- ✅ 真机（三星 Android）：demo（消费本 SDK）安装启动无崩溃、BLE 扫描 12 台、
  HM-T300 PRO 正确识别「汉印 HPRT」徽章、通道切换正常
- ✅ 历史真机打印（demo 时期验证，逻辑原样平移）：ME35PLUS TSPL、BMA3 CPCL、佳博 GT1 ESC/POS
- ⚠️ 新增品牌 Profile 的 BLE 真机连接与打印待补验（HM-T300 配对 PIN 未知未走通，
  Zebra/TSC/得力等需对应真机）
- ⚠️ `PrinterClient` 门面为新增 API，demo 主界面尚未迁移（仍直接驱动各通道类），迁移待真机复测后进行
