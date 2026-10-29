package com.example.bleprinter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 扫描雷达动画视图（纯代码绘制，零资源依赖）：
 *  - 静止：径向微光 + 同心圆 + 十字准线 + 中央打印机图标；
 *  - 扫描中：扇形扫掠光旋转 + 向外扩散波纹 + 状态灯变绿。
 * 点击雷达可触发扫描（由外部设置 OnClickListener）。
 */
public class RadarView extends View {

    private int accent = 0xFF22D3EE;        // 主色（随主题切换）
    private int idleBody = 0xFF3B5170;      // 静止时机身色

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator rotateAnim;
    private ValueAnimator pingAnim;
    private float sweepAngle;
    private float pingT;
    private boolean scanning;

    /** 主题切换入口：accent 主色 + 静止机身色 */
    public void setColors(int accent, int idleBody) {
        this.accent = accent;
        this.idleBody = idleBody;
        invalidate();
    }

    /** 主色 + alpha 合成 */
    private int tint(int alpha) {
        return (alpha << 24) | (accent & 0x00FFFFFF);
    }

    public RadarView(Context c) {
        super(c);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.1f));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1f));
        fillPaint.setStyle(Paint.Style.FILL);
        sweepPaint.setStyle(Paint.Style.FILL);
    }

    public void setScanning(boolean s) {
        if (scanning == s) return;
        scanning = s;
        configAnimators();
        invalidate();
    }

    private void configAnimators() {
        if (rotateAnim == null) {
            rotateAnim = ValueAnimator.ofFloat(0f, 360f);
            rotateAnim.setDuration(2400);
            rotateAnim.setRepeatCount(ValueAnimator.INFINITE);
            rotateAnim.setInterpolator(new LinearInterpolator());
            rotateAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    sweepAngle = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            pingAnim = ValueAnimator.ofFloat(0f, 1f);
            pingAnim.setDuration(1800);
            pingAnim.setRepeatCount(ValueAnimator.INFINITE);
            pingAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    pingT = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
        }
        if (scanning) {
            if (!rotateAnim.isRunning()) rotateAnim.start();
            if (!pingAnim.isRunning()) pingAnim.start();
        } else {
            rotateAnim.cancel();
            pingAnim.cancel();
            sweepAngle = 0;
            pingT = 0;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (rotateAnim != null) rotateAnim.cancel();
        if (pingAnim != null) pingAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy) - dp(6);

        // 径向微光背景
        fillPaint.setShader(new RadialGradient(cx, cy, maxR,
                new int[]{tint(0x18), tint(0x08), 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, maxR, fillPaint);
        fillPaint.setShader(null);

        // 同心圆 + 十字线
        ringPaint.setColor(tint(0x23));
        for (int i = 1; i <= 3; i++) {
            canvas.drawCircle(cx, cy, maxR * i / 3f, ringPaint);
        }
        linePaint.setColor(tint(0x1A));
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, linePaint);
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, linePaint);

        // 扫描中：旋转扇形扫掠 + 扩散波纹
        if (scanning) {
            sweepPaint.setShader(new SweepGradient(cx, cy,
                    new int[]{tint(0x00), tint(0x00), tint(0x33), tint(0xB4)}, null));
            canvas.save();
            canvas.rotate(sweepAngle, cx, cy);
            canvas.drawCircle(cx, cy, maxR, sweepPaint);
            canvas.restore();

            for (int k = 0; k < 2; k++) {   // 双波纹错相
                float t = (pingT + k * 0.5f) % 1f;
                ringPaint.setColor(tint((int) (0x66 * (1 - t))));
                canvas.drawCircle(cx, cy, maxR * t, ringPaint);
            }
        }

        // 中央打印机图标
        drawPrinter(canvas, cx, cy);
    }

    private void drawPrinter(Canvas canvas, float cx, float cy) {
        float bw = dp(64), bh = dp(36);

        // 扫描时图标外圈呼吸光
        if (scanning) {
            fillPaint.setColor(tint((int) (0x2E * (0.5f + 0.5f * pingT))));
            canvas.drawCircle(cx, cy, dp(44), fillPaint);
        }

        // 机身
        RectF body = new RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        iconPaint.setColor(scanning ? accent : idleBody);
        canvas.drawRoundRect(body, dp(9), dp(9), iconPaint);

        // 顶部出纸
        RectF paper = new RectF(cx - dp(18), cy - dp(30), cx + dp(18), cy - dp(2));
        iconPaint.setColor(0xFFE2E8F0);
        canvas.drawRoundRect(paper, dp(3), dp(3), iconPaint);
        iconPaint.setColor(0xFF94A3B8);
        iconPaint.setStrokeWidth(dp(1.6f));
        for (int i = 0; i < 3; i++) {
            float ly = cy - dp(24) + i * dp(6);
            canvas.drawLine(cx - dp(12), ly, cx + dp(12) - i * dp(5), ly, iconPaint);
        }

        // 出纸槽
        iconPaint.setColor(0x59000000);
        RectF slot = new RectF(cx - dp(20), cy + dp(3), cx + dp(20), cy + dp(12));
        canvas.drawRoundRect(slot, dp(3), dp(3), iconPaint);

        // 状态灯
        ledPaint.setColor(scanning ? 0xFF34D399 : 0xFF64748B);
        canvas.drawCircle(cx + dp(24), cy - dp(10), dp(2.6f), ledPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
