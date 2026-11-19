package jp.mk.recorder;

/** 軽量 FFT (radix-2, Hann 窓)。再生時のスペクトル表示用。 */
public class Fft {
    private final int n;
    private final double[] re;
    private final double[] im;
    private final double[] window;

    public Fft(int n) {
        this.n = n;
        re = new double[n];
        im = new double[n];
        window = new double[n];
        for (int i = 0; i < n; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
        }
    }

    public int size() {
        return n;
    }

    /**
     * samples (モノラル化済み float) の offset から n サンプルを解析し、
     * n/2 本の dB 値 (フルスケールサイン ≒ 0dB) を返す。
     */
    public float[] magnitudeDb(float[] samples, int offset) {
        for (int i = 0; i < n; i++) {
            int idx = offset + i;
            double v = (idx >= 0 && idx < samples.length) ? samples[idx] : 0;
            re[i] = v * window[i];
            im[i] = 0;
        }
        fft(re, im);
        float[] out = new float[n / 2];
        for (int k = 0; k < n / 2; k++) {
            double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            // Hann のコヒーレントゲイン 0.5 を補正し、片側スペクトルとして 2 倍
            double amp = 2.0 * mag / (n * 0.5);
            double db = 20.0 * Math.log10(Math.max(amp, 1e-6));
            out[k] = (float) Math.max(db, -100.0);
        }
        return out;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        // bit reversal
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cwr = 1, cwi = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = i + k + len / 2;
                    double vr = re[v] * cwr - im[v] * cwi;
                    double vi = re[v] * cwi + im[v] * cwr;
                    re[v] = re[u] - vr; im[v] = im[u] - vi;
                    re[u] += vr; im[u] += vi;
                    double nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
            }
        }
    }
}
