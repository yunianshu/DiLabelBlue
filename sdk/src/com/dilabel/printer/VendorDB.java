package com.dilabel.printer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 多厂家打印机厂商识别与 GATT 配置库。
 *
 * 学习自 Dlabel 云标签的多厂家适配思路，分三层：
 *  1. 名称前缀识别（对应 assets/printlist.list：printer name 前缀 -> 协议 type）
 *  2. 已知厂商 GATT 服务/特征 UUID 表（对应各厂商 SDK 内置的 UUID）
 *  3. 未知厂商的自动发现降级策略（枚举所有 service，找可写特征 + notify 特征）
 *
 * 品牌表扩充依据「主流打印机SDK连接方式调研.md」（2026-11-12）：
 * Zebra 是唯一官方公开 BLE GATT 的厂商；国产机（佳博/汉印/芯烨及白牌）大量共用
 * FF00/FFE0/18F0 透传服务；Honeywell/TSC/Epson/Star/Brother/得力的 BLE UUID 不公开，
 * 候选 UUID 仅作连接尝试，最终以特征属性 + 指令族探测为准。
 */
public final class VendorDB {

    /** 打印指令协议族 */
    public enum Protocol {
        ESC_POS, TSPL, CPCL, ZPL, UNKNOWN;

        public static Protocol fromTypeCode(int type) {
            // 对应 printlist.list 中 type 字段的语义（各厂商固件指令族）
            switch (type) {
                case 2: return CPCL;
                case 3: return TSPL;
                case 4: return ESC_POS;
                case 5: return ZPL;
                default: return UNKNOWN;
            }
        }
    }

    /** 一个厂商/协议族的 BLE GATT 配置 */
    public static class Profile {
        public final String brand;
        public final List<String> namePrefixes;   // 蓝牙名称前缀，小写
        public final Protocol protocol;
        public final List<String> serviceUuids;   // 候选服务 UUID（完整小写）
        public final List<String> writeUuids;     // 候选写特征
        public final List<String> notifyUuids;    // 候选通知特征

        Profile(String brand, Protocol protocol, String[] prefixes,
                String[] services, String[] writes, String[] notifies) {
            this.brand = brand;
            this.protocol = protocol;
            this.namePrefixes = Arrays.asList(prefixes);
            this.serviceUuids = Arrays.asList(services);
            this.writeUuids = Arrays.asList(writes);
            this.notifyUuids = Arrays.asList(notifies);
        }
    }

    public static final String BASE = "0000%s-0000-1000-8000-00805f9b34fb";

    public static final String SVC_GPRINTER = String.format(BASE, "ff00"); // 佳博
    public static final String CHR_GPRINTER_WRITE = String.format(BASE, "ff02");
    public static final String CHR_GPRINTER_NOTIFY = String.format(BASE, "ff01");

    public static final String SVC_FE0 = String.format(BASE, "ffe0");      // 芯烨/精臣/汉印部分型号
    public static final String CHR_FE1 = String.format(BASE, "ffe1");

    public static final String SVC_FFF0 = String.format(BASE, "fff0");     // FeasyCom 模块
    public static final String CHR_FFF1 = String.format(BASE, "fff1");
    public static final String CHR_FFF2 = String.format(BASE, "fff2");

    public static final String SVC_FF02 = String.format(BASE, "ff02");     // 部分汉印 MT/A300
    public static final String CHR_FF02_WRITE = String.format(BASE, "ff02");
    public static final String CHR_FF01_NOTIFY = String.format(BASE, "ff01");

    /** 汉印及兼容机型（NETUM 等）的自定义 128bit 服务（公开资料已证实服务 UUID） */
    public static final String SVC_HPRT =
            "e7810a71-73ae-499d-8c15-faa9aef0c3f2";
    public static final String CHR_HPRT_WRITE =
            "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f";
    public static final String CHR_HPRT_NOTIFY =
            "bef8d7c9-9c21-4c9e-b632-bd58c1009f9f";

    /** Nordic UART / Microchip USP（大量白牌打印机借用） */
    public static final String SVC_NUS =
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String CHR_NUS_WRITE =
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String CHR_NUS_NOTIFY =
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    public static final String SVC_USP =
            "49535343-fe7d-4ae5-8fa9-9fafd205e455";
    public static final String CHR_USP_WRITE =
            "49535343-8841-43f4-a8d4-ecbe34729bb3";
    public static final String CHR_USP_NOTIFY =
            "49535343-1e4d-4d49-bd57-d68e7abe8de9";
    /** Microchip/ISSC 透传通知的另一变体（芯烨 XP-58IIH 实测用的是这个） */
    public static final String CHR_USP_NOTIFY2 =
            "49535343-1e4d-4bd9-ba61-23c647249616";

