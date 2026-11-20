package jp.mk.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** ゲインリダクション表示バー。0 dB が右端、左へ潰れ量が伸びる (DAW 式)。 */
public class GrView extends View {
    public static final double MAX_GR = 20.0;

    private final Paint bg = new Paint();
    private final Paint fill = new Paint();
    private double grDb = 0;

    public GrView(Context ctx) { super(ctx); init(); }
    public GrView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        bg.setColor(Color.parseColor("#1A1A1A"));
    }

    public void setGr(double grDb) {
        double v = Math.max(0, Math.min(MAX_GR, grDb));
        if (Math.abs(v - this.grDb) > 0.05) {
            this.grDb = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        c.drawRect(0, 0, w, h, bg);
        if (grDb > 0.05) {
            fill.setColor(grDb < 6 ? Color.parseColor("#22C55E")
                    : grDb < 12 ? Color.parseColor("#EAB308") : Color.parseColor("#EF4444"));
            float fw = (float) (w * grDb / MAX_GR);
            c.drawRect(w - fw, 0, w, h, fill);
        }
    }
}
