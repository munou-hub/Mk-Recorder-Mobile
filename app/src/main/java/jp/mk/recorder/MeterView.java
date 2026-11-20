package jp.mk.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 横向き 2 本レベルメーター。ピークホールドとクリップラッチ内蔵。 */
public class MeterView extends View {
    public static final double MIN_DB = -60.0;
    public static final double MAX_DB = 3.0;

    private final Paint barBg = new Paint();
    private final Paint barGreen = new Paint();
    private final Paint barYellow = new Paint();
    private final Paint barRed = new Paint();
    private final Paint peakPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint clipPaint = new Paint();

    private double levelL = Meters.FLOOR, levelR = Meters.FLOOR;
    private double holdL = Meters.FLOOR, holdR = Meters.FLOOR;
    private long holdTimeL = 0, holdTimeR = 0;
    private boolean clipL = false, clipR = false;
    private boolean stereo = true;
    private String label = "LUFS";
    // 表示値 (描画側でターゲットへ滑らかに追従させる)
    private double dispL = Meters.FLOOR, dispR = Meters.FLOOR;
    private long lastDrawMs = 0;

    public MeterView(Context ctx) { super(ctx); init(); }
    public MeterView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        barBg.setColor(Color.parseColor("#1A1A1A"));
        barGreen.setColor(Color.parseColor("#22C55E"));
        barYellow.setColor(Color.parseColor("#EAB308"));
        barRed.setColor(Color.parseColor("#EF4444"));
        peakPaint.setColor(Color.parseColor("#F8FAFC"));
        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(26f);
        clipPaint.setColor(Color.parseColor("#7F1D1D"));
    }

    public void setLabel(String label) { this.label = label; }

    public void setStereo(boolean stereo) { this.stereo = stereo; }

    private long lastSetMs = 0;

    public void setLevels(double lDb, double rDb, double peakLLin, double peakRLin) {
        long now = System.currentTimeMillis();
        double dt = lastSetMs == 0 ? 0 : (now - lastSetMs) / 1000.0;
        lastSetMs = now;
        boolean changed = Math.abs(lDb - levelL) > 0.05 || Math.abs(rDb - levelR) > 0.05;
        levelL = lDb; levelR = rDb;
        // ピークホールド: 1.2 秒保持したあと時間ベースで滑らかに落とす
        if (lDb >= holdL) { holdL = lDb; holdTimeL = now; }
        else if (now - holdTimeL > 1200) {
            double nh = Math.max(lDb, holdL - 18.0 * dt);
            if (nh != holdL) changed = true;
            holdL = nh;
        }
        if (rDb >= holdR) { holdR = rDb; holdTimeR = now; }
        else if (now - holdTimeR > 1200) {
            double nh = Math.max(rDb, holdR - 18.0 * dt);
            if (nh != holdR) changed = true;
            holdR = nh;
        }
        if (peakLLin >= 0.999 && !clipL) { clipL = true; changed = true; }
        if (peakRLin >= 0.999 && !clipR) { clipR = true; changed = true; }
        if (changed) invalidate();
    }

    public void clearClip() {
        clipL = clipR = false;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            clearClip();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private float dbToX(double db, float left, float width) {
        double t = (db - MIN_DB) / (MAX_DB - MIN_DB);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return left + (float) (t * width);
    }

    private static double chase(double disp, double target, double dt) {
        if (target >= disp) return target; // attack は即時
        return Math.max(target, disp - 28.0 * dt); // release は 28 dB/s
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        // 表示値をターゲットへ追従させる
        long now = System.currentTimeMillis();
        double dt = lastDrawMs == 0 ? 0.016 : Math.min(0.1, (now - lastDrawMs) / 1000.0);
        lastDrawMs = now;
        dispL = chase(dispL, levelL, dt);
        dispR = chase(dispR, levelR, dt);
        float labelW = 52f;
        float clipW = 34f;
        float barW = w - labelW - clipW - 12f;
        float barH = stereo ? (h - 18f) / 2f : h - 14f;
        drawBar(c, 0, labelW, barW, barH, dispL, holdL, clipL, "L");
        if (stereo) {
            drawBar(c, barH + 10f, labelW, barW, barH, dispR, holdR, clipR, "R");
        }
        // まだ動いている間は次のフレームを予約する
        if (dispL != levelL || dispR != levelR
                || (holdL > dispL && now - holdTimeL > 1200)
                || (holdR > dispR && now - holdTimeR > 1200)) {
            postInvalidateDelayed(16);
        }
        // スケール目盛り
        double[] ticks = {-60, -40, -20, -10, -5, 0};
        textPaint.setTextSize(20f);
        for (double t : ticks) {
            float x = dbToX(t, labelW, barW);
            c.drawText(String.valueOf((int) t), x - 8f, h - 1f, textPaint);
        }
    }

    private void drawBar(Canvas c, float top, float left, float width, float height,
                         double level, double hold, boolean clip, String chLabel) {
        textPaint.setTextSize(26f);
        c.drawText(chLabel, 8f, top + height * 0.72f, textPaint);
        c.drawRect(left, top, left + width, top + height, barBg);
        float x = dbToX(level, left, width);
        float xYellow = dbToX(-9, left, width);
        float xRed = dbToX(-3, left, width);
        // 緑
        c.drawRect(left, top, Math.min(x, xYellow), top + height, barGreen);
        // 黄
        if (x > xYellow) c.drawRect(xYellow, top, Math.min(x, xRed), top + height, barYellow);
        // 赤
        if (x > xRed) c.drawRect(xRed, top, Math.min(x, left + width), top + height, barRed);
        // ピークホールド
        float hx = dbToX(hold, left, width);
        c.drawRect(hx - 2f, top, hx + 2f, top + height, peakPaint);
        // 数値
        String val = level <= Meters.FLOOR + 0.01 ? "-∞" : String.format("%.1f", level);
        c.drawText(val, left + 6f, top + height * 0.72f, textPaint);
        // クリップランプ
        float cx = left + width + 6f;
        clipPaint.setColor(clip ? Color.parseColor("#EF4444") : Color.parseColor("#7F1D1D"));
        c.drawRect(cx, top, cx + 26f, top + height, clipPaint);
    }
}
