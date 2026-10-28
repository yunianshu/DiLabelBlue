package com.example.bleprinter;

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

        // 汉印 HPRT：便携款多走 ffe0 或自定义 128bit 服务
        PROFILES.add(new Profile("汉印 HPRT", Protocol.ESC_POS,
                new String[]{"hm-", "hm ", "a300", "m320", "n41", "n31", "jcp", "d31", "d21", "d11"},
                new String[]{SVC_FE0, SVC_HPRT, SVC_FF02},
                new String[]{CHR_FE1, CHR_HPRT_WRITE, CHR_FF02_WRITE},
                new String[]{CHR_FE1, CHR_HPRT_NOTIFY, CHR_FF01_NOTIFY}));

        // 芯烨 Xprinter / 白牌热敏
        PROFILES.add(new Profile("芯烨 Xprinter", Protocol.ESC_POS,
                new String[]{"xp", "xprinter", "pos", "p58", "thermal"},
                new String[]{SVC_FE0, SVC_USP, SVC_NUS},
                new String[]{CHR_FE1, CHR_USP_WRITE, CHR_NUS_WRITE},
                new String[]{CHR_FE1, CHR_USP_NOTIFY, CHR_NUS_NOTIFY}));

        // FeasyCom 蓝牙模块（Dlabel 内置其 SDK）
        PROFILES.add(new Profile("FeasyCom", Protocol.ESC_POS,
                new String[]{"fsc", "feasy", "bp1", "bp2"},
                new String[]{SVC_FFF0},
                new String[]{CHR_FFF2},
                new String[]{CHR_FFF1}));

        // 精臣 NIIMBOT（D 系列/B 系列，type 4/5 常见）
        PROFILES.add(new Profile("精臣 NIIMBOT", Protocol.ESC_POS,
                new String[]{"d11", "d21", "d31", "d61", "b1", "b21", "b31", "jd", "niimbot"},
                new String[]{SVC_FE0, SVC_FFF0, SVC_NUS},
                new String[]{CHR_FE1, CHR_FFF2, CHR_NUS_WRITE},
                new String[]{CHR_FE1, CHR_FFF1, CHR_NUS_NOTIFY}));

        // Dlabel printlist.list 中出现的型号前缀（type 2/3/4/5），未知品牌兜底
        PROFILES.add(new Profile("DiLabel 适配型号", Protocol.UNKNOWN,
                new String[]{"printer", "m320", "1324d", "qr380a", "3120tu", "zh", "bhs",
                        "rf", "qr", "hm-a300", "d31", "d21", "mht-", "pd", "km-202bt",
                        "k319", "k419", "k219"},
                new String[]{SVC_FE0, SVC_GPRINTER, SVC_FFF0, SVC_NUS, SVC_USP},
                new String[]{CHR_FE1, CHR_GPRINTER_WRITE, CHR_FFF2, CHR_NUS_WRITE, CHR_USP_WRITE},
                new String[]{CHR_FE1, CHR_GPRINTER_NOTIFY, CHR_FFF1, CHR_NUS_NOTIFY, CHR_USP_NOTIFY}));
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
