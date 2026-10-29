package com.example.bleprinter;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/**
 * BLE GATT 打印机连接（对应厂商 SDK 中 PrinterBLE / BTPrinter 的核心流程）：
 *
 * connect -> discoverServices -> 按 VendorDB 候选表选 service/write/notify
 *          -> 找不到则自动降级：枚举所有 service，取任意可写特征 + notify 特征
 *          -> requestMtu(247) -> 打开 CCCD 通知 -> 队列分片写入
 *
 * 关键点（各厂商 SDK 通用做法）：
 *  - 写入必须排队：onCharacteristicWrite 回调成功后再发下一包
 *  - MTU 不足 20 字节时按 20 字节分片（BLE 4.0 默认 ATT_MTU=23）
 *  - notify 特征写 CCCD(0x2902) = 0x0001 打开通知，用于接收打印机回包/状态
 */
public class BlePrinterConnection {

    public interface Listener {
        void onLog(String msg);
        void onState(int state);           // STATE_*
        void onData(byte[] data);          // 打印机 notify 回包
    }

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_READY = 3;   // 服务发现完成，可发送

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private int mtu = 20;
    private int state = STATE_DISCONNECTED;
    private BluetoothDevice pendingDevice;
    private int retriesLeft = 2;   // status=133 自动重试次数（业界标准做法）
    private android.content.BroadcastReceiver bondReceiver;   // 配对状态广播

    private final Queue<byte[]> sendQueue = new ArrayDeque<>();
    private boolean writing = false;
    // 真机踩坑（HPRT T200）：按 MTU 240 发 237 字节大包固件缓冲不足会整包丢弃，
    // 固定 20 字节分片 + 小间隔是所有机型的最大公约数（对应 Dlabel 的分片流控参数）
    private static final int CHUNK = 20;
    private static final long PACKET_INTERVAL_MS = 15;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private VendorDB.Profile profile;

    public BlePrinterConnection(Context ctx, Listener l) {
        this.context = ctx;
        this.listener = l;
    }

    public int getState() { return state; }

    public void setProfile(VendorDB.Profile p) { this.profile = p; }