    /** Zebra Link-OS 官方 BLE Parser 服务（唯一官方文档化 GATT，AppNote-BlueToothLE-v4）。
     *  ZPL/CPCL 直写；单次写 ≤ min(MTU,512)，回包走 Indication（CCCD 写 0x0002） */
    public static final String SVC_ZEBRA =
            "38eb4a80-c570-11e3-9507-0002a5d5c51b";
    public static final String CHR_ZEBRA_WRITE =
            "38eb4a82-c570-11e3-9507-0002a5d5c51b";
    public static final String CHR_ZEBRA_READ =
            "38eb4a81-c570-11e3-9507-0002a5d5c51b";

    /** 18F0 通用打印服务（大量迷你/POS 打印机借用）：写 2AF1 / 通知 2AF0 */
    public static final String SVC_18F0 = String.format(BASE, "18f0");
    public static final String CHR_2AF1_WRITE = String.format(BASE, "2af1");
    public static final String CHR_2AF0_NOTIFY = String.format(BASE, "2af0");

    /** FFE0 模块的写特征（HM-10/CC2541 类模块：FFE2 写 / FFE1 通知） */
    public static final String CHR_FE2 = String.format(BASE, "ffe2");

    /** 经典蓝牙 SPP 通道（部分台式标签机走 SPP） */
    public static final String SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb";

    private static final List<Profile> PROFILES = new ArrayList<>();

