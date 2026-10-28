package com.example.bleprinter;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 多厂家蓝牙打印机连接测试 Demo 主界面。
 *
 * 复刻 Dlabel 云标签的连接流程：
 *   搜索(BLE 无过滤扫描) -> 名称前缀识别厂商 -> GATT 连接(厂商 UUID 表 + 自动降级)
 *   -> 指令适配测试(探测打印机协议族) -> 分协议族发送测试标签
 */
public class MainActivity extends Activity implements BleScanner.Callback,
        BlePrinterConnection.Listener {

    private BleScanner scanner;
    private BlePrinterConnection connection;
    private ClassicSppConnection spp;
    private final Handler main = new Handler(Looper.getMainLooper());

    private DeviceAdapter listAdapter;
    private TextView statusText;
    private TextView logText;
    private Spinner protocolSpinner;
    private BleScanner.PrinterDevice selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        scanner = new BleScanner(this, this);
        connection = new BlePrinterConnection(this, this);
        spp = new ClassicSppConnection(this, new ClassicSppConnection.Listener() {
            @Override
            public void onLog(String msg) { log(msg); }

            @Override
            public void onConnected(boolean ok) {
                log(ok ? "[SPP] 已连接" : "[SPP] 连接失败");
            }

            @Override
            public void onData(byte[] data) { log("[SPP] 收到 " + data.length + " 字节"); }
        });
        requestBtPermissions();
    }

    // ---------- UI（纯代码构建，零第三方依赖） ----------

    private void buildUi() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        statusText = new TextView(this);
        statusText.setText("状态: 未连接");
        root.addView(statusText);

        Button scanBtn = button(root, "搜索打印机 (BLE 扫描)");
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanner.isScanning()) scanner.stop();
                else {
                    appendLog("-- 开始扫描 --");
                    scanner.start();
                    main.postDelayed(new Runnable() {
                        @Override public void run() { if (scanner.isScanning()) scanner.stop(); }
                    }, 8000);
                }
            }
        });

        Button bondedBtn = button(root, "列出已配对设备(经典蓝牙 SPP)");
        bondedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad == null) return;
                Set<BluetoothDevice> set = ad.getBondedDevices();
                appendLog("-- 已配对 " + set.size() + " 台 --");
                for (BluetoothDevice d : set) {
                    VendorDB.Profile p = VendorDB.matchByName(d.getName());
                    appendLog(d.getName() + " | " + d.getAddress()
                            + (p == null ? "" : " -> " + p.brand + "/" + p.protocol));
                }
            }
        });

        ListView list = new ListView(this);
        listAdapter = new DeviceAdapter();
        list.setAdapter(listAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                selected = listAdapter.getItem(pos);
                appendLog("选中: " + selected.displayName());
                statusText.setText("状态: 已选 " + selected.displayName()
                        + (selected.profile == null ? " (未知厂商)" : " (" + selected.profile.brand + ")"));
            }
        });
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(list, lp);

        protocolSpinner = new Spinner(this);
        ArrayAdapter<String> sp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"AUTO(按厂商表)", "ESC/POS", "TSPL", "CPCL"});
        sp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolSpinner.setAdapter(sp);
        root.addView(protocolSpinner);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        Button connBtn = smallButton(row, "连接");
        connBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doConnect(); }
        });
        Button testBtn = smallButton(row, "适配测试");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doAdaptTest(); }
        });
        Button printBtn = smallButton(row, "打印测试标签");
        printBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPrint(); }
        });
        Button discBtn = smallButton(row, "断开");
        discBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connection.disconnect();
                spp.disconnect();
            }
        });
        root.addView(row);

        ScrollView sv = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextIsSelectable(true);
        logText.setTextSize(11f);
        sv.addView(logText);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, slp);

        setContentView(root);
    }

    private Button button(ViewGroup parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        parent.addView(b);
        return b;
    }

    private Button smallButton(ViewGroup parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        parent.addView(b);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------- 动作 ----------

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    private void doConnect() {
        if (selected == null) {
            toast("请先在列表中选择设备");
            return;
        }
        if (selected.profile != null) connection.setProfile(selected.profile);
        connection.connect(selected.device);
    }

    /** 对应 Dlabel "打印机适配测试"：发探测指令看是否有回包，判断通道与协议族 */
    private void doAdaptTest() {
        if (connection.getState() != BlePrinterConnection.STATE_READY) {
            toast("请先连接(通道就绪后测试)");
            return;
        }
        log("-- 适配测试: 发送状态探测指令 --");
        connection.sendData(LabelProtocols.probeCommand());
        toast("探测指令已发送, 观察日志回包");
    }

    private void doPrint() {
        if (connection.getState() != BlePrinterConnection.STATE_READY) {
            toast("请先连接");
            return;
        }
        String title = "多厂家打印测试";
        String body = "DiLabel 技术验证 Demo";
        int which = protocolSpinner.getSelectedItemPosition();
        VendorDB.Protocol proto;
        byte[] data;
        switch (which) {
            case 1:
                proto = VendorDB.Protocol.ESC_POS;
                data = LabelProtocols.escPosTest(title, body);
                break;
            case 2:
                proto = VendorDB.Protocol.TSPL;
                data = LabelProtocols.tsplTest(title, body);
                break;
            case 3:
                proto = VendorDB.Protocol.CPCL;
                data = LabelProtocols.cpclTest(title, body);
                break;
            default:
                proto = selected != null && selected.profile != null
                        ? selected.profile.protocol : VendorDB.Protocol.ESC_POS;
                data = buildAuto(proto, title, body);
                break;
        }
        log("-- 发送 " + proto + " 指令 " + data.length + " 字节 --");
        connection.sendData(data);
    }

    private byte[] buildAuto(VendorDB.Protocol p, String title, String body) {
        switch (p) {
            case TSPL: return LabelProtocols.tsplTest(title, body);
            case CPCL: return LabelProtocols.cpclTest(title, body);
            default: return LabelProtocols.escPosTest(title, body);
        }
    }

    // ---------- 回调 ----------

    @Override
    public void onDeviceFound(final BleScanner.PrinterDevice d) {
        main.post(new Runnable() {
            @Override public void run() {
                listAdapter.add(d);
                log("发现: " + d.displayName() + " rssi=" + d.rssi
                        + (d.profile == null ? "" : " -> " + d.profile.brand));
            }
        });
    }

    @Override
    public void onScanFinished() {
        main.post(new Runnable() {
            @Override public void run() { log("-- 扫描结束, 共 " + listAdapter.getCount() + " 台 --"); }
        });
    }

    @Override
    public void onLog(final String msg) {
        main.post(new Runnable() {
            @Override public void run() { log(msg); }
        });
    }

    @Override
    public void onData(final byte[] data) {
        main.post(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder("[BLE 回包] ");
                for (int i = 0; i < Math.min(data.length, 32); i++) {
                    sb.append(String.format("%02X ", data[i]));
                }
                log(sb.toString() + (data.length > 32 ? "..." : "")
                        + " (" + data.length + "字节)");
            }
        });
    }

    @Override
    public void onState(final int state) {
        main.post(new Runnable() {
            @Override public void run() {
                String s;
                switch (state) {
                    case BlePrinterConnection.STATE_CONNECTING: s = "连接中"; break;
                    case BlePrinterConnection.STATE_CONNECTED: s = "已连接(发现服务中)"; break;
                    case BlePrinterConnection.STATE_READY: s = "就绪(可打印)"; break;
                    default: s = "未连接"; break;
                }
                statusText.setText("状态: " + s);
            }
        });
    }

    private void log(String s) {
        appendLog(s);
    }

    private void appendLog(String s) {
        logText.append(s + "\n");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 设备列表适配器 */
    private class DeviceAdapter extends BaseAdapter {
        private final List<BleScanner.PrinterDevice> items = new ArrayList<>();

        void add(BleScanner.PrinterDevice d) {
            for (BleScanner.PrinterDevice e : items) {
                if (e.device.getAddress().equals(d.device.getAddress())) return;
            }
            items.add(d);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public BleScanner.PrinterDevice getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView v = (TextView) (convertView == null
                    ? new TextView(MainActivity.this) : convertView);
            v.setPadding(dp(4), dp(4), dp(4), dp(4));
            BleScanner.PrinterDevice d = items.get(position);
            v.setText(d.displayName() + "  " + d.device.getAddress() + "  " + d.rssi + "dBm"
                    + (d.profile == null ? "" : "  [" + d.profile.brand + "]"));
            return v;
        }
    }
}