    public void connect(BluetoothDevice device) {
        pendingDevice = device;
        retriesLeft = 2;
        log("连接: " + device.getName() + " [" + device.getAddress() + "]");
        setState(STATE_CONNECTING);
        // 真机踩坑（HPRT T200）：未 bond 的设备 GATT 写入"成功"但固件忽略数据，
        // 必须先系统配对（createBond 为公开 API），配对完成后广播回调里继续连接
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            log("设备未配对, 发起系统配对（如弹窗请在手机上确认）...");
            registerBondReceiver();
            if (!device.createBond()) {
                log("createBond 返回 false");
                setState(STATE_DISCONNECTED);
            }
            return;
        }
        connectGatt(device);
    }

    private void registerBondReceiver() {
        if (bondReceiver != null) return;
        bondReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (d == null || pendingDevice == null
                        || !d.getAddress().equals(pendingDevice.getAddress())) return;
                if (state == BluetoothDevice.BOND_BONDED) {
                    log("配对成功, 继续连接...");
                    unregisterBondReceiver();
                    setState(STATE_CONNECTING);
                    connectGatt(pendingDevice);
                } else if (state == BluetoothDevice.BOND_NONE) {
                    log("配对失败/被取消");
                    unregisterBondReceiver();
                    setState(STATE_DISCONNECTED);
                }
            }
        };
        android.content.IntentFilter f =
                new android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, f);
        }
    }

    private void unregisterBondReceiver() {
        if (bondReceiver == null) return;
        try { context.unregisterReceiver(bondReceiver); } catch (Exception ignore) { }
        bondReceiver = null;
    }

    private void connectGatt(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(context, false, callback);
        }
    }

    public void disconnect() {
        unregisterBondReceiver();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        setState(STATE_DISCONNECTED);
        log("已断开");
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT 已连接, 开始服务发现...");
                setState(STATE_CONNECTED);
                g.discoverServices();          // 必须等这个回调后才能读写
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT 断开 status=" + status);
                // 133 = GATT_ERROR：弱信号/资源紧张时高发，标准做法是关旧句柄后自动重试
                if (status == 133 && retriesLeft > 0 && pendingDevice != null) {
                    retriesLeft--;
                    try { g.close(); } catch (Exception ignore) { }
                    gatt = null;
                    writing = false;
                    synchronized (sendQueue) { sendQueue.clear(); }
                    log("status=133, 自动重试(剩余 " + (retriesLeft + 1) + " 次)...");
                    setState(STATE_CONNECTING);
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (pendingDevice != null) connectGatt(pendingDevice);
                                }
                            }, 400);
                    return;   // 不落回未连接态，保持连接中
                }
                setState(STATE_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("服务发现失败 status=" + status);
                return;
            }
            StringBuilder sb = new StringBuilder("服务列表:\n");
            for (BluetoothGattService s : g.getServices()) {
                sb.append("  ").append(s.getUuid()).append('\n');
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    sb.append("    ").append(c.getUuid())
                      .append(" prop=0x").append(Integer.toHexString(c.getProperties())).append('\n');
                }
            }
            log(sb.toString());
            pickCharacteristics(g);
            if (writeChar == null) {
                log("未找到可写特征, 无法打印");
                return;
            }
            if (Build.VERSION.SDK_INT >= 21) {
                log("请求 MTU=247");
                g.requestMtu(247);             // onMtuChanged 里继续
            } else {
                enableNotify(g);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtuSize, int status) {
            log("MTU=" + mtuSize + " status=" + status);
            mtu = Math.max(20, mtuSize - 3);   // ATT 有效负载 = MTU - 3
            enableNotify(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            log("通知已开启, 通道就绪");
            setState(STATE_READY);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writing = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("写入失败 status=" + status);
            }
            // 一包写完再隔小间隔发下一包（流控 + 给固件处理时间）
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { flushQueue(); }
            }, PACKET_INTERVAL_MS);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (listener != null) listener.onData(c.getValue());
        }
    };

    /** 先按厂商候选表挑特征，失败则自动降级枚举 */
    private void pickCharacteristics(BluetoothGatt g) {
        if (profile != null) {
            outer:
            for (String su : profile.serviceUuids) {
                BluetoothGattService s = g.getService(UUID.fromString(su));
                if (s == null) continue;
                for (String wu : profile.writeUuids) {
                    BluetoothGattCharacteristic c = s.getCharacteristic(UUID.fromString(wu));
                    if (c != null && (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        writeChar = c;
                        log("命中厂商配置: " + su + " / " + wu + " (" + profile.brand + ")");
                        break outer;
                    }
                }
            }
            if (writeChar != null) {
                for (String nu : profile.notifyUuids) {
                    for (BluetoothGattService s : g.getServices()) {
                        BluetoothGattCharacteristic c = s.getCharacteristic(UUID.fromString(nu));
                        if (c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            notifyChar = c;
                            return;
                        }
                    }
                }
            }
        }
        // ---- 自动降级: 枚举全部服务 ----
        // 真机踩坑（HPRT T200）：标准 GATT 特征 2A00(Device Name) 标了可写，
        // 先枚举到它会导致指令写进设备名通道；必须跳过 SIG 标准特征(0x2A00~0x2AFF)，
        // 且优先 WRITE_NO_RESPONSE（打印数据通道的典型形态）
        if (writeChar == null) {
            log("降级: 自动枚举特征");
            BluetoothGattCharacteristic wrNoRsp = null, wrRsp = null;
            for (BluetoothGattService svc : g.getServices()) {
                for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    int p = c.getProperties();
                    boolean sig = isSigCharacteristic(c.getUuid());
                    if (!sig) {
                        if (wrNoRsp == null && (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            wrNoRsp = c;
                        }
                        if (wrRsp == null && (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                            wrRsp = c;
                        }
                        if (notifyChar == null && (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            notifyChar = c;
                        }
                    }
                }
            }
            writeChar = wrRsp != null ? wrRsp : wrNoRsp;   // 带响应优先：有真实流控
            if (writeChar != null) log("自动选择写特征: " + writeChar.getUuid());
        }
        if (notifyChar == null) {
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        notifyChar = c;
                        break;
                    }
                }
            }
        }
    }

    /** 蓝牙 SIG 标准特征（0x2A00~0x2AFF，如 Device Name/Appearance）：GATT 定义数据，不是打印通道 */
    private static boolean isSigCharacteristic(UUID u) {
        String str = u.toString().toLowerCase();
        if (!str.endsWith("-0000-1000-8000-00805f9b34fb")) return false;
        try {
            int shortId = Integer.parseInt(str.substring(4, 8), 16);
            return shortId >= 0x2a00 && shortId <= 0x2aff;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void enableNotify(BluetoothGatt g) {
        if (notifyChar == null) {
            log("无通知特征(单向打印机), 直接就绪");
            setState(STATE_READY);
            return;
        }
        g.setCharacteristicNotification(notifyChar, true);
        BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
        if (d != null) {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        } else {
            setState(STATE_READY);
        }
    }

    /** 分片入队发送（线程安全） */
    public void sendData(byte[] data) {
        if (state != STATE_READY || writeChar == null) {
            log("通道未就绪, 丢弃 " + data.length + " 字节");
            return;
        }
        int chunk = CHUNK;   // 固定 20 字节，兼容小缓冲固件
        for (int i = 0; i < data.length; i += chunk) {
            int end = Math.min(data.length, i + chunk);
            byte[] part = new byte[end - i];
            System.arraycopy(data, i, part, 0, part.length);
            synchronized (sendQueue) { sendQueue.add(part); }
        }
        flushQueue();
    }

    private void flushQueue() {
        if (writing || gatt == null) return;
        byte[] part;
        synchronized (sendQueue) { part = sendQueue.poll(); }
        if (part == null) return;
        writing = true;
        writeChar.setValue(part);
        writeChar.setWriteType((writeChar.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        if (!gatt.writeCharacteristic(writeChar)) {
            writing = false;
            log("writeCharacteristic 返回 false");
        }
    }

    private void setState(int s) {
        state = s;
        if (listener != null) listener.onState(s);
    }

    private void log(String msg) {
        if (listener != null) listener.onLog("[BLE] " + msg);
    }
}
