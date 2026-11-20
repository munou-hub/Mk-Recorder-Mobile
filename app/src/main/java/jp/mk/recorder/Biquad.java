package jp.mk.recorder;

/** RBJ Audio EQ Cookbook のバイクアッドフィルタ (係数は倍精度)。 */
public class Biquad {
    public static final int LOW_SHELF = 0;
    public static final int PEAKING = 1;
    public static final int HIGH_SHELF = 2;

    private double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    private final double[] x1 = new double[2], x2 = new double[2];
    private final double[] y1 = new double[2], y2 = new double[2];
    private boolean identity = true;

    public void set(int type, double freq, double gainDb, double q, int sampleRate) {
        if (gainDb == 0.0 || freq <= 0 || sampleRate <= 0) {
            identity = true;
            return;
        }
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0n, b1n, b2n, a0n, a1n, a2n;
        switch (type) {
            case LOW_SHELF: {
                double s = 2.0 * Math.sqrt(A) * alpha;
                b0n = A * ((A + 1) - (A - 1) * cos + s);
                b1n = 2 * A * ((A - 1) - (A + 1) * cos);
                b2n = A * ((A + 1) - (A - 1) * cos - s);
                a0n = (A + 1) + (A - 1) * cos + s;
                a1n = -2 * ((A - 1) + (A + 1) * cos);
                a2n = (A + 1) + (A - 1) * cos - s;
                break;
            }
            case HIGH_SHELF: {
                double s = 2.0 * Math.sqrt(A) * alpha;
                b0n = A * ((A + 1) + (A - 1) * cos + s);
                b1n = -2 * A * ((A - 1) + (A + 1) * cos);
                b2n = A * ((A + 1) + (A - 1) * cos - s);
                a0n = (A + 1) - (A - 1) * cos + s;
                a1n = 2 * ((A - 1) - (A + 1) * cos);
                a2n = (A + 1) - (A - 1) * cos - s;
                break;
            }
            default: { // PEAKING
                b0n = 1 + alpha * A;
                b1n = -2 * cos;
                b2n = 1 - alpha * A;
                a0n = 1 + alpha / A;
                a1n = -2 * cos;
                a2n = 1 - alpha / A;
                break;
            }
        }
        b0 = b0n / a0n; b1 = b1n / a0n; b2 = b2n / a0n;
        a1 = a1n / a0n; a2 = a2n / a0n;
        identity = false;
    }

    public void reset() {
        x1[0] = x1[1] = x2[0] = x2[1] = 0;
        y1[0] = y1[1] = y2[0] = y2[1] = 0;
    }

    public double process(int ch, double x) {
        if (identity) return x;
        double y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch];
        x2[ch] = x1[ch]; x1[ch] = x;
        y2[ch] = y1[ch]; y1[ch] = y;
        return y;
    }
}
