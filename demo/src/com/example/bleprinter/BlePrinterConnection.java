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

    private final Queue<byte[]> sendQueue = new ArrayDeque<>();
    private boolean writing = false;
    private VendorDB.Profile profile;

    public BlePrinterConnection(Context ctx, Listener l) {
        this.context = ctx;
        this.listener = l;
    }

    public int getState() { return state; }

    public void setProfile(VendorDB.Profile p) { this.profile = p; }

    public void connect(BluetoothDevice device) {
        log("连接: " + device.getName() + " [" + device.getAddress() + "]");
        setState(STATE_CONNECTING);
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(context, false, callback);
        }
    }

    public void disconnect() {
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
            flushQueue();                      // 一包写完再发下一包（流控关键）
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
        if (writeChar == null) {
            log("降级: 自动枚举特征");
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    if (writeChar == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        writeChar = c;
                    }
                    if (notifyChar == null && (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        notifyChar = c;
                    }
                }
            }
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
        int chunk = Math.max(20, mtu);
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
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
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
