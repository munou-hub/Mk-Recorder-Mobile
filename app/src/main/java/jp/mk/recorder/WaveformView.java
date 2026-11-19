package jp.mk.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 波形表示 + 再生位置 + マーカー。タップ/ドラッグでシーク。 */
public class WaveformView extends View {
    public interface OnSeekListener { void onSeek(double fraction); }

    private final Paint wavePaint = new Paint();
    private final Paint playPaint = new Paint();
    private final Paint markerPaint = new Paint();
    private final Paint bgPaint = new Paint();

    private float[] mins;
    private float[] maxs;
    private double progress = 0;
    private final List<Double> markers = new ArrayList<>();
    private double durationSec = 0;
    private OnSeekListener listener;

    public WaveformView(Context ctx) { super(ctx); init(); }
    public WaveformView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        wavePaint.setColor(Color.parseColor("#38BDF8"));
        playPaint.setColor(Color.parseColor("#F8FAFC"));
        playPaint.setStrokeWidth(3f);
        markerPaint.setColor(Color.parseColor("#F59E0B"));
        markerPaint.setStrokeWidth(3f);
        bgPaint.setColor(Color.parseColor("#000000"));
    }

    public void setPeaks(float[] mins, float[] maxs) {
        this.mins = mins; this.maxs = maxs;
        invalidate();
    }

    public void setProgress(double p) {
        progress = p;
        invalidate();
    }

    public void setMarkers(List<Double> m, double durationSec) {
        markers.clear();
        if (m != null) markers.addAll(m);
        this.durationSec = durationSec;
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener l) { listener = l; }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bgPaint);
        if (mins != null && maxs != null && mins.length > 0) {
            float mid = h / 2f;
            int n = mins.length;
            for (int x = 0; x < w; x++) {
                int idx = (int) ((double) x / w * n);
                if (idx >= n) idx = n - 1;
                float top = mid - maxs[idx] * mid;
                float bot = mid - mins[idx] * mid;
                if (bot - top < 1f) bot = top + 1f;
                c.drawRect(x, top, x + 1, bot, wavePaint);
            }
        }
        if (durationSec > 0) {
            for (Double m : markers) {
                float x = (float) (m / durationSec * w);
                c.drawLine(x, 0, x, h * 0.35f, markerPaint);
            }
        }
        float px = (float) (progress * w);
        c.drawLine(px, 0, px, h, playPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            if (listener != null && getWidth() > 0) {
                double f = e.getX() / getWidth();
                if (f < 0) f = 0;
                if (f > 1) f = 1;
                listener.onSeek(f);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }
}
