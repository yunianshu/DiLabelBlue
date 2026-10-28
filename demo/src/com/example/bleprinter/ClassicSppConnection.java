package com.example.bleprinter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 经典蓝牙 SPP 通道（对应 Dlabel 的「蓝牙模式切换」：BLE 找不到时切经典蓝牙）。
 * 台式标签机/部分小票机只提供 SPP (RFCOMM) 通道。
 */
public class ClassicSppConnection {

    public interface Listener {
        void onLog(String msg);
        void onConnected(boolean ok);
        void onData(byte[] data);
    }

    private static final UUID SPP = UUID.fromString(VendorDB.SPP_UUID);

    private final Context context;
    private final Listener listener;
    private BluetoothSocket socket;
    private Thread rxThread;
    private OutputStream tx;

    public ClassicSppConnection(Context ctx, Listener l) {
        this.context = ctx;
        this.listener = l;
    }

    /** 必须是已配对(bonded)设备；SPP 无需配对弹窗流程 */
    public void connect(final BluetoothDevice device) {
        disconnect();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    log("[SPP] 连接 " + device.getName());
                    socket = device.createRfcommSocketToServiceRecord(SPP);
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    adapter.cancelDiscovery();   // 连接前必须停扫描
                    socket.connect();
                    tx = socket.getOutputStream();
                    listener.onConnected(true);
                    startRx(socket.getInputStream());
                } catch (Exception e) {
                    log("[SPP] 失败: " + e.getMessage());
                    listener.onConnected(false);
                }
            }
        }, "spp-conn").start();
    }

    public void sendData(final byte[] data) {
        if (tx == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    tx.write(data);
                    tx.flush();
                } catch (IOException e) {
                    log("[SPP] 写失败: " + e.getMessage());
                }
            }
        }).start();
    }

    public void disconnect() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignore) { }
        socket = null;
        tx = null;
        if (rxThread != null) rxThread.interrupt();
    }

    private void startRx(final InputStream in) {
        rxThread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[512];
                try {
                    while (!Thread.currentThread().isInterrupted() && socket != null) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        byte[] out = new byte[n];
                        System.arraycopy(buf, 0, out, 0, n);
                        listener.onData(out);
                    }
                } catch (IOException ignore) { }
                listener.onConnected(false);
            }
        }, "spp-rx");
        rxThread.start();
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }
}