    static {
        // 佳博 Gprinter / 思迅：大部分型号 ff00 服务
        PROFILES.add(new Profile("佳博 Gprinter", Protocol.ESC_POS,
                new String[]{"gps", "gprinter", "gk", "gt", "zn", "xp-", "nii", "sz"},
                new String[]{SVC_GPRINTER},
                new String[]{CHR_GPRINTER_WRITE},
                new String[]{CHR_GPRINTER_NOTIFY}));

        // 汉印 HPRT：便携款多走 ffe0/ff00 或自定义 128bit 服务（A300/T300 社区实测 ff00/ff02）
        PROFILES.add(new Profile("汉印 HPRT", Protocol.ESC_POS,
                new String[]{"hm-", "hm ", "hprt", "t200", "a300", "m320", "n41", "n31", "jcp", "d31", "d21", "d11"},
                new String[]{SVC_FE0, SVC_HPRT, SVC_GPRINTER, SVC_FF02, SVC_18F0},
                new String[]{CHR_FE2, CHR_FE1, CHR_HPRT_WRITE, CHR_GPRINTER_WRITE, CHR_FF02_WRITE, CHR_2AF1_WRITE},
                new String[]{CHR_FE1, CHR_HPRT_NOTIFY, CHR_GPRINTER_NOTIFY, CHR_FF01_NOTIFY, CHR_2AF0_NOTIFY}));

        // 汉印 ME35/ME35PLUS 便携标签机（2026-11-11 真机验证）：
        // BLE ff00/ff02 通道 + TSPL 指令族打印命中（文字+条码）；ESC/POS、CPCL 无反应
        PROFILES.add(new Profile("汉印 ME35 系列", Protocol.TSPL,
                new String[]{"me35"},
                new String[]{SVC_GPRINTER, SVC_FE0, SVC_HPRT},
                new String[]{CHR_GPRINTER_WRITE, CHR_FE1, CHR_HPRT_WRITE},
                new String[]{CHR_GPRINTER_NOTIFY, CHR_FE1, CHR_HPRT_NOTIFY}));

        // 芯烨 Xprinter / 白牌热敏（XP-58IIH 实测走 49535343 透传，通知用变体 1E4D-4BD9）
        PROFILES.add(new Profile("芯烨 Xprinter", Protocol.ESC_POS,
                new String[]{"xp", "xprinter", "pos", "p58", "thermal"},
                new String[]{SVC_FE0, SVC_USP, SVC_NUS, SVC_18F0},
                new String[]{CHR_FE2, CHR_FE1, CHR_USP_WRITE, CHR_NUS_WRITE, CHR_2AF1_WRITE},
                new String[]{CHR_FE1, CHR_USP_NOTIFY, CHR_USP_NOTIFY2, CHR_NUS_NOTIFY, CHR_2AF0_NOTIFY}));

        // FeasyCom 蓝牙模块（Dlabel 内置其 SDK）
        PROFILES.add(new Profile("FeasyCom", Protocol.ESC_POS,
                new String[]{"fsc", "feasy", "bp1", "bp2"},
                new String[]{SVC_FFF0},
                new String[]{CHR_FFF2},
                new String[]{CHR_FFF1}));

        // 精臣 NIIMBOT（D 系列/B 系列）：调研证实为私有二进制协议（55 55 ... AA AA 帧），
        // 标准 TSPL/ESC-POS 盲发无效；BLE 通道本身可连（e7810a71 服务，与汉印部分模块共用），
        // 打印需官方 JCPrintApi 或社区 NiimBlueLib。指令族标 UNKNOWN 防止 AUTO 误发标准指令。
        PROFILES.add(new Profile("精臣 NIIMBOT", Protocol.UNKNOWN,
                new String[]{"d11", "d21", "d31", "d61", "b1", "b21", "b31", "jd", "niimbot"},
                new String[]{SVC_FE0, SVC_FFF0, SVC_NUS},
                new String[]{CHR_FE2, CHR_FE1, CHR_FFF2, CHR_NUS_WRITE},
                new String[]{CHR_FE1, CHR_FFF1, CHR_NUS_NOTIFY}));

        // BMA 系列白牌标签机（2026-11-11 真机验证）：
        // 降级枚举命中 ff00/ff02 + CPCL 指令族打印命中；ESC/POS、TSPL 无反应
        PROFILES.add(new Profile("BMA 系列", Protocol.CPCL,
                new String[]{"bma"},
                new String[]{SVC_GPRINTER, SVC_FE0},
                new String[]{CHR_GPRINTER_WRITE, CHR_FE1},
                new String[]{CHR_GPRINTER_NOTIFY, CHR_FE1}));

        // ---- 以下按「主流打印机SDK连接方式调研.md」扩充（2026-11-12）----

        // Zebra（斑马）：唯一官方公开 BLE GATT 的厂商（Link-OS AppNote-BlueToothLE-v4）。
        // ZPL/CPCL 直写 Parser 服务；SPP/TCP 9100/USB 均官方支持
        PROFILES.add(new Profile("斑马 Zebra", Protocol.ZPL,
                new String[]{"zebra", "zq5", "zq6", "zd4", "zd6"},
                new String[]{SVC_ZEBRA, SVC_GPRINTER, SVC_FE0},
                new String[]{CHR_ZEBRA_WRITE, CHR_GPRINTER_WRITE, CHR_FE2, CHR_FE1},
                new String[]{CHR_ZEBRA_READ, CHR_GPRINTER_NOTIFY, CHR_FE1}));

        // TSC：台式标签机，TSPL/TSPL2；Android 主走 SPP，WiFi 走 TCP 9100，BLE 无官方公开 UUID
        PROFILES.add(new Profile("TSC", Protocol.TSPL,
                new String[]{"tsc", "alpha-"},
                new String[]{SVC_GPRINTER, SVC_FE0, SVC_USP, SVC_NUS},
                new String[]{CHR_GPRINTER_WRITE, CHR_FE2, CHR_FE1, CHR_USP_WRITE, CHR_NUS_WRITE},
                new String[]{CHR_GPRINTER_NOTIFY, CHR_FE1, CHR_USP_NOTIFY, CHR_USP_NOTIFY2, CHR_NUS_NOTIFY}));

        // Honeywell（霍尼韦尔）：指令族 DPL/CPCL/ZPL（DPL 未实现，靠探测识别 CPCL/ZPL）；
        // 官方推荐 SPP/TCP，BLE 无公开 UUID（RP 系列为双模，SPP 最稳）
        PROFILES.add(new Profile("霍尼韦尔 Honeywell", Protocol.UNKNOWN,
                new String[]{"rp2", "rp4", "pr2", "pr3", "mpd"},
                new String[]{SVC_GPRINTER, SVC_FE0, SVC_NUS, SVC_USP},
                new String[]{CHR_GPRINTER_WRITE, CHR_FE2, CHR_FE1, CHR_NUS_WRITE, CHR_USP_WRITE},
                new String[]{CHR_GPRINTER_NOTIFY, CHR_FE1, CHR_NUS_NOTIFY, CHR_USP_NOTIFY}));

        // Epson：ESC/POS 小票机；TCP 9100 raw 是第三方最稳通路，BLE 由 ePOS SDK 封装
        PROFILES.add(new Profile("爱普生 Epson", Protocol.ESC_POS,
                new String[]{"tm-"},
                new String[]{SVC_FE0, SVC_GPRINTER, SVC_NUS, SVC_USP, SVC_18F0},
                new String[]{CHR_FE2, CHR_FE1, CHR_GPRINTER_WRITE, CHR_NUS_WRITE, CHR_USP_WRITE, CHR_2AF1_WRITE},
                new String[]{CHR_FE1, CHR_GPRINTER_NOTIFY, CHR_NUS_NOTIFY, CHR_USP_NOTIFY, CHR_USP_NOTIFY2, CHR_2AF0_NOTIFY}));

        // Star Micronics：StarPRNT 专有指令族（部分机型有 ESC/POS 仿真，靠探测识别）
        PROFILES.add(new Profile("Star Micronics", Protocol.UNKNOWN,
                new String[]{"sm-l", "sm-s", "sm-t", "tsp", "mcp31"},
                new String[]{SVC_FE0, SVC_USP, SVC_GPRINTER, SVC_18F0},
                new String[]{CHR_FE2, CHR_FE1, CHR_USP_WRITE, CHR_GPRINTER_WRITE, CHR_2AF1_WRITE},
                new String[]{CHR_FE1, CHR_USP_NOTIFY, CHR_USP_NOTIFY2, CHR_GPRINTER_NOTIFY, CHR_2AF0_NOTIFY}));

        // Brother：私有光栅协议（P-touch/Raster），标准指令族无效，打印需官方 SDK；
        // 部分 RJ/TD 机型有 ZPL/CPCL 仿真（探测可能命中）
        PROFILES.add(new Profile("Brother", Protocol.UNKNOWN,
                new String[]{"pj-", "ql-", "td-", "rj-", "pt-"},
                new String[]{SVC_FE0, SVC_NUS, SVC_GPRINTER},
                new String[]{CHR_FE2, CHR_FE1, CHR_NUS_WRITE, CHR_GPRINTER_WRITE},
                new String[]{CHR_FE1, CHR_NUS_NOTIFY, CHR_GPRINTER_NOTIFY}));

        // 得力 Deli：标签机 TSPL / 小票机 ESC-POS；官方资料主走 SPP，BLE 无公开 UUID
        PROFILES.add(new Profile("得力 Deli", Protocol.TSPL,
                new String[]{"dl-", "deli"},
                new String[]{SVC_FE0, SVC_GPRINTER, SVC_USP},
                new String[]{CHR_FE2, CHR_FE1, CHR_GPRINTER_WRITE, CHR_USP_WRITE},
                new String[]{CHR_FE1, CHR_GPRINTER_NOTIFY, CHR_USP_NOTIFY, CHR_USP_NOTIFY2}));

        // Dlabel printlist.list 中出现的型号前缀（type 2/3/4/5），未知品牌兜底
        PROFILES.add(new Profile("DiLabel 适配型号", Protocol.UNKNOWN,
                new String[]{"printer", "m320", "1324d", "qr380a", "3120tu", "zh", "bhs",
                        "rf", "qr", "hm-a300", "d31", "d21", "mht-", "pd", "km-202bt",
                        "k319", "k419", "k219"},
                new String[]{SVC_FE0, SVC_GPRINTER, SVC_FFF0, SVC_NUS, SVC_USP, SVC_18F0},
                new String[]{CHR_FE2, CHR_FE1, CHR_GPRINTER_WRITE, CHR_FFF2, CHR_NUS_WRITE, CHR_USP_WRITE, CHR_2AF1_WRITE},
                new String[]{CHR_FE1, CHR_GPRINTER_NOTIFY, CHR_FFF1, CHR_NUS_NOTIFY, CHR_USP_NOTIFY, CHR_USP_NOTIFY2, CHR_2AF0_NOTIFY}));
    }

    /**
     * 按蓝牙名称前缀匹配厂商（与 Dlabel printlist.list 同思路：纯前缀表）。
     * 不做 contains 模糊匹配——真机验证发现随机广播名（如 NZPIGki249...）
     * 会因子串巧合误命中厂商（"gki" 含 "gk"→佳博），前缀匹配才不会误报。
     */
    public static Profile matchByName(String deviceName) {
        if (deviceName == null) return null;
        String lower = deviceName.trim().toLowerCase();
        for (Profile p : PROFILES) {
            for (String prefix : p.namePrefixes) {
                if (lower.startsWith(prefix)) {
                    return p;
                }
            }
        }
        return null;
    }

    public static List<Profile> all() {
        return PROFILES;
    }
}
