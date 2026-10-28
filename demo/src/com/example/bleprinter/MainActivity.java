package com.example.bleprinter;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 多厂家打印机连接测试 Demo 主界面（暗色控制台风格，纯代码构建，零第三方依赖）。
 *
 * 通道模型对齐 Dlabel APK（见 分析报告 §2.2/§2.3）：
 *   - BLE GATT（默认，无过滤扫描 + 前缀识别 + UUID 候选表/自动降级）
 *   - 经典蓝牙 SPP（Dlabel「切换蓝牙模式」，FeasyCom SDK 同时导出 FscBleCentralApiImp/FscSppApiImp）
 *   - USB OTG（Dlabel Manifest 声明 android.hardware.usb.host）
 * 复刻流程：搜索 -> 名称前缀识别厂商 -> 连接 -> 指令适配测试 -> 分协议族发送测试标签
 */
public class MainActivity extends Activity implements BleScanner.Callback,
        BlePrinterConnection.Listener {

    // ---- 连接通道（对齐 Dlabel：BLE / 经典蓝牙 SPP / USB） ----
    private static final int CH_BLE = 0;
    private static final int CH_SPP = 1;
    private static final int CH_USB = 2;

    // ---- 配色（暗色控制台主题） ----
    private static final int C_BG_TOP = 0xFF0B1220;
    private static final int C_BG_MID = 0xFF0E1A33;
    private static final int C_BG_BOT = 0xFF142648;
    private static final int C_CARD = 0xFF15223E;
    private static final int C_CARD_STROKE = 0xFF24365A;
    private static final int C_CARD_SEL = 0xFF1B2E56;
    private static final int C_TEXT = 0xFFE6EDF7;
    private static final int C_SUB = 0xFF8CA0BF;
    private static final int C_FAINT = 0xFF5E7191;
    private static final int C_ACCENT = 0xFF22D3EE;
    private static final int C_INDIGO = 0xFF6366F1;
    private static final int C_GREEN = 0xFF34D399;
    private static final int C_AMBER = 0xFFFBBF24;
    private static final int C_RED = 0xFFF87171;
    private static final int C_BLUE = 0xFF60A5FA;

    private BleScanner scanner;
    private BlePrinterConnection connection;
    private ClassicSppConnection spp;
    private UsbPrinterConnection usb;
    private final Handler main = new Handler(Looper.getMainLooper());

    private DeviceAdapter listAdapter;
    private RadarView radar;
    private TextView statusText;
    private View statusDot;
    private GradientDrawable statusPillBg;
    private TextView scanBtn, countText, logText, emptyHint;
    private TextView chBle, chSpp, chUsb;
    private TextView chipAuto, chipEsc, chipTspl, chipCpcl;
    private TextView connBtn, testBtn, printBtn, discBtn;
    private ScrollView consoleScroll;
    private int channel = CH_BLE;
    private int protocolIndex = 0;
    private int st = BlePrinterConnection.STATE_DISCONNECTED;   // 三通道统一状态
    private DeviceItem selected;
    private ValueAnimator dotPulse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG_TOP);
        getWindow().setNavigationBarColor(C_BG_TOP);
        buildUi();
        scanner = new BleScanner(this, this);
        connection = new BlePrinterConnection(this, this);
        spp = new ClassicSppConnection(this, new ClassicSppConnection.Listener() {
            @Override
            public void onLog(String msg) {
                main.post(new Runnable() {   // SPP 回调来自工作线程，必须切回主线程更新 UI
                    @Override public void run() { log(msg); }
                });
            }

            @Override
            public void onConnected(final boolean ok) {
                main.post(new Runnable() {
                    @Override public void run() {
                        st = ok ? BlePrinterConnection.STATE_READY
                                : BlePrinterConnection.STATE_DISCONNECTED;
                        setPill(ok ? "SPP 已连接 · 可打印" : "未连接",
                                ok ? C_GREEN : C_FAINT, ok ? C_GREEN : C_FAINT);
                        refreshButtons();
                    }
                });
            }

            @Override
            public void onData(final byte[] data) {
                main.post(new Runnable() {
                    @Override public void run() {
                        log("[SPP] 收到 " + data.length + " 字节");
                    }
                });
            }
        });
        usb = new UsbPrinterConnection(this, new UsbPrinterConnection.Listener() {
            @Override
            public void onLog(String msg) { log(msg); }

            @Override
            public void onState(int s) {
                st = s;
                if (s == BlePrinterConnection.STATE_READY) {
                    setPill("USB 已连接 · 可打印", C_GREEN, C_GREEN);
                } else if (s == BlePrinterConnection.STATE_DISCONNECTED) {
                    setPill("未连接", C_FAINT, C_FAINT);
                }
                refreshButtons();
            }
        });
        usb.register();
        requestBtPermissions();
        refreshButtons();   // connection 就绪后刷新按钮初始禁用态
        appendLog("-- DiLabel 多厂家连接控制台就绪, 点雷达或扫描键开始 --");
    }

    @Override
    protected void onDestroy() {
        usb.unregister();
        super.onDestroy();
    }

    // ---------- UI（纯代码构建，零第三方依赖） ----------

    private void buildUi() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{C_BG_TOP, C_BG_MID, C_BG_BOT});

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(bg);
        root.setPadding(dp(14), dp(4), dp(14), dp(10));

        // ===== 头部：雷达动画 =====
        radar = new RadarView(this);
        radar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanAction(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        root.addView(radar, rlp);

        // 状态胶囊
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        statusPillBg = pillBg(C_FAINT);
        pill.setBackground(statusPillBg);
        pill.setPadding(dp(14), dp(5), dp(14), dp(5));

        LinearLayout dotWrap = new LinearLayout(this);
        dotWrap.setGravity(Gravity.CENTER);
        statusDot = new View(this);
        statusDot.setBackground(dotBg(C_FAINT));
        dotWrap.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        pill.addView(dotWrap);

        statusText = new TextView(this);
        statusText.setText("  未连接");
        statusText.setTextColor(C_TEXT);
        statusText.setTextSize(13);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(statusText);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(2);
        root.addView(pill, plp);

        // ===== 通道选取（对齐 Dlabel：BLE / 经典蓝牙 SPP / USB） =====
        LinearLayout chRow = new LinearLayout(this);
        chRow.setOrientation(LinearLayout.HORIZONTAL);
        chRow.setGravity(Gravity.CENTER);
        chBle = chip("BLE GATT");
        chSpp = chip("经典蓝牙 SPP");
        chUsb = chip("USB (OTG)");
        TextView[] cs = {chBle, chSpp, chUsb};
        for (int i = 0; i < cs.length; i++) {
            final int idx = i;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            cs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    switchChannel(idx);
                    buzz(v);
                }
            });
            chRow.addView(cs[i], lp);
        }
        styleChannel();
        LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cplp.topMargin = dp(8);
        root.addView(chRow, cplp);

        // 扫描主按钮（随通道切换语义）
        scanBtn = bigBtn("", new int[]{C_ACCENT, C_INDIGO}, 0xFF071018, 0);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanAction(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(10);
        root.addView(scanBtn, slp);

        // ===== 设备列表 =====
        LinearLayout listHead = new LinearLayout(this);
        listHead.setOrientation(LinearLayout.HORIZONTAL);
        listHead.setPadding(dp(2), dp(8), dp(2), dp(4));
        listHead.addView(sectionLabel("◈ 发现的打印机"));
        countText = new TextView(this);
        countText.setText("0 台");
        countText.setTextColor(C_FAINT);
        countText.setTextSize(11);
        countText.setGravity(Gravity.RIGHT);
        listHead.addView(countText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listHead);

        FrameLayout listContainer = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(listAdapter = new DeviceAdapter());
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setCacheColorHint(Color.TRANSPARENT);
        listContainer.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyHint = new TextView(this);
        emptyHint.setText("");
        emptyHint.setTextColor(C_FAINT);
        emptyHint.setTextSize(13);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setLineSpacing(dp(4), 1f);
        listContainer.addView(emptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90), Gravity.CENTER));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f);
        root.addView(listContainer, clp);

        // ===== 运行日志（终端风格） =====
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setPadding(dp(2), dp(2), dp(2), dp(3));
        logHead.addView(sectionLabel("▸ 运行日志"));
        TextView clearBtn = new TextView(this);
        clearBtn.setText("清空");
        clearBtn.setTextColor(C_FAINT);
        clearBtn.setTextSize(11);
        clearBtn.setPadding(dp(10), 0, dp(4), 0);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logText.setText(""); }
        });
        logHead.addView(clearBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logHead);

        consoleScroll = new ScrollView(this);
        consoleScroll.setBackground(roundRect(0xFF0A0F1E, 0xFF1B2A48, dp(10)));
        logText = new TextView(this);
        logText.setTextIsSelectable(true);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(10.5f);
        logText.setTextColor(C_SUB);
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logText.setLineSpacing(dp(2), 1f);
        consoleScroll.addView(logText);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.72f);
        slp2.topMargin = dp(2);
        root.addView(consoleScroll, slp2);

        // ===== 协议芯片 =====
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER);
        chipAuto = chip("AUTO");
        chipEsc = chip("ESC/POS");
        chipTspl = chip("TSPL");
        chipCpcl = chip("CPCL");
        styleChip(chipAuto, true);
        TextView[] pcs = {chipAuto, chipEsc, chipTspl, chipCpcl};
        for (int i = 0; i < pcs.length; i++) {
            final int idx = i;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            pcs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    protocolIndex = idx;
                    styleChip(chipAuto, idx == 0);
                    styleChip(chipEsc, idx == 1);
                    styleChip(chipTspl, idx == 2);
                    styleChip(chipCpcl, idx == 3);
                    buzz(v);
                }
            });
            chips.addView(pcs[i], lp);
        }
        cplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cplp.topMargin = dp(8);
        root.addView(chips, cplp);

        // ===== 操作按钮 2×2 =====
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        connBtn = actionBtn("连接设备", new int[]{C_ACCENT, C_INDIGO}, 0xFF071018);
        testBtn = actionBtn("适配测试", null, C_AMBER);
        row1.addView(connBtn, cellW());
        row1.addView(testBtn, cellW());
        root.addView(row1, rowLp());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        printBtn = actionBtn("打印测试标签", new int[]{C_GREEN, 0xFF059669}, 0xFF04120C);
        discBtn = actionBtn("断开连接", null, C_RED);
        row2.addView(printBtn, cellW());
        row2.addView(discBtn, cellW());
        root.addView(row2, rowLp());

        connBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doConnect(); }
        });
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doAdaptTest(); }
        });
        printBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPrint(); }
        });
        discBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { disconnectAll(); }
        });

        setContentView(root);
        switchChannel(CH_BLE);
    }

    // ---- 通道切换 ----

    private void switchChannel(int ch) {
        disconnectAll();
        channel = ch;
        selected = null;
        styleChannel();
        listAdapter.replaceAll(new ArrayList<DeviceItem>());
        scanBtn.setText(scanLabel());
        emptyHint.setText(hintText());
        listAdapter.refreshMeta();
        appendLog("-- 切换到 " + channelName() + " 通道 --");
        refreshButtons();
    }

    private String channelName() {
        switch (channel) {
            case CH_SPP: return "经典蓝牙 SPP";
            case CH_USB: return "USB (OTG)";
            default: return "BLE GATT";
        }
    }

    private String scanLabel() {
        switch (channel) {
            case CH_SPP: return "☰  列出已配对设备 (SPP)";
            case CH_USB: return "⛶  枚举 USB 打印机 (OTG)";
            default: return "⌖  开始扫描周围打印机";
        }
    }

    private String hintText() {
        switch (channel) {
            case CH_SPP: return "尚未列出已配对设备\n点击上方按钮列出（需先在系统设置配对）";
            case CH_USB: return "未检测到 USB 打印机\n请用 OTG 线连接后重新枚举";
            default: return "周围暂未发现打印机\n点击雷达开始搜索";
        }
    }

    private void styleChannel() {
        styleChip(chBle, channel == CH_BLE);
        styleChip(chSpp, channel == CH_SPP);
        styleChip(chUsb, channel == CH_USB);
    }

    // ---- 控件工厂 ----

    private TextView bigBtn(String text, int[] gradient, int textColor, int stroke) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        if (gradient != null) {
            g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            g.setColors(gradient);
        } else {
            g.setColor(0x1422D3EE);
        }
        g.setCornerRadius(dp(14));
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        pressFx(b);
        return b;
    }

    private TextView actionBtn(String text, int[] gradient, int textColor) {
        TextView b = bigBtn(text, gradient, textColor, gradient == null ? 0xFF2A3C60 : 0);
        b.setTextSize(13);
        return b;
    }

    private LinearLayout.LayoutParams cellW() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private TextView chip(String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(12);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(6), dp(7), dp(6), dp(7));
        return c;
    }

    private void styleChip(TextView c, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(999));
        if (selected) {
            g.setColors(new int[]{C_ACCENT, C_INDIGO});
            g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            c.setTextColor(0xFF071018);
        } else {
            g.setColor(0x1422D3EE);
            g.setStroke(dp(1), 0xFF2A3C60);
            c.setTextColor(C_SUB);
        }
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_ACCENT);
        t.setTextSize(11.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private GradientDrawable pillBg(int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1A22D3EE);
        g.setCornerRadius(dp(999));
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    private GradientDrawable dotBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable roundRect(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    /** 按压缩放 + 震动反馈（华丽但轻量） */
    private void pressFx(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View vv, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        vv.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start();
                        buzz(vv);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        vv.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                        break;
                }
                return false;   // 不消费，click 照常触发
            }
        });
    }

    private void buzz(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------- 动作 ----------

    private BluetoothAdapter btAdapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    /** 扫描/列出动作：随通道切换语义 */
    private void scanAction() {
        BluetoothAdapter ad = btAdapter();
        if (channel != CH_USB && (ad == null || !ad.isEnabled())) {
            toast("蓝牙未开启, 请求打开...");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            return;
        }
        switch (channel) {
            case CH_SPP: listBonded(); break;
            case CH_USB: listUsb(); break;
            default: toggleBleScan(); break;
        }
    }

    private void toggleBleScan() {
        if (scanner.isScanning()) {
            scanner.stop();
        } else {
            appendLog("-- 开始扫描 --");
            radar.setScanning(true);
            scanBtn.setText("◷ 扫描中 · 点击停止 (8s 自动)");
            scanner.start();
            main.postDelayed(new Runnable() {
                @Override public void run() { if (scanner.isScanning()) scanner.stop(); }
            }, 8000);
        }
    }

    /** SPP 通道：列出已配对设备（对应 Dlabel「切换蓝牙模式」） */
    private void listBonded() {
        BluetoothAdapter ad = btAdapter();
        if (ad == null) return;
        Set<BluetoothDevice> set = ad.getBondedDevices();
        List<DeviceItem> items = new ArrayList<>();
        for (BluetoothDevice d : set) {
            items.add(DeviceItem.spp(d, VendorDB.matchByName(d.getName())));
        }
        listAdapter.replaceAll(items);
        appendLog("-- 已配对 " + items.size() + " 台 (SPP 通道) --");
    }

    /** USB 通道：枚举 USB 设备（对应 Dlabel Manifest 的 usb.host） */
    private void listUsb() {
        List<UsbDevice> printers = usb.listPrinters();
        List<DeviceItem> items = new ArrayList<>();
        for (UsbDevice d : printers) {
            items.add(DeviceItem.usbDev(d, usb.hasPermission(d)));
        }
        listAdapter.replaceAll(items);
        appendLog("-- USB 设备 " + items.size() + " 台 --");
    }

    private void disconnectAll() {
        if (connection != null) connection.disconnect();   // buildUi 阶段可能尚未创建
        if (spp != null) spp.disconnect();
        if (usb != null) usb.disconnect();
    }

    private void doConnect() {
        if (selected == null) {
            toast("请先在列表中选择设备");
            return;
        }
        switch (channel) {
            case CH_SPP:
                st = BlePrinterConnection.STATE_CONNECTING;
                setPill("SPP 连接中...", C_AMBER, C_AMBER);
                spp.connect(selected.bt);
                break;
            case CH_USB:
                st = BlePrinterConnection.STATE_CONNECTING;
                setPill("USB 连接中...", C_AMBER, C_AMBER);
                usb.connect(selected.usbDev);
                break;
            default:
                if (selected.profile != null) connection.setProfile(selected.profile);
                connection.connect(selected.bt);
                break;
        }
        refreshButtons();
    }

    /** 对应 Dlabel "打印机适配测试"：发探测指令看是否有回包，判断通道与协议族 */
    private void doAdaptTest() {
        if (st != BlePrinterConnection.STATE_READY) {
            toast("请先连接(通道就绪后测试)");
            return;
        }
        appendLog("-- 适配测试: 发送状态探测指令 --");
        sendBytes(LabelProtocols.probeCommand());
        toast("探测指令已发送, 观察日志回包");
    }

    private void doPrint() {
        if (st != BlePrinterConnection.STATE_READY) {
            toast("请先连接");
            return;
        }
        String title = "多厂家打印测试";
        String body = "DiLabel 技术验证 Demo";
        VendorDB.Protocol proto;
        byte[] data;
        switch (protocolIndex) {
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
        appendLog("-- 发送 " + proto + " 指令 " + data.length + " 字节 --");
        sendBytes(data);
    }

    /** 按当前通道路由发送（BLE 走分片流控；SPP/USB 是流式整段写） */
    private void sendBytes(byte[] data) {
        switch (channel) {
            case CH_SPP: spp.sendData(data); break;
            case CH_USB: usb.sendData(data); break;
            default: connection.sendData(data); break;
        }
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
                boolean isNew = listAdapter.upsert(d);
                if (isNew) {   // RSSI 刷新不重复记日志
                    appendLog("发现: " + d.displayName() + " rssi=" + d.rssi
                            + (d.profile == null ? "" : " -> " + d.profile.brand));
                }
            }
        });
    }

    @Override
    public void onScanFinished() {
        main.post(new Runnable() {
            @Override public void run() {
                radar.setScanning(false);
                scanBtn.setText(scanLabel());
                appendLog("-- 扫描结束, 共 " + listAdapter.getCount() + " 台 --");
                refreshButtons();
            }
        });
    }

    @Override
    public void onScanFailed(final int errorCode) {
        main.post(new Runnable() {
            @Override public void run() {
                appendLog("[错误] BLE 扫描启动失败 code=" + errorCode
                        + " (2=应用注册失败/权限, 1=已满, 3=内部错误, 6=频繁启停)");
            }
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
                appendLog(sb.toString() + (data.length > 32 ? "..." : "")
                        + " (" + data.length + "字节)");
            }
        });
    }

    @Override
    public void onState(final int state) {
        main.post(new Runnable() {
            @Override public void run() {
                st = state;
                String s;
                int color;
                switch (state) {
                    case BlePrinterConnection.STATE_CONNECTING:
                        s = "连接中..."; color = C_AMBER; startPulse(); break;
                    case BlePrinterConnection.STATE_CONNECTED:
                        s = "已连接 · 发现服务中"; color = C_BLUE; stopPulse(); break;
                    case BlePrinterConnection.STATE_READY:
                        s = "就绪 · 可打印"; color = C_GREEN; stopPulse(); break;
                    default:
                        s = "未连接"; color = C_FAINT; stopPulse(); break;
                }
                setPill(s, color, color);
                refreshButtons();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2) {
            BluetoothAdapter ad = btAdapter();
            appendLog(ad != null && ad.isEnabled() ? "-- 蓝牙已开启 --" : "-- 用户拒绝开启蓝牙 --");
        }
    }

    // ---------- 状态与日志 ----------

    private void setPill(String s, int dotColor, int strokeColor) {
        statusText.setText("  " + s);
        statusDot.getBackground().setTint(dotColor);
        statusPillBg.setStroke(dp(1), strokeColor);
    }

    private void startPulse() {
        if (dotPulse != null && dotPulse.isRunning()) return;
        dotPulse = ValueAnimator.ofFloat(1f, 0.25f);
        dotPulse.setDuration(500);
        dotPulse.setRepeatCount(ValueAnimator.INFINITE);
        dotPulse.setRepeatMode(ValueAnimator.REVERSE);
        dotPulse.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                statusDot.setAlpha((Float) a.getAnimatedValue());
            }
        });
        dotPulse.start();
    }

    private void stopPulse() {
        if (dotPulse != null) dotPulse.cancel();
        statusDot.setAlpha(1f);
    }

    private void refreshButtons() {
        if (connection == null) return;   // buildUi 阶段连接器尚未创建
        boolean hasSel = selected != null;
        en(connBtn, hasSel && st != BlePrinterConnection.STATE_CONNECTING);
        en(testBtn, st == BlePrinterConnection.STATE_READY);
        en(printBtn, st == BlePrinterConnection.STATE_READY);
        en(discBtn, st != BlePrinterConnection.STATE_DISCONNECTED);
    }

    private void en(View v, boolean ok) {
        v.setEnabled(ok);
        v.setAlpha(ok ? 1f : 0.35f);
    }

    private void log(String s) {
        appendLog(s);
    }

    private void appendLog(String s) {
        int color = 0xFF93A6C4;
        if (s.contains("失败") || s.contains("错误") || s.contains("无法")
                || s.contains("false") || s.contains("断开")) color = C_RED;
        else if (s.contains("就绪") || s.contains("命中") || s.contains("已连接")) color = C_GREEN;
        else if (s.startsWith("发现:") || s.startsWith("选中:") || s.startsWith("--")) color = C_AMBER;
        else if (s.startsWith("[BLE") || s.startsWith("[SPP") || s.startsWith("[USB"))
            color = 0xFF7DD3FC;
        SpannableStringBuilder ssb = new SpannableStringBuilder(s + "\n");
        ssb.setSpan(new ForegroundColorSpan(color), 0, ssb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        logText.append(ssb);
        consoleScroll.post(new Runnable() {
            @Override public void run() { consoleScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 设备条目（三通道统一模型） ----------

    /** 列表条目：一个条目可能是 BLE / SPP / USB 设备（静态嵌套类，可放静态工厂） */
    private static class DeviceItem {
        final int channel;
        final String name;
        final String sub;
        final VendorDB.Profile profile;
        final int rssi;              // 仅 BLE 有效
        final BluetoothDevice bt;    // BLE/SPP 用
        final UsbDevice usbDev;      // USB 用
        final boolean usbGranted;    // USB 权限状态

        DeviceItem(int ch, String name, String sub, VendorDB.Profile p, int rssi,
                   BluetoothDevice bt, UsbDevice ud, boolean granted) {
            this.channel = ch;
            this.name = name;
            this.sub = sub;
            this.profile = p;
            this.rssi = rssi;
            this.bt = bt;
            this.usbDev = ud;
            this.usbGranted = granted;
        }

        static DeviceItem ble(BleScanner.PrinterDevice d) {
            return new DeviceItem(CH_BLE, d.displayName(),
                    d.device.getAddress() + " · " + d.rssi + " dBm",
                    d.profile, d.rssi, d.device, null, false);
        }

        static DeviceItem spp(BluetoothDevice d, VendorDB.Profile p) {
            String n = (d.getName() == null || d.getName().isEmpty())
                    ? d.getAddress() : d.getName();
            return new DeviceItem(CH_SPP, n, d.getAddress() + " · 已配对", p, 0,
                    d, null, false);
        }

        static DeviceItem usbDev(UsbDevice d, boolean granted) {
            return new DeviceItem(CH_USB,
                    "USB 设备 " + String.format("%04X:%04X", d.getVendorId(), d.getProductId()),
                    String.format("vid=0x%04X pid=0x%04X · %s",
                            d.getVendorId(), d.getProductId(),
                            granted ? "已授权" : "未授权"),
                    VendorDB.matchByName(d.getDeviceName()), 0,
                    null, d, granted);
        }
    }

    /** 设备卡片列表适配器（三通道统一） */
    private class DeviceAdapter extends BaseAdapter {
        private final List<DeviceItem> items = new ArrayList<>();

        /** BLE 扫描按地址去重插入/刷新 RSSI；已识别厂商的置顶。返回 true 表示新设备 */
        boolean upsert(BleScanner.PrinterDevice d) {
            String key = d.device.getAddress();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).bt != null
                        && key.equals(items.get(i).bt.getAddress())) {
                    items.set(i, DeviceItem.ble(d));
                    notifyDataSetChanged();
                    refreshMeta();
                    return false;
                }
            }
            DeviceItem item = DeviceItem.ble(d);
            if (d.profile != null && items.size() > 0) items.add(0, item);
            else items.add(item);
            notifyDataSetChanged();
            refreshMeta();
            return true;
        }

        /** SPP/USB 通道整体重建列表 */
        void replaceAll(List<DeviceItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
            refreshMeta();
        }

        private void refreshMeta() {
            countText.setText(getCount() + " 台");
            emptyHint.setVisibility(getCount() == 0 ? View.VISIBLE : View.GONE);
        }

        @Override public int getCount() { return items.size(); }
        @Override public DeviceItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card = (convertView instanceof LinearLayout)
                    ? (LinearLayout) convertView : buildCard();
            CardHolder h = (CardHolder) card.getTag();

            DeviceItem d = items.get(position);
            h.name.setText(d.name);
            h.sub.setText(d.sub);

            int brandColor = brandColor(d.profile == null ? null : d.profile.brand);
            h.chip.setText(d.profile == null ? "未知厂商" : d.profile.brand);
            GradientDrawable cg = new GradientDrawable();
            cg.setCornerRadius(dp(999));
            cg.setColor((brandColor & 0x00FFFFFF) | 0x26000000);
            cg.setStroke(dp(1), (brandColor & 0x00FFFFFF) | 0x66000000);
            h.chip.setTextColor(brandColor);
            h.chip.setBackground(cg);

            if (d.channel == CH_BLE) {
                h.bars.setVisibility(View.VISIBLE);
                h.bars.setRssi(d.rssi);
            } else {
                h.bars.setVisibility(View.GONE);
            }
            h.tag.setText(d.channel == CH_SPP ? "SPP" : d.channel == CH_USB ? "USB" : "BLE");
            GradientDrawable tg = new GradientDrawable();
            tg.setCornerRadius(dp(999));
            tg.setStroke(dp(1), 0xFF2A3C60);
            tg.setColor(0x1422D3EE);
            h.tag.setTextColor(C_SUB);
            h.tag.setBackground(tg);

            boolean sel = selected == d;
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(14));
            cardBg.setColor(sel ? C_CARD_SEL : C_CARD);
            cardBg.setStroke(dp(1.2f), sel ? C_ACCENT : C_CARD_STROKE);
            card.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x22FFFFFF), cardBg, null));

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selected = items.get(position);
                    appendLog("选中: " + selected.name
                            + (selected.profile == null ? " (未知厂商)"
                            : " (" + selected.profile.brand + ")"));
                    notifyDataSetChanged();
                    refreshButtons();
                }
            });
            return card;
        }

        private LinearLayout buildCard() {
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(9), dp(12), dp(9));

            LinearLayout info = new LinearLayout(MainActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(MainActivity.this);
            name.setTextColor(C_TEXT);
            name.setTextSize(14.5f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            info.addView(name);
            TextView sub = new TextView(MainActivity.this);
            sub.setTextColor(C_FAINT);
            sub.setTextSize(10.5f);
            sub.setTypeface(Typeface.MONOSPACE);
            sub.setPadding(0, dp(1), 0, 0);
            info.addView(sub);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            card.addView(info, ilp);

            TextView vChip = new TextView(MainActivity.this);
            vChip.setTextSize(10.5f);
            vChip.setTypeface(Typeface.DEFAULT_BOLD);
            vChip.setPadding(dp(9), dp(3), dp(9), dp(3));
            card.addView(vChip);

            TextView tag = new TextView(MainActivity.this);
            tag.setTextSize(9.5f);
            tag.setTypeface(Typeface.MONOSPACE);
            tag.setPadding(dp(6), dp(3), dp(6), dp(3));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(6);
            card.addView(tag, tlp);

            SignalBarsView bars = new SignalBarsView(MainActivity.this);
            LinearLayout.LayoutParams barlp = new LinearLayout.LayoutParams(dp(21), dp(15));
            barlp.leftMargin = dp(9);
            card.addView(bars, barlp);

            card.setTag(new CardHolder(name, sub, vChip, tag, bars));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = dp(5);
            card.setLayoutParams(cardLp);
            pressFx(card);
            return card;
        }

        /** 卡片控件引用（避免 setTag(int,...) 要求资源 ID 的问题） */
        private class CardHolder {
            final TextView name, sub, chip, tag;
            final SignalBarsView bars;
            CardHolder(TextView n, TextView s, TextView c, TextView t, SignalBarsView b) {
                name = n; sub = s; chip = c; tag = t; bars = b;
            }
        }
    }

    private int brandColor(String brand) {
        if (brand == null) return 0xFF64748B;
        if (brand.contains("佳博")) return 0xFFF59E0B;
        if (brand.contains("汉印")) return 0xFFF472B6;
        if (brand.contains("芯烨")) return 0xFF34D399;
        if (brand.contains("精臣")) return 0xFF60A5FA;
        if (brand.contains("FeasyCom")) return 0xFFA78BFA;
        return 0xFF94A3B8;
    }
}
