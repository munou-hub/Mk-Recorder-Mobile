package jp.mk.recorder;

/**
 * フィードフォワード・コンプレッサー。ステレオリンク検出 (L/R の大きい方)、
 * ソフトニー付き静特性、ゲインリダクションを dB 領域で attack/release 平滑化する。
 * Fx のパラメータを参照する。
 */
public class Compressor {
    private double grSmoothDb = 0; // 現在のゲインリダクション (正の値 = 潰している量)
    private volatile double grNowDb = 0;
    private int syncedVersion = -1;
    private int syncedRate = -1;
    private double ratio = 3.0;
    private double atkCoef = 0, relCoef = 0;

    public void reset() {
        grSmoothDb = 0;
        grNowDb = 0;
    }

    /** UI 表示用の現在のゲインリダクション (dB、正の値)。 */
    public double grDb() {
        return grNowDb;
    }

    private void sync(int sampleRate) {
        if (syncedVersion == Fx.version && syncedRate == sampleRate) return;
        syncedVersion = Fx.version;
        syncedRate = sampleRate;
        ratio = Fx.compRatio10 >= 200 ? 1000.0 : Fx.compRatio10 / 10.0;
        double atkSec = Fx.compAttack10 / 10000.0;
        double relSec = Fx.compReleaseMs / 1000.0;
        atkCoef = Math.exp(-1.0 / (atkSec * sampleRate));
        relCoef = Math.exp(-1.0 / (relSec * sampleRate));
    }

    /** 静特性: 入力レベル (dBFS) に対するゲインリダクション (dB)。 */
    private double staticGr(double inDb) {
        double thr = Fx.compThr10 / 10.0;
        double knee = Fx.compKnee10 / 10.0;
        double over = inDb - thr;
        double slope = 1.0 - 1.0 / ratio;
        if (knee > 0) {
            if (over <= -knee / 2) return 0;
            if (over >= knee / 2) return slope * over;
            double t = over + knee / 2;
            return slope * t * t / (2 * knee);
        }
        return over > 0 ? slope * over : 0;
    }

    /** interleaved float バッファをその場で処理する。 */
    public void process(float[] buf, int frames, int channels, int sampleRate) {
        sync(sampleRate);
        double makeup = Fx.compMakeup10 / 10.0;
        for (int i = 0; i < frames; i++) {
            int o = i * channels;
            double det = Math.abs(buf[o]);
            if (channels > 1) det = Math.max(det, Math.abs(buf[o + 1]));
            double inDb = det > 1e-7 ? 20.0 * Math.log10(det) : -120.0;
            double target = staticGr(inDb);
            // attack = GR が増える方向、release = 戻る方向
            double coef = target > grSmoothDb ? atkCoef : relCoef;
            grSmoothDb = target + (grSmoothDb - target) * coef;
            double gainLin = Math.pow(10.0, (-grSmoothDb + makeup) / 20.0);
            buf[o] = (float) (buf[o] * gainLin);
            if (channels > 1) buf[o + 1] = (float) (buf[o + 1] * gainLin);
        }
        grNowDb = grSmoothDb;
    }
}
