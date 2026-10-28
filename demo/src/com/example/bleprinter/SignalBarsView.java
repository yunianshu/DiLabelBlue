package com.example.bleprinter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * RSSI 信号强度条（4 格，颜色随强弱变化），纯代码绘制。
 */
public class SignalBarsView extends View {

    private int level = 1;   // 1..4
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SignalBarsView(Context c) {
        super(c);
    }

    public void setRssi(int rssi) {
        int l = rssi >= -55 ? 4 : rssi >= -70 ? 3 : rssi >= -85 ? 2 : 1;
        if (l != level) {
            level = l;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int color;
        switch (level) {
            case 4: color = 0xFF34D399; break;
            case 3: color = 0xFFA3E635; break;
            case 2: color = 0xFFFBBF24; break;
            default: color = 0xFFF87171; break;
        }
        float w = dp(3.4f), gap = dp(2.2f), maxH = dp(14);
        for (int i = 0; i < 4; i++) {
            float h = maxH * (i + 1) / 4f;
            float x = i * (w + gap);
            p.setColor(i < level ? color : 0xFF2A3A58);
            canvas.drawRoundRect(x, maxH - h, x + w, maxH, dp(1.4f), dp(1.4f), p);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension((int) dp(21), (int) dp(15));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
