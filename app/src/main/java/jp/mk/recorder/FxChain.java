package jp.mk.recorder;

/** 再生/書き出し用の FX チェーン (EQ → コンプ)。処理スレッド専用。 */
public class FxChain {
    private final ParametricEq eq = new ParametricEq();
    private final Compressor comp = new Compressor();

    public void reset() {
        eq.reset();
        comp.reset();
    }

    public double grDb() {
        return Fx.compOn ? comp.grDb() : 0;
    }

    /** どちらも OFF なら何もしない。interleaved float バッファをその場で処理する。 */
    public void process(float[] buf, int frames, int channels, int sampleRate) {
        if (frames <= 0) return;
        if (Fx.eqOn) eq.process(buf, frames, channels, sampleRate);
        if (Fx.compOn) {
            comp.process(buf, frames, channels, sampleRate);
            Fx.grDbNow = comp.grDb();
        } else {
            Fx.grDbNow = 0;
        }
    }

    public static boolean anyOn() {
        return Fx.eqOn || Fx.compOn;
    }
}
