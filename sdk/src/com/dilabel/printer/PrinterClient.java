package com.dilabel.printer;

import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.content.Context;

/**
 * 统一打印机客户端门面 —— 整合 SDK 的对外入口。
 *
 * 调研结论（主流打印机SDK连接方式调研.md §五）：各品牌 SDK 的门面层都是同一形态——
 * 一个 Printer 对象 + 统一回调 + sendData/disconnect，底层通道（BLE/SPP/TCP/USB）
 * 由连接参数决定。本类按同一思路整合四通道：
 *
 *   PrinterClient client = new PrinterClient(context, listener);
 *   client.connectBle(device);        // BLE GATT（佳博/汉印/芯烨/Zebra 等）
 *   client.connectSpp(device);        // 经典蓝牙 SPP（TSC/Honeywell/得力等台式机）
 *   client.connectUsb(usbDevice);     // USB OTG
 *   client.connectTcp("192.168.1.100", TcpPrinterConnection.DEFAULT_PORT); // WiFi 9100
 *   client.sendData(LabelProtocols.tsplTest(...));  // 指令族与通道无关
 *   client.disconnect();
 *
 * 状态与回包统一走 Listener（onLog/onState/onData），状态码复用
 * BlePrinterConnection.STATE_*。同一时刻只有一条活动通道，切换前先 disconnect。
 */
public class PrinterClient implements BlePrinterConnection.Listener,
        ClassicSppConnection.Listener, UsbPrinterConnection.Listener,
        TcpPrinterConnection.Listener {

    public interface Listener {
        void onLog(String msg);
        void onState(int state);           // BlePrinterConnection.STATE_*
        void onData(byte[] data);
    }

    /** 活动通道类型（连接成功后可用 channel() 查询） */
    public enum Channel { BLE, SPP, USB, TCP, NONE }

    private final Context context;
    private final Listener listener;
    private BlePrinterConnection ble;
    private ClassicSppConnection spp;
    private UsbPrinterConnection usb;
    private TcpPrinterConnection tcp;
    private volatile int sppState = BlePrinterConnection.STATE_DISCONNECTED; // SPP 无状态查询，回调维护
    private Channel active = Channel.NONE;
    private VendorDB.Profile profile;   // 当前厂商配置（BLE 连接用）

    public PrinterClient(Context ctx, Listener l) {
        this.context = ctx;
        this.listener = l;
    }

    /** 预设厂商配置（VendorDB.matchByName 的结果），BLE 连接前设置 */
    public void setProfile(VendorDB.Profile p) { this.profile = p; }

    /** 当前活动通道 */
    public Channel channel() { return active; }

    /** 当前连接状态（BlePrinterConnection.STATE_*，四通道统一语义） */
    public int getState() {
        switch (active) {
            case BLE: return ble.getState();
            case SPP: return sppState;
            case USB: return usb.getState();
            case TCP: return tcp.getState();
            default:  return BlePrinterConnection.STATE_DISCONNECTED;
        }
    }

    // ---- 四通道连接入口 ----

    public void connectBle(BluetoothDevice device) {
        lazyBle().setProfile(profile);
        active = Channel.BLE;
        ble.connect(device);
    }

    public void connectSpp(BluetoothDevice device) {
        lazySpp();
        active = Channel.SPP;
        spp.connect(device);
    }

    public void connectUsb(UsbDevice device) {
        lazyUsb();
        active = Channel.USB;
        usb.connect(device);
    }

    /** USB 权限广播需在界面 onCreate 时注册（Android 14+ 要求显式导出标志） */
    public void registerUsb() { lazyUsb().register(); }

    public void unregisterUsb() { if (usb != null) usb.unregister(); }

    public void connectTcp(String host, int port) {
        lazyTcp();
        active = Channel.TCP;
        tcp.connect(host, port);
    }

    /** 分片/流控由各通道内部处理（BLE 20 字节分片，其余整段直发） */
    public void sendData(byte[] data) {
        switch (active) {
            case BLE: ble.sendData(data); break;
            case SPP: spp.sendData(data); break;
            case USB: usb.sendData(data); break;
            case TCP: tcp.sendData(data); break;
            default:
                log("无活动通道, 丢弃 " + data.length + " 字节");
        }
    }

    public void disconnect() {
        if (ble != null) ble.disconnect();
        if (spp != null) spp.disconnect();
        if (usb != null) usb.disconnect();
        if (tcp != null) tcp.disconnect();
        active = Channel.NONE;
    }

    public void close() {
        disconnect();
        unregisterUsb();
    }

    // ---- 懒加载各通道（避免未用通道也持有资源） ----

    private BlePrinterConnection lazyBle() {
        if (ble == null) ble = new BlePrinterConnection(context, this);
        return ble;
    }

    private ClassicSppConnection lazySpp() {
        if (spp == null) spp = new ClassicSppConnection(context, this);
        return spp;
    }

    private UsbPrinterConnection lazyUsb() {
        if (usb == null) usb = new UsbPrinterConnection(context, this);
        return usb;
    }

    private TcpPrinterConnection lazyTcp() {
        if (tcp == null) tcp = new TcpPrinterConnection(this);
        return tcp;
    }

    // ---- 各通道 Listener 统一转发（[BLE]/[SPP]/[USB]/[TCP] 前缀由各通道自带） ----

    @Override public void onLog(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    @Override public void onState(int state) {
        if (listener != null) listener.onState(state);
    }

    @Override public void onData(byte[] data) {
        if (listener != null) listener.onData(data);
    }

    @Override public void onConnected(boolean ok) {
        // SPP 通道回调：映射到统一状态码
        sppState = ok ? BlePrinterConnection.STATE_READY
                : BlePrinterConnection.STATE_DISCONNECTED;
        if (listener != null) listener.onState(sppState);
    }

    private void log(String msg) {
        if (listener != null) listener.onLog("[SDK] " + msg);
    }
}
