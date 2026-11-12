# 主流打印机品牌 SDK 连接方式调研

> 调研视角：第三方 App（Android/iOS）直接连接打印机并发送打印指令，不依赖厂商自家 App。
> 配套项目：[demo](demo/README.md)（Android 真机验证）、[sdk](sdk/README.md)（整合后的统一 SDK）、
> [各平台连接方式调研](各平台连接方式调研.md)（OS 平台维度）。撰写日期：2026-11-12。

---

## 一、结论先行

1. **真正由官方文档完整给出 BLE GATT 协议的品牌很少**。Zebra 是唯一明确公布
   Service/Characteristic UUID、写入方式和 MTU 规则的国际大厂。
2. **国产标签/小票机大量采用 BLE 串口透传**，最常见的是 `FF00/FF02`、`FFE0/FFE2`、
   `18F0/2AF1`、`49535343...`。BLE 只负责「透明字节流」，真正决定能否打印的是指令族
   （TSPL/CPCL/ESC-POS/ZPL）。本项目实测 `FF00/FF02` 与这一生态完全吻合。
3. **日系/欧美移动标签机更多走 SPP、TCP 或「SDK 封装 BLE」**。Brother、Epson、Star、
   TSC、Honeywell 的 BLE UUID 基本不公开，第三方裸连成本高，官方也不建议。
4. **NIIMBOT 是私有二进制协议**（`55 55 ... AA AA` 帧），不是 TSPL/CPCL/ESC-POS；
   HP 基本不走 BLE 打印，走 IPP/9100。
5. 对本项目最现实的策略：**BLE 多 Profile 自动识别 + 指令族探测 + SPP/TCP 9100 兜底**，
   不要假设所有 BLE 打印机都吃 TSPL。

## 二、通用 BLE 打印 GATT Profile 速查

16 位 UUID 的完整形式为 `0000xxxx-0000-1000-8000-00805F9B34FB`。

