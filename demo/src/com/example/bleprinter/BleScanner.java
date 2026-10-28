package com.example.bleprinter;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BLE 扫描 + 厂商识别（对应 Dlabel "搜索打印机" 的流程）：
 *
 * 关键点：不能用 deviceName 的 ScanFilter 做前缀匹配（系统只支持全名匹配），
 * 多厂家识别的正确做法是「无过滤扫描 + 回调里按前缀表分类」，
 * 这样一家厂商的任何型号前缀变化都不会漏扫。
 */
public class BleScanner {

    public interface Callback {
        void onDeviceFound(PrinterDevice device);
        void onScanFinished();
    }

    /** 扫描结果设备（含识别出的厂商） */
    public static class PrinterDevice {
        public final BluetoothDevice device;
        public final int rssi;
        public final VendorDB.Profile profile;   // null = 未识别厂商

        PrinterDevice(BluetoothDevice d, int rssi, VendorDB.Profile p) {
            this.device = d;
            this.rssi = rssi;
            this.profile = p;
        }

        public String displayName() {
            String name = device.getName();
            return (name == null || name.isEmpty()) ? device.getAddress() : name;
        }
    }

    private final Context context;
    private final Callback callback;
    private BluetoothAdapter adapter;
    private boolean scanning;
    private final Map<String, PrinterDevice> found = new HashMap<>();

    public BleScanner(Context ctx, Callback cb) {
        this.context = ctx;
        this.callback = cb;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    public boolean isScanning() { return scanning; }

    public List<PrinterDevice> results() {
        List<PrinterDevice> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<PrinterDevice>() {
            @Override
            public int compare(PrinterDevice a, PrinterDevice b) {
                int pa = a.profile == null ? 0 : 1;
                int pb = b.profile == null ? 0 : 1;
                if (pa != pb) return pb - pa;       // 已识别厂商的排前面
                return b.rssi - a.rssi;             // 再按信号强度
            }
        });
        return list;
    }

    public void start() {
        if (adapter == null || !adapter.isEnabled()) {
            if (callback != null) callback.onScanFinished();
            return;
        }
        found.clear();
        // Android 12+ 用 BLUETOOTH_SCAN 权限；旧系统需要定位权限
        if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN);
        }
        scanning = true;
        // 不加 ScanFilter：前缀无法过滤，回调里分类
        adapter.getBluetoothLeScanner().startScan(null,
                new android.bluetooth.le.ScanSettings.Builder()
                        .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                        .build(),
                scanCallback);
    }

    public void stop() {
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        }
        scanning = false;
        if (callback != null) callback.onScanFinished();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = d.getName();
            if (name == null || name.isEmpty()) return;   // 无名设备跳过
            String key = d.getAddress();
            if (found.containsKey(key)) return;
            VendorDB.Profile p = VendorDB.matchByName(name);
            // 过滤明显不是打印机的设备（手机/手表等常见名）
            if (p == null && !looksLikePrinter(name)) return;
            PrinterDevice pd = new PrinterDevice(d, result.getRssi(), p);
            found.put(key, pd);
            if (callback != null) callback.onDeviceFound(pd);
        }
    };

    /** 常见打印机蓝牙名称关键词（白牌机命名非常杂，故用宽匹配） */
    static boolean looksLikePrinter(String name) {
        String lower = name.toLowerCase();
        return lower.contains("print") || lower.contains("printer") || lower.startsWith("m")
                || lower.startsWith("d") || lower.startsWith("k") || lower.startsWith("qr")
                || lower.contains("label") || lower.contains("pos") || lower.startsWith("hm")
                || lower.startsWith("b21") || lower.startsWith("b1") || lower.startsWith("d11")
                || lower.startsWith("d21") || lower.startsWith("d31");
    }
}
