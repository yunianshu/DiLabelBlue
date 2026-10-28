package com.example.bleprinter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * 三大热敏/标签指令族的最小实现（打印一张中文测试标签）。
 * 各厂商机型基本落在这三族：
 *  - ESC/POS：小票机 + 部分标签机（佳博、芯烨、汉印小票款）
 *  - TSPL/TPCL：台式标签机（佳博 Z 系、TSC 兼容款）
 *  - CPCL：便携标签机（Zebra 移动款、汉印 A300 等）
 */
public final class LabelProtocols {

    private static final Charset GBK = Charset.forName("GBK");
    private static final byte[] INIT = {0x1b, 0x40};           // ESC @ 初始化
    private static final byte[] CN_FONT = {0x1c, 0x26, 0x03, 0x00, 0x1c, 0x26, 0x00}; // FS & 中文模式
    private static final byte[] CUT = {0x0a, 0x0a, 0x0a, 0x1d, 0x56, 0x42, 0x00};     // 走纸+切刀

    /** ESC/POS 测试标签：居中标题 + 正文 + 条码 */
    public static byte[] escPosTest(String title, String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(INIT, 0, INIT.length);
        out.write(CN_FONT, 0, CN_FONT.length);
        byte[] alignCenter = {0x1b, 0x61, 0x01};
        byte[] alignLeft = {0x1b, 0x61, 0x00};
        byte[] big = {0x1d, 0x21, 0x11};                       // GS ! 倍大
        byte[] normal = {0x1d, 0x21, 0x00};
        out.write(alignCenter, 0, alignCenter.length);
        out.write(big, 0, big.length);
        writeLine(out, title + "\n\n");
        out.write(normal, 0, normal.length);
        out.write(alignLeft, 0, alignLeft.length);
        writeLine(out, "----------------\n");
        writeLine(out, body + "\n");
        writeLine(out, "----------------\n\n");
        // CODE128 条码
        byte[] barcode = {0x1d, 0x68, 0x50, 0x1d, 0x77, 0x02, 0x1d, 0x6b, 0x49, 0x0c,
                0x7b, 0x42};
        out.write(barcode, 0, barcode.length);
        write(out, "12345678".getBytes(GBK));
        out.write(CUT, 0, CUT.length);
        return out.toByteArray();
    }

    /** TSPL 测试标签：文本 + 条码（用 SIZE 声明纸宽高，CLS 清空，PRINT 出纸） */
    public static byte[] tsplTest(String title, String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "SIZE 60 mm,40 mm\r\nGAP 2 mm,0\r\nDIRECTION 1,0\r\nCLS\r\n".getBytes(GBK));
        write(out, "TEXT 100,30,\"TSS24.BF2\",0,1,1,\"".getBytes(GBK));
        write(out, title.getBytes(GBK));
        write(out, "\"\r\n".getBytes(GBK));
        write(out, "TEXT 100,90,\"TSS24.BF2\",0,1,1,\"".getBytes(GBK));
        write(out, body.getBytes(GBK));
        write(out, "\"\r\n".getBytes(GBK));
        write(out, "BARCODE 100,150,\"128\",60,1,0,2,2,\"12345678\"\r\n".getBytes(GBK));
        write(out, "PRINT 1,1\r\n".getBytes(GBK));
        return out.toByteArray();
    }

    /** CPCL 测试标签（便携标签机指令族） */
    public static byte[] cpclTest(String title, String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "! 0 200 200 320 1\r\n".getBytes(GBK));
        write(out, "PAGE-WIDTH 480\r\n".getBytes(GBK));
        write(out, ("CENTER TEXT 4 0 30 " + title + "\r\n").getBytes(GBK));
        write(out, ("TEXT 4 0 60 " + body + "\r\n").getBytes(GBK));
        write(out, "BARCODE 128 1 1 50 60 150 12345678\r\n".getBytes(GBK));
        write(out, "FORM\r\nPRINT\r\n".getBytes(GBK));
        return out.toByteArray();
    }

    /** 通用探测指令（适配测试用，多数机会回 ACK 或状态） */
    public static byte[] probeCommand() {
        // ESC/POS 实时状态 DLE EOT 1：返回 1 字节状态
        return new byte[]{0x10, 0x04, 0x01};
    }

    private static void writeLine(ByteArrayOutputStream out, String s) {
        write(out, s.getBytes(GBK));
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