| Profile | Service UUID | Write（App→打印机） | Notify/Read（打印机→App） | 常见设备 | 典型指令 |
|---|---|---|---|---|---|
| Zebra LE Parser | `38eb4a80-c570-11e3-9507-0002a5d5c51b` | `38eb4a82-...` | `38eb4a81-...` | Zebra Link-OS | ZPL/CPCL |
| FF00 透传 | `0000ff00-...` | `0000ff02-...` | `0000ff01-...` | 佳博、汉印、芯烨及大量国产 | TSPL/CPCL/ESC/ZPL |
| FFE0 透传 | `0000ffe0-...` | `0000ffe2-...` | `0000ffe1-...` | HM-10/CC2541 类模块 | 透明字节流 |
| 18F0 打印服务 | `000018f0-...` | `00002af1-...` | `00002af0-...` | 大量迷你/POS 打印机 | ESC/POS、CPCL |
| 49535343（Microchip/ISSC 透传 UART） | `49535343-fe7d-4ae5-8fa9-9fafd205e455` | `49535343-8841-...` | `49535343-1e4d-...`（两种变体） | 芯烨 XP-58IIH、部分 Star/国产 | ESC/POS/TSPL/CPCL |
| NIIMBOT 私有 | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` | `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f`（Write No Response） | 同一特征 Notify | 精臣 | 私有二进制 |
| Device Information `0x180A` | `0000180a-...` | — | `0x2A29` 厂商 / `0x2A24` 型号 / `0x2A25` 序列号 / `0x2A26` 固件 | 通用 | 设备信息 |
| GAP `0x1800` | `00001800-...` | — | `0x2A00` Device Name | 通用 | 设备名 |

补充说明：

- Device Name 在 GAP `0x1800` 的 `0x2A00`；`0x180A` 提供 Manufacturer/Model/Serial/Firmware
  等。两者配合可用于品牌和型号识别。
- 部分模组会把 `FF01/FF02` 或 `2AF0/2AF1` 的读写方向对调——不能只看 UUID，必须同时检查
  `PROPERTY_WRITE` / `PROPERTY_WRITE_NO_RESPONSE` / `PROPERTY_NOTIFY`（demo 的降级枚举即此做法）。
- BLE 默认 ATT MTU=23（有效载荷 20 字节）。Android 应 requestMtu(247/512) 按协商结果分片，
  未协商时按 20 字节发送；长指令建议 `WRITE_TYPE_DEFAULT`（有响应）保证可靠。
- 中文文本在国产 TSPL/CPCL/ESC-POS 上通常是 **GBK/GB18030**，不是 UTF-8。

## 三、品牌逐个调研

### 1. Zebra（斑马）

- **SDK**：Link-OS Multiplatform SDK（Android/iOS/Windows/Linux，Java/ObjC/C#）。
  [官网](https://www.zebra.com/us/en/software/printer-software/multiplatform-sdk.html) ｜
  [下载](https://www.zebra.com/us/en/support-downloads/printer-software/link-os-multiplatform-sdk.html) ｜
  [TechDocs](https://techdocs.zebra.com/link-os/) ｜ [Android 示例](https://github.com/ZebraDevs/LinkOS-Android-Samples)
- **连接**：BLE、经典 SPP、TCP 9100、USB。Link-OS 机型（ZQ500/ZQ600、ZD421 等）支持 BLE。
- **BLE UUID（官方文档化，AppNote）**：Service `38EB4A80-...` / Write `38EB4A82-...` /
  Read(Indication) `38EB4A81-...`；单次写 ≤ min(ATT MTU, 512)；反向数据需对 `38EB4A81`
  的 CCCD 写 `0x0002` 开 Indication。
  [AppNote-BlueToothLE-v4.pdf](https://www.zebra.com/content/dam/zebra/software/en/application-notes/AppNote-BlueToothLE-v4.pdf)
- **指令族**：ZPL II、CPCL（旧机型 EPL）。TCP 端口常量 `TcpConnection.DEFAULT_ZPL_TCP_PORT`=9100。
- **关键 API**：`com.zebra.sdk.comm.Connection`（`BluetoothConnection`/`TcpConnection`/`UsbConnection`）、
  `ZebraPrinterFactory.getInstance(connection)`。
- **第三方直连结论：最友好**。官方文档化 BLE Parser Service，第三方 App 可直接写 ZPL/CPCL。

### 2. Honeywell（霍尼韦尔 / Datamax-O'Neil）

- **SDK**：Honeywell Printing SDK / Intermec Printing SDK（Android 核心 `LinePrinter.jar`）。
  [下载说明](https://support.honeywellaidc.com/s/article/Where-to-download-Intermec-printing-SDK-for-Android)
- **连接**：经典 SPP（`bt://MAC`）、TCP、USB；RP4 等双模机型支持 SPP；部分机型 NFC 配对。
- **BLE UUID**：**官方未公开**。裸连只能运行时枚举，`18F0/2AF1` 是通用候选但不保证。
- **指令族**：DPL（Datamax 原生）、CPCL、ZPL、ESC/P、IPL，按机型。
- **关键 API**：`com.intermec.print.lp.LinePrinter`（`new LinePrinter(profilePath, "PR3", "bt://68:0A:D2:...", settings)`）。
- **结论**：SPP 可行（DPL/CPCL 走串口字节流）；BLE 不建议裸连。

### 3. TSC

