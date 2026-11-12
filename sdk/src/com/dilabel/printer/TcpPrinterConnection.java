package com.dilabel.printer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * WiFi / TCP RAW 打印通道（对应主流厂商 SDK 的 WiFi 连接方式）。
 *
 * 依据调研（各平台连接方式调研.md / 主流打印机SDK连接方式调研.md）：
 *  - Zebra、TSC、佳博、芯烨、Epson、Star 等台式机型标配 RAW 9100（JetDirect）端口，
 *    一条 TCP 连接直接写指令字节，无 BLE 的分片/流控问题；
 *  - 指令族（ESC/POS、TSPL、CPCL、ZPL）与 BLE/SPP/USB 完全相同（流式字节）；
 *  - 也是跨 OS 平台的最大公约数通道：Windows/Linux/服务器直接 Socket，
 *    iOS 走 Network.framework，Android 走普通 Socket（本实现）。
 *
 * 无 MTU 分片心智负担：整段指令一次 write。回包（探测查询/状态）由 RX 线程异步回调。
 */
public class TcpPrinterConnection {

    public interface Listener {
        void onLog(String msg);
        void onState(int state);           // STATE_*
        void onData(byte[] data);          // 打印机回包（查询指令的应答）
    }

    /** 状态码与 BlePrinterConnection.STATE_* 对齐（四通道统一语义） */
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_READY = 3;

    /** RAW/JetDirect 标准端口（Zebra/TSC/佳博/芯烨/Epson 台式系列通用） */
    public static final int DEFAULT_PORT = 9100;

    private final Listener listener;
    private Socket socket;
    private Thread rxThread;
    private volatile OutputStream tx;
    private volatile int state = STATE_DISCONNECTED;

    public TcpPrinterConnection(Listener l) {
        this.listener = l;
    }

    public int getState() { return state; }

    /** 连接打印机（host:port，port 传 0 用默认 9100）。异步执行，结果走 onState。 */
    public void connect(final String host, final int port) {
        disconnect();
        setState(STATE_CONNECTING);
        new Thread(new Runnable() {
            @Override public void run() {
                int p = port > 0 ? port : DEFAULT_PORT;
                try {
                    log("[TCP] 连接 " + host + ":" + p + " ...");
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(host, p), 5000);
                    s.setTcpNoDelay(true);   // 打印指令要立即发出，禁用 Nagle
                    socket = s;
                    tx = s.getOutputStream();
                    setState(STATE_READY);
                    log("[TCP] 通道就绪");
                    startRx(s);
                } catch (Exception e) {
                    log("[TCP] 失败: " + e.getMessage());
                    closeQuietly();
                    setState(STATE_DISCONNECTED);
                }
            }
        }, "tcp-conn").start();
    }

    /** 发送整段指令字节（TCP 流式通道，无需分片） */
    public void sendData(final byte[] data) {
        final OutputStream out = tx;
        if (out == null || state != STATE_READY) {
            log("[TCP] 通道未就绪, 丢弃 " + data.length + " 字节");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    out.write(data);
                    out.flush();
                    log("[TCP] 已发送 " + data.length + " 字节");
                } catch (IOException e) {
                    log("[TCP] 写失败: " + e.getMessage());
                }
            }
        }, "tcp-tx").start();
    }

    public void disconnect() {
        closeQuietly();
        if (rxThread != null) rxThread.interrupt();
        if (state != STATE_DISCONNECTED) {
            setState(STATE_DISCONNECTED);
            log("[TCP] 已断开");
        }
    }

    private void startRx(final Socket s) {
        rxThread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[1024];
                try {
                    InputStream in = s.getInputStream();
                    while (!Thread.currentThread().isInterrupted() && socket == s) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        byte[] out = new byte[n];
                        System.arraycopy(buf, 0, out, 0, n);
                        if (listener != null) listener.onData(out);
                    }
                } catch (IOException ignore) { }
                if (socket == s && listener != null) listener.onLog("[TCP] 连接关闭");
            }
        }, "tcp-rx");
        rxThread.start();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignore) { }
        socket = null;
        tx = null;
    }

    private void setState(int s) {
        state = s;
        if (listener != null) listener.onState(s);
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }
}
