import com.dilabel.printer.LabelProtocols;
import com.dilabel.printer.TcpPrinterConnection;
import com.dilabel.printer.VendorDB;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SDK 纯 JVM 冒烟测试（不依赖 Android 设备）。
 *
 * 覆盖 SDK 中平台无关的部分：
 *  1. VendorDB 前缀识别：主流品牌命中 + 旧误报回归（#24 随机名不得误命中）
 *  2. 四指令族字节构建：ESC/POS、TSPL、CPCL、ZPL 头部正确
 *  3. 探测指令字节正确
 *  4. TCP 9100 通道真实收发：本机 loopback 全双工验证（新通道的功能实测）
 *
 * 运行: java -cp build/printer-sdk.jar:build/test JvmSmokeTest   (Linux/macOS)
 *       java -cp "build/printer-sdk.jar;build/test" JvmSmokeTest (Windows)
 * 编译: javac -cp build/printer-sdk.jar -d build/test test/JvmSmokeTest.java
 */
public class JvmSmokeTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        testVendorMatching();
        testProtocolBuilders();
        testProbes();
        testTcpLoopback();
        System.out.println("\n==== 结果: " + passed + " 通过, " + failed + " 失败 ====");
        if (failed > 0) System.exit(1);
    }

    // ---------- 1. VendorDB 前缀识别 ----------

    static void testVendorMatching() {
        // 主流品牌命中（调研扩充的新 Profile）
        checkEq("Zebra ZQ511", "斑马 Zebra", VendorDB.matchByName("ZQ511-203").brand);
        checkEq("Zebra 明示名", "斑马 Zebra", VendorDB.matchByName("zebra-zd421").brand);
        checkEq("TSC", "TSC", VendorDB.matchByName("TSC TDP-225").brand);
        checkEq("TSC Alpha", "TSC", VendorDB.matchByName("Alpha-30L").brand);
        checkEq("Honeywell RP4", "霍尼韦尔 Honeywell", VendorDB.matchByName("RP4e").brand);
        checkEq("Epson TM", "爱普生 Epson", VendorDB.matchByName("TM-T88VII").brand);
        checkEq("Star SM-L200", "Star Micronics", VendorDB.matchByName("SM-L200").brand);
        checkEq("Brother TD", "Brother", VendorDB.matchByName("TD-4550DNWB").brand);
        checkEq("得力", "得力 Deli", VendorDB.matchByName("DL-886AW").brand);
        // 真机验证过的旧路径不回归
        checkEq("ME35PLUS", "汉印 ME35 系列", VendorDB.matchByName("ME35PLUS").brand);
        checkEq("BMA3", "BMA 系列", VendorDB.matchByName("BMA3").brand);
        checkEq("佳博 GT1", "佳博 Gprinter", VendorDB.matchByName("GT1").brand);
        // 协议族提示
        checkEq("Zebra->ZPL", "ZPL", VendorDB.matchByName("ZQ511").protocol.name());
        checkEq("TSC->TSPL", "TSPL", VendorDB.matchByName("TSC-Alpha-30L").protocol.name());
        checkEq("Epson->ESC_POS", "ESC_POS", VendorDB.matchByName("TM-P80II").protocol.name());
        checkEq("NIIMBOT->UNKNOWN(私有协议防误发)", "UNKNOWN",
                VendorDB.matchByName("B21").protocol.name());
        // 误报回归（问题 #24）：子串巧合不得命中，必须前缀匹配
        checkTrue("随机名 gki 不误命中佳博", VendorDB.matchByName("NZPIGki249") == null);
        checkTrue("未知设备返回 null", VendorDB.matchByName("NothingGT30") == null);
    }

    // ---------- 2. 四指令族构建 ----------

    static void testProtocolBuilders() throws Exception {
        byte[] esc = LabelProtocols.escPosTest("标题", "内容");
        checkTrue("ESC/POS 以 ESC@ 初始化", esc.length > 4
                && esc[0] == 0x1b && esc[1] == 0x40);
        byte[] tspl = LabelProtocols.tsplTest("标题", "内容");
        checkTrue("TSPL 以 SIZE 开头", new String(tspl, "GBK").startsWith("SIZE 60 mm,40 mm"));
        byte[] cpcl = LabelProtocols.cpclTest("标题", "内容");
        checkTrue("CPCL 以 ! 头开始", new String(cpcl, "GBK").startsWith("! 0 200 200"));
        byte[] zpl = LabelProtocols.zplTest("标题", "内容");
        checkTrue("ZPL 以 ^XA 开始 ^XZ 结束",
                new String(zpl, "GBK").startsWith("^XA") && new String(zpl, "GBK").trim().endsWith("^XZ"));
        checkTrue("四族均有实质内容", esc.length > 50 && tspl.length > 50
                && cpcl.length > 50 && zpl.length > 50);
    }

    // ---------- 3. 探测指令 ----------

    static void testProbes() {
        checkTrue("ESC/POS 探测 = DLE EOT 1",
                Arrays.equals(LabelProtocols.probeEscPos(), new byte[]{0x10, 0x04, 0x01}));
        checkTrue("TSPL 探测 = ~!I", Arrays.equals(LabelProtocols.probeTspl(), "~!I\r\n".getBytes()));
        checkTrue("ZPL 探测 = ~HS", Arrays.equals(LabelProtocols.probeZpl(), "~HS\r\n".getBytes()));
    }

    // ---------- 4. TCP 9100 通道 loopback 实测 ----------

    static void testTcpLoopback() throws Exception {
        final byte[] zpl = LabelProtocols.zplTest("SDK冒烟", "TCP9100");
        final byte[] reply = {0x1b, 0x40, 0x00, 0x11};   // 模拟打印机状态回包
        final ServerSocket server = new ServerSocket(0);  // 随机空闲端口
        final CountDownLatch rxLatch = new CountDownLatch(1);
        final ByteArrayOutputStream serverGot = new ByteArrayOutputStream();
        final Throwable[] serverErr = new Throwable[1];

        Thread serverThread = new Thread(new Runnable() {
            @Override public void run() {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[1024];
                    while (serverGot.size() < zpl.length) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        serverGot.write(buf, 0, n);
                    }
                    OutputStream out = s.getOutputStream();
                    out.write(reply);
                    out.flush();
                } catch (Throwable t) {
                    serverErr[0] = t;
                }
            }
        }, "smoke-server");
        serverThread.start();

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final ByteArrayOutputStream clientGot = new ByteArrayOutputStream();
        final int port = server.getLocalPort();
        TcpPrinterConnection tcp = new TcpPrinterConnection(
                new TcpPrinterConnection.Listener() {
                    @Override public void onLog(String msg) { System.out.println("  " + msg); }
                    @Override public void onState(int state) {
                        if (state == TcpPrinterConnection.STATE_READY) ready.countDown();
                    }
                    @Override public void onData(byte[] data) {
                        clientGot.write(data, 0, data.length);
                        dataLatch.countDown();
                    }
                });

        tcp.connect("127.0.0.1", port);
        checkTrue("TCP 连接就绪", ready.await(5, TimeUnit.SECONDS));
        tcp.sendData(zpl);
        checkTrue("TCP 收到状态回包", dataLatch.await(5, TimeUnit.SECONDS));
        serverThread.join(5000);
        server.close();

        checkTrue("服务器完整收到 ZPL 指令",
                Arrays.equals(serverGot.toByteArray(), zpl));
        checkTrue("客户端完整收到回包",
                Arrays.equals(clientGot.toByteArray(), reply));
        checkTrue("服务器无线程异常", serverErr[0] == null);
        tcp.disconnect();
        checkEq("断开后状态复位", String.valueOf(TcpPrinterConnection.STATE_DISCONNECTED),
                String.valueOf(tcp.getState()));
    }

    // ---------- 断言工具 ----------

    static void checkTrue(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    static void checkEq(String name, String expect, String actual) {
        boolean ok = expect.equals(actual);
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + " 期望=" + expect + " 实际=" + actual); }
    }
}