- **SDK**：TSC Printer SDK（Android/iOS/Windows DLL/WinCE）。
  [EMEA 下载](https://emea.tscprinters.com/en/downloads) ｜
  [TSPL/TSPL2 手册](https://www.tscprinters.com/EN/DownloadFile/readpdf/support/4356/TSPL_TSPL2_Programming.pdf?file_type=0)
- **连接**：经典 SPP（Android 主流）、MFi BT（iOS Alpha-30L）、TCP 9100、USB；部分 Alpha 机型 BLE 4.2。
- **BLE UUID**：**官方未公开**，SDK 封装；`FF00/FF02` 或 `49535343` 只是候选。
- **指令族**：TSPL/TSPL2；部分机型支持 EPL/ZPL/ESC-POS 仿真。
- **关键 API**：`TSCActivity`：`openport(mac)` → `setup(...)` → `clearbuffer()` →
  `sendcommand()`/`barcode()` → `printlabel(sets,copies)` → `closeport()`。
- **结论**：SPP + TSPL 最通用；iOS 经典蓝牙需 MFi。

### 4. Brother

- **SDK**：Brother Print SDK for Android / iPhone-iPad（4.13.x）。
  [下载](https://support.brother.com/g/s/es/dev/en/mobilesdk/download/index.html) ｜
  [支持机型](https://support.brother.com/g/s/es/htmldoc/mobilesdk/overview/supported-printer.html)
- **连接**：WiFi、经典 SPP、BLE、USB OTG（仅 Android）。iOS 经典蓝牙需 MFi 机型 + PPID。
- **BLE UUID**：**未公开**，SDK 封装（Android `getBLEPrinters()` 发现）。
- **指令族**：Brother 私有光栅（Raster Command Reference）、P-touch Template；部分 RJ/TD 支持 ZPL/CPCL 仿真。
- **关键 API**：`com.brother.ptouch.sdk.Printer`、`PrinterInfo`、`printImage()`；iOS `BRPtouchPrinter`。
- **结论**：**必须用官方 SDK**，裸连无法保证。

### 5. Epson

- **SDK**：Epson ePOS SDK（Android/iOS/JavaScript）。
  [说明](https://download4.epson.biz/sec_pubs/pos/reference_en/technology/epson_epos_sdk.html) ｜
  [ESC/POS 参考](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/index.html)
- **连接**：WiFi/Ethernet、经典 SPP（Android）、BLE（视版本/机型）、USB。网络端口：ePOS 8008/8009/8043、
  ePOS-Print XML 80/443、**原始 ESC/POS 走 TCP 9100**。
- **BLE UUID**：**未公开**，由 ePOS SDK 抽象。
- **指令族**：ESC/POS、ePOS-Print XML。
- **关键 API**：`Epos2Printer`、`Epos2Discovery.start(...)`、`printer.connect("BT:MAC"|"TCP:IP", timeout)`。
- **结论**：**TCP 9100 + 原始 ESC/POS 是第三方最稳通路**。

### 6. Star Micronics

- **SDK**：StarPRNT SDK（StarIO/StarIO_Extension）、StarXpand SDK（StarIO10）。
  [开发者页](https://starmicronics.com/support/developers/) ｜
  [Android 示例](https://github.com/star-micronics/StarPRNT-SDK-Android-Java)
- **连接**：TCP 9100、USB、经典 SPP、BLE。SM-L200 的 BLE 在 iOS 免配对，Android 需配对（PIN 1234）。
- **BLE UUID**：**官方不公开**；社区报告部分模块用 `49535343...` 透传，不能视为保证。
- **指令族**：StarPRNT / Star Line Mode（专有）；部分机型支持 ESC/POS 仿真。
- **关键 API**：Android `StarIOPort`、iOS `SMPort`；StarIO10 `StarDeviceDiscoveryManager`、`InterfaceType.BluetoothLE`。
- **结论**：SPP/TCP 可行；BLE 建议用 SDK。

### 7. HP

- **SDK**：HP Mobile Printing SDK **已弃用**（Android 8.0+ 打印已原生集成）。
  [说明](https://developers.hp.com/printing-internet-developers)
- **连接**：WiFi/Ethernet IPP(631)、Raw 9100、USB；BLE 仅用于发现/配网，不是打印数据通道。
- **指令族**：PCL 5/6、PCLm、PostScript、PDF、PWG Raster。
- **结论**：**不要走 BLE**。标签/小票场景应走 IPP 631 或 Raw 9100；HP 也不吃 TSPL/CPCL。

### 8. 佳博 Gprinter（Gainscha）

- **SDK**：[官方开发文档/下载](https://www.gprinter.net/kaifa01/)（Android/iOS/C#/Linux/微信/Web/UniApp）。
  统一 SDK 包 `@gprinter/gp_sdk` 2.0.3，明确支持 SPP/BLE/USB/网络 + ESC/TSC/CPC/ZPL 四种指令集。
- **连接**：BLE、经典 SPP、USB、WiFi/Ethernet TCP 9100。
- **BLE UUID**：常见 `0000FF00-...` + 写 `0000FF02-...`（本项目 ME35PLUS/BMA3 真机验证命中）；
  部分型号 `FFE0` 或 `18F0`。
- **指令族**：TSPL/TSC、CPCL、ESC/POS、ZPL。
- **关键 API**：V1 `GpDevice.openBluetoothPort()`/`sendDataImmediately()`/`TscCommand`/`EscCommand`；
  V2 `GpService` AIDL `sendTscCommand(printerId, base64)`。
- **云打印**：`api.poscom.cn`（`mode=2` 文本/指令，标签默认 UTF-8、票据默认 GB18030）。
- **结论：好**。`FF00/FF02` + TSPL/CPCL 是已验证的通用组合。

### 9. 汉印 HPRT

- **SDK**：HPRT UxPrint SDK（Android/iOS/Windows/Linux/macOS/HarmonyOS）+ 按机型的 TSPL/CPCL/ZPL SDK。
  [下载](https://www.hprt.com.cn/pro_files.html?classifyid=64&pid=126)
- **连接**：BLE、SPP、USB、WiFi TCP 9100。A300/T300 便携机多为 CPCL + BLE；N41 以 USB/TSPL 为主。
- **BLE UUID**：A300/T300 等社区实测 `0000FF00-...` + `0000FF02-...`；部分模块 `FFE0/FFE2`。
  官方未统一公布，需按型号确认（本项目 ME35 系列 ff00/ff02 + TSPL 真机验证）。
- **指令族**：ESC/POS、CPCL、TSPL、ZPL；UxPrint 为多协议统一 SDK。
- **关键 API**：`HPRTPrinterHelper.PortOpen("Bluetooth,<MAC>")`/`PortClose()`；CPCL 机型 `cpcl.PrinterHelper`。
- **结论：FF00/FF02 可行**，但机型/固件差异大，建议先读 `0x180A` 型号再定指令族。

### 10. 芯烨 Xprinter

- **SDK**：[官方 SDK 页](https://pt.xprintertech.com/sdk.html)（Windows 2.1.5 / Android 3.5.8 / iOS 3.2.2 / Linux），
  HarmonyOS NEXT `@xpsdk/printersdk`。
- **连接**：BLE、SPP、USB、WiFi TCP 9100。
- **BLE UUID**：常见 `0000FF00-...`+`0000FF02-...`；XP-58IIH 实测走 `49535343-FE7D-...` 透传
  （写 `...8841-...`、通知 `...1E4D-4BD9-BA61-...` 变体）；也有 `FFE0`、`18F0`。
- **指令族**：ESC/POS、TSPL、CPCL、ZPL，按机型。
- **关键 API**：`net.posprinter` 包 `PrinterBinder`/`IMyBinder`：
  `ConnectBtPort(mac, callback)` / `ConnectNetPort(ip, port, callback)` / `ConnectUsbPort(context, path, callback)`。
- **结论：好**，但 UUID 因型号/固件而异，需同时支持 `FF00` 与 `49535343`。

### 11. NIIMBOT（精臣）

- **SDK**：JCPrintApi（Android AAR，公开流通 V4.1.1）。
  [开发者工单入口](https://www.niimbot.com/en/developer_work_order)
- **连接**：BLE 为主；部分机型（K3）走 SPP；支持 BLE 配置 WiFi。
- **BLE UUID**（社区验证，NiimBlueLib）：Service `e7810a71-73ae-499d-8c15-faa9aef0c3f2`、
  特征 `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f`（Notify + Write No Response）。
  [NiimBlueLib 连接说明](https://printers.niim.blue/interfacing/connecting/)
- **指令族**：**私有二进制协议**（`55 55 ... AA AA` 帧：命令字+长度+数据+校验），
  **不是** TSPL/CPCL/ESC-POS/ZPL。
- **关键 API**：`JCPrintApi.getInstance(callbacks, transport)`、`startPrintJob()`、`drawLabelText()`、`commitData()`。
- **结论**：**不能靠通用指令族直连**；BLE 连接可行，数据层是私有协议，需官方 SDK 或社区协议实现。

### 12. 得力 Deli

- **SDK**：得力打印机 Android SDK（核心类 `PrinterInstance`，V1.6/SDK5.5，售前提供 jar/aar）；
  公开资料见[得力云开放文档](https://doc.delicloud.com/v3/)。
- **连接**：经典 SPP（DL-886AW，固定 9600）、串口、WiFi、USB；**未见官方 BLE 打印 SDK 文档**。
- **BLE UUID**：无公开资料，只能运行时枚举。
- **指令族**：标签机 TSPL 为主（DL-886AW），小票机 ESC/POS；云端为 JSON 消息协议。
- **关键 API**：`PrinterInstance.getPrinterInstance(BluetoothDevice, Handler)`、`openConnection()`、`printText()`。
- **结论**：SPP + TSPL 可行（Android 经典蓝牙）；BLE 不建议作为通用通路。

## 四、横向对比表

### 4.1 连接方式、指令族、平台与 UUID/端口

| 品牌 | 连接方式 | 指令族 | 平台 | BLE UUID 或端口 |
|---|---|---|---|---|
| Zebra | BLE、SPP、TCP、USB | ZPL II、CPCL、EPL（旧） | Android/iOS/Windows/Linux | BLE `38EB4A80...` / 写 `38EB4A82...`；TCP 9100 |
| Honeywell | SPP、TCP、USB；部分 BLE | DPL、CPCL、ZPL、ESC/P、IPL | Android/iOS/Windows | BLE 未公开；SPP `bt://MAC`；TCP 9100 |
| TSC | SPP、MFi BT、TCP、USB；部分 BLE | TSPL/TSPL2；部分 EPL/ZPL/ESC-POS | Android/iOS/Windows/WinCE | BLE 未公开；TCP 9100 |
| Brother | WiFi、SPP、BLE、USB OTG | 私有光栅、P-touch Template；部分 ZPL/CPCL 仿真 | Android/iOS | BLE 由 SDK 封装；WiFi TCP |
| Epson | WiFi/Ethernet、SPP、BLE、USB | ESC/POS、ePOS-Print XML | Android/iOS/JS | BLE 由 SDK 封装；TCP 9100、8008/8009/8043 |
| Star | TCP、USB、SPP、BLE | StarPRNT / Star Line Mode、ESC/POS 仿真 | Android/iOS/Windows/Linux | BLE 未公开；TCP 9100 |
| HP | WiFi IPP、Raw 9100、USB | PCL 5/6、PCLm、PostScript、PDF | Android/iOS/Windows | 无 BLE 打印；IPP 631、Raw 9100 |
| 佳博 | BLE、SPP、USB、WiFi | TSPL/TSC、CPCL、ESC/POS、ZPL | Android/iOS/Windows/Linux/Web/uni-app | BLE `FF00/FF02`；TCP 9100 |
| 汉印 | BLE、SPP、USB、WiFi | ESC/POS、CPCL、TSPL、ZPL | Android/iOS/Windows/Linux/macOS/HarmonyOS | BLE `FF00/FF02`（机型相关）；TCP 9100 |
| 芯烨 | BLE、SPP、USB、WiFi | ESC/POS、TSPL、CPCL、ZPL | Android/iOS/Windows/Linux/HarmonyOS | BLE `FF00/FF02` 或 `49535343...`；TCP 9100 |
| NIIMBOT | BLE 为主、部分 SPP | 私有二进制协议 | Android/iOS（官方支持） | BLE `e7810a71...` / `bef8d6c9...` |
| 得力 | SPP、串口、WiFi、USB | TSPL（标签）、ESC/POS（小票）、云 JSON | Android/Windows/云 | BLE 未公开；SPP；TCP 9100（常见） |

### 4.2 第三方 App 直连可行性分级

| 品牌 | BLE 裸连 | 推荐通路 | 说明 |
|---|---|---|---|
| Zebra | 可（官方文档） | BLE GATT / SPP / TCP 9100 | 最推荐，ZPL/CPCL 直接写 |
| 佳博 | 可（`FF00/FF02`） | BLE / SPP / TCP 9100 | 国产通用通路代表 |
| 汉印 | 可（部分机型） | BLE / SPP / TCP 9100 | 需按型号确认指令族 |
| 芯烨 | 可（部分机型） | BLE / SPP / TCP 9100 | 注意 `FF00` 与 `49535343` 两种 |
| Honeywell | 部分/不推荐 | SPP / TCP | BLE 无公开 UUID |
| TSC | 部分/不推荐 | SPP / TCP 9100 | iOS 走 MFi |
| Epson | 部分/需 SDK | TCP 9100 / SPP | BLE 走 ePOS SDK |
| Star | 部分/需 SDK | SPP / TCP 9100 | BLE 私有协议 |
| Brother | 不可 | 官方 SDK（BLE/SPP/WiFi） | iOS 经典蓝牙需 MFi+PPID |
| NIIMBOT | 不可（协议私有） | 官方 SDK / 私有 BLE 协议 | 标准 TSPL 无效 |
| HP | 不适用 | IPP 631 / Raw 9100 | 无 BLE 打印 |
| 得力 | 不推荐 | SPP / TCP | 无公开 BLE 文档 |

## 五、对本项目的落地（已实施）

sdk 模块（[sdk/README.md](sdk/README.md)）按本调研整合：

1. **VendorDB 扩充**：新增 Zebra（官方 BLE UUID 置顶）、TSC、Honeywell、Epson、Star、
   Brother、得力 7 个品牌 Profile；补 `18F0/2AF1/2AF0` 通用打印服务、`FFE2` 写特征、
   `49535343` 通知变体；NIIMBOT 指令族改标 UNKNOWN（防 AUTO 盲发标准指令）。
2. **新增 TCP 9100 通道**（`TcpPrinterConnection`）：Zebra/Epson/Star/TSC/佳博/汉印/芯烨/得力
   台式机通用，亦是跨 OS 平台最大公约数；无分片/流控负担。
3. **统一门面 `PrinterClient`**：BLE/SPP/USB/TCP 四通道一个入口、统一状态码与回调。
4. **已有真机验证路径不动**：佳博 ff00/ff02、汉印 ME35（TSPL）、BMA（CPCL）原样保留。

## 六、参考链接

- Zebra：[Link-OS SDK](https://www.zebra.com/us/en/software/printer-software/multiplatform-sdk.html) ｜
  [BLE AppNote](https://www.zebra.com/content/dam/zebra/software/en/application-notes/AppNote-BlueToothLE-v4.pdf) ｜
  [Android 示例](https://github.com/ZebraDevs/LinkOS-Android-Samples)
- Honeywell：[Intermec Printing SDK 说明](https://support.honeywellaidc.com/s/article/Where-to-download-Intermec-printing-SDK-for-Android)
- TSC：[下载（EMEA）](https://emea.tscprinters.com/en/downloads) ｜
  [TSPL/TSPL2 手册](https://www.tscprinters.com/EN/DownloadFile/readpdf/support/4356/TSPL_TSPL2_Programming.pdf?file_type=0)
- Brother：[Mobile SDK 下载](https://support.brother.com/g/s/es/dev/en/mobilesdk/download/index.html) ｜
  [支持机型](https://support.brother.com/g/s/es/htmldoc/mobilesdk/overview/supported-printer.html)
- Epson：[ePOS SDK](https://download4.epson.biz/sec_pubs/pos/reference_en/technology/epson_epos_sdk.html) ｜
  [ESC/POS 参考](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/index.html)
- Star：[开发者页](https://starmicronics.com/support/developers/) ｜
  [StarPRNT Android](https://github.com/star-micronics/StarPRNT-SDK-Android-Java)
- HP：[Developers - Printing](https://developers.hp.com/printing-internet-developers)（Mobile SDK 已弃用）
- 佳博：[开发文档/下载](https://www.gprinter.net/kaifa01/) ｜
  [云打印文档](http://cloud.poscom.cn/index.php?catid=18)
- 汉印：[开发工具下载](https://www.hprt.com.cn/pro_files.html?classifyid=64&pid=126)
- 芯烨：[SDK 下载](https://pt.xprintertech.com/sdk.html) ｜ [国内下载](https://www.xprinter.net/Driver_Download/p-0-12.html)
- 精臣：[开发者工单](https://www.niimbot.com/en/developer_work_order) ｜
  [NiimBlueLib](https://printers.niim.blue/interfacing/connecting/) ｜
  [niimbluelib GitHub](https://github.com/MultiMote/niimbluelib)
- 得力：[得力云开放文档](https://doc.delicloud.com/v3/) ｜ [云打印接入协议](https://doc.delicloud.com/v3/iot/print/)

> 说明：TSC Android SDK PDF、得力 `prt.docx`、Star `49535343` 等属第三方镜像或社区资料，
> 正文已标注；生产接入前建议向厂商索取最新官方 SDK 与协议文档。
