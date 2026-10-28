package com.example.bleprinter;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * USB (OTG) 打印机通道。
 *
 * 依据：Dlabel APK 的 AndroidManifest 声明了 android.hardware.usb.host / usb.accessory，
 * 即 Dlabel 除蓝牙双通道（BLE/SPP，见 ClassicSppConnection）外还支持 USB 连接打印机。
 *
 * USB 是流式通道（同 SPP），没有 BLE 的 MTU 分片问题：bulkTransfer 直接写整段指令。
 * 打印机在 USB 上的形态通常是「打印机类设备(class 7)」或「厂商自定义设备但含 bulk OUT 端点」，
 * 后者是国产热敏机更常见的形态，因此枚举时两者都收。
 */
public class UsbPrinterConnection {

    public interface Listener {
        void onLog(String msg);
        void onState(int state);   // 复用 BlePrinterConnection.STATE_*
    }

    public static final String ACTION_USB_PERMISSION =
            "com.example.bleprinter.USB_PERMISSION";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private UsbDeviceConnection conn;
    private UsbInterface claimed;
    private UsbEndpoint outEndpoint;
    private int state = BlePrinterConnection.STATE_DISCONNECTED;
    private boolean registered;

    public UsbPrinterConnection(Context ctx, Listener l) {
        this.context = ctx;
        this.listener = l;
    }

    public int getState() { return state; }

    private UsbManager usbManager() {
        return (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    /** 枚举可打印的 USB 设备：打印机类(class 7)或任意含 bulk OUT 端点的设备 */
    public List<UsbDevice> listPrinters() {
        List<UsbDevice> out = new ArrayList<>();
        UsbManager m = usbManager();
        if (m == null) return out;
        for (UsbDevice d : m.getDeviceList().values()) {
            if (d.getDeviceClass() == 7 || findBulkOut(d, null) != null) out.add(d);
        }
        return out;
    }

    public boolean hasPermission(UsbDevice d) {
        UsbManager m = usbManager();
        return m != null && m.hasPermission(d);
    }

    /** 连接：有权限直接打开，否则弹系统 USB 权限框（回调里继续） */
    public void connect(UsbDevice d) {
        if (hasPermission(d)) {
            open(d);
        } else {
            log("[USB] 请求 USB 权限...");
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()),
                    (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            UsbManager m = usbManager();
            if (m != null) m.requestPermission(d, pi);
        }
    }

    /** 注册 USB 权限广播（onCreate 调用一次；Android 14+ 需显式声明导出标志） */
    public void register() {
        if (registered) return;
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, f);
        }
        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        try { context.unregisterReceiver(receiver); } catch (Exception ignore) { }
        registered = false;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (!ACTION_USB_PERMISSION.equals(i.getAction()) || d == null) return;
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                open(d);
            } else {
                log("[USB] 用户拒绝 USB 权限: " + d.getDeviceName());
            }
        }
    };

    /** 打开设备：找 bulk OUT 端点所在 interface 并 claim */
    private void open(UsbDevice d) {
        close();
        log("[USB] 打开 vid=0x" + Integer.toHexString(d.getVendorId())
                + " pid=0x" + Integer.toHexString(d.getProductId())
                + " " + d.getDeviceName());
        UsbManager m = usbManager();
        conn = m == null ? null : m.openDevice(d);
        if (conn == null) {
            log("[USB] openDevice 失败");
            return;
        }
        UsbInterface[] holder = new UsbInterface[1];
        UsbEndpoint ep = findBulkOut(d, holder);
        if (ep == null) {
            log("[USB] 未找到 bulk OUT 端点");
            return;
        }
        claimed = holder[0];
        if (!conn.claimInterface(claimed, true)) {
            log("[USB] claimInterface 失败");
            return;
        }
        outEndpoint = ep;
        log("[USB] 通道就绪: interface#" + claimed.getId()
                + " ep=0x" + Integer.toHexString(ep.getAddress()));
        setState(BlePrinterConnection.STATE_READY);
    }

    /** 流式发送整段指令（USB bulk 无 MTU 分片） */
    public void sendData(final byte[] data) {
        final UsbDeviceConnection c = conn;
        final UsbEndpoint ep = outEndpoint;
        if (c == null || ep == null) {
            log("[USB] 通道未就绪, 丢弃 " + data.length + " 字节");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                int n = c.bulkTransfer(ep, data, data.length, 5000);
                final boolean ok = n >= 0;
                main.post(new Runnable() {
                    @Override public void run() {
                        log(ok ? "[USB] 已发送 " + n + " 字节"
                                : "[USB] bulkTransfer 写失败");
                    }
                });
            }
        }, "usb-tx").start();
    }

    public void disconnect() {
        close();
        setState(BlePrinterConnection.STATE_DISCONNECTED);
        log("[USB] 已断开");
    }

    private void close() {
        if (conn != null) {
            try {
                if (claimed != null) conn.releaseInterface(claimed);
                conn.close();
            } catch (Exception ignore) { }
        }
        conn = null;
        claimed = null;
        outEndpoint = null;
    }

    /** 在所有 interface 中找第一个 bulk OUT 端点，holder[0] 带回其所在 interface */
    private static UsbEndpoint findBulkOut(UsbDevice d, UsbInterface[] holder) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    if (holder != null) holder[0] = itf;
                    return ep;
                }
            }
        }
        return null;
    }

    private void setState(int s) {
        state = s;
        if (listener != null) listener.onState(s);
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }
}
