package jp.mk.recorder;

import android.content.Context;

/**
 * エフェクト (EQ → コンプ) のパラメータ保持と永続化。
 * 値はすべて int スケールで保存する (gain は 0.1dB 単位、Q は 100 倍など)。
 * パラメータ変更時は version を進め、DSP 側が process 内で検知して係数を再計算する。
 */
public class Fx {
    public static final int BANDS = 4;
    public static final int TYPE_LS = 0, TYPE_PK = 1, TYPE_HS = 2;
    public static final int[] BAND_TYPE = {TYPE_LS, TYPE_PK, TYPE_PK, TYPE_HS};
    public static final int[] FREQ_MIN = {40, 200, 500, 2000};
    public static final int[] FREQ_MAX = {500, 4000, 12000, 16000};
    public static final int[] FREQ_DEF = {100, 800, 3000, 8000};

    public static volatile boolean eqOn = false;
    public static volatile boolean compOn = false;
    public static final boolean[] bandOn = {true, true, true, true};
    public static final int[] bandFreq = new int[BANDS];   // Hz
    public static final int[] bandGain10 = new int[BANDS]; // 0.1 dB
    public static final int[] bandQ100 = new int[BANDS];   // x100
    static {
        for (int i = 0; i < BANDS; i++) {
            bandFreq[i] = FREQ_DEF[i];
            bandGain10[i] = 0;
            bandQ100[i] = i == 1 || i == 2 ? 100 : 90;
        }
    }

    public static volatile int compThr10 = -180;    // 0.1 dB, -60.0..0
    public static volatile int compRatio10 = 30;    // x10, 10..200 (200 = ∞扱い)
    public static volatile int compAttack10 = 100;  // 0.1 ms, 1..3000
    public static volatile int compReleaseMs = 150; // ms, 10..1000
    public static volatile int compKnee10 = 120;    // 0.1 dB, 0..240
    public static volatile int compMakeup10 = 0;    // 0.1 dB, 0..240

    public static volatile int version = 1;

    /** 処理中チェーンが公開する現在のゲインリダクション (dB、エディタ表示用)。 */
    public static volatile double grDbNow = 0;

    public static void touch() {
        version++;
    }

    public static void load(Context ctx) {
        eqOn = Prefs.getInt(ctx, "fx_eq_on", 0) == 1;
        compOn = Prefs.getInt(ctx, "fx_comp_on", 0) == 1;
        for (int i = 0; i < BANDS; i++) {
            bandOn[i] = Prefs.getInt(ctx, "fx_b" + i + "_on", 1) == 1;
            bandFreq[i] = Prefs.getInt(ctx, "fx_b" + i + "_f", FREQ_DEF[i]);
            bandGain10[i] = Prefs.getInt(ctx, "fx_b" + i + "_g", 0);
            bandQ100[i] = Prefs.getInt(ctx, "fx_b" + i + "_q", i == 1 || i == 2 ? 100 : 90);
        }
        compThr10 = Prefs.getInt(ctx, "fx_c_thr", -180);
        compRatio10 = Prefs.getInt(ctx, "fx_c_ratio", 30);
        compAttack10 = Prefs.getInt(ctx, "fx_c_atk", 100);
        compReleaseMs = Prefs.getInt(ctx, "fx_c_rel", 150);
        compKnee10 = Prefs.getInt(ctx, "fx_c_knee", 120);
        compMakeup10 = Prefs.getInt(ctx, "fx_c_mk", 0);
        touch();
    }

    public static void save(Context ctx) {
        Prefs.putInt(ctx, "fx_eq_on", eqOn ? 1 : 0);
        Prefs.putInt(ctx, "fx_comp_on", compOn ? 1 : 0);
        for (int i = 0; i < BANDS; i++) {
            Prefs.putInt(ctx, "fx_b" + i + "_on", bandOn[i] ? 1 : 0);
            Prefs.putInt(ctx, "fx_b" + i + "_f", bandFreq[i]);
            Prefs.putInt(ctx, "fx_b" + i + "_g", bandGain10[i]);
            Prefs.putInt(ctx, "fx_b" + i + "_q", bandQ100[i]);
        }
        Prefs.putInt(ctx, "fx_c_thr", compThr10);
        Prefs.putInt(ctx, "fx_c_ratio", compRatio10);
        Prefs.putInt(ctx, "fx_c_atk", compAttack10);
        Prefs.putInt(ctx, "fx_c_rel", compReleaseMs);
        Prefs.putInt(ctx, "fx_c_knee", compKnee10);
        Prefs.putInt(ctx, "fx_c_mk", compMakeup10);
    }
}
