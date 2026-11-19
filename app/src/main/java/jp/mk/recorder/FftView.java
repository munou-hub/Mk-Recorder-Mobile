package jp.mk.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** FFT スペクトル表示。対数周波数軸、-90..0 dB。 */
public class FftView extends View {
    private final Paint linePaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint bgPaint = new Paint();

    private float[] db;
    private int sampleRate = 48000;
    // 対数軸のビン対応表キャッシュ (幅/レート/本数が変わった時だけ再計算)
    private int[] binMap;
    private int binMapW = -1, binMapRate = -1, binMapBins = -1;

    public FftView(Context ctx) { super(ctx); init(); }
    public FftView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        linePaint.setColor(Color.parseColor("#22C55E"));
        linePaint.setStrokeWidth(2f);
        linePaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.parseColor("#334155"));
        gridPaint.setStrokeWidth(1f);
        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(20f);
        bgPaint.setColor(Color.parseColor("#000000"));
    }

    public void setData(float[] db, int sampleRate) {
        this.db = db;
        this.sampleRate = sampleRate;
        invalidate();
    }

    private double freqToX(double f, double fMin, double fMax, int w) {
        return Math.log(f / fMin) / Math.log(fMax / fMin) * w;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bgPaint);
        double fMin = 20, fMax = sampleRate / 2.0;
        // グリッド
        double[] freqs = {100, 1000, 10000};
        String[] labels = {"100", "1k", "10k"};
        for (int i = 0; i < freqs.length; i++) {
            if (freqs[i] >= fMax) continue;
            float x = (float) freqToX(freqs[i], fMin, fMax, w);
            c.drawLine(x, 0, x, h, gridPaint);
            c.drawText(labels[i], x + 3, h - 4, textPaint);
        }
        double[] dbs = {-30, -60, -90};
        for (double d : dbs) {
            float y = (float) ((0 - d) / 100.0 * h);
            c.drawLine(0, y, w, y, gridPaint);
        }
        if (db == null || db.length < 2) return;
        Path path = new Path();
        int bins = db.length;
        if (binMap == null || binMapW != w || binMapRate != sampleRate || binMapBins != bins) {
            binMap = new int[w];
            for (int x = 0; x < w; x++) {
                double f = fMin * Math.pow(fMax / fMin, (double) x / w);
                int bin = (int) (f / (sampleRate / 2.0) * bins);
                if (bin < 0) bin = 0;
                if (bin >= bins) bin = bins - 1;
                binMap[x] = bin;
            }
            binMapW = w; binMapRate = sampleRate; binMapBins = bins;
        }
        for (int x = 0; x < w; x++) {
            int bin = binMap[x];
            // 近傍最大で間引きの落ち込みを防ぐ
            float v = db[bin];
            if (bin + 1 < bins) v = Math.max(v, db[bin + 1]);
            float y = (float) ((0 - Math.max(-100, Math.min(0, v))) / 100.0 * h);
            if (x == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, linePaint);
    }
}
