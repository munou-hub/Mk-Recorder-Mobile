package jp.mk.recorder;

/** 4 バンド パラメトリック EQ (LS / Peak / Peak / HS)。Fx のパラメータを参照する。 */
public class ParametricEq {
    private final Biquad[] bands = new Biquad[Fx.BANDS];
    private int syncedVersion = -1;
    private int syncedRate = -1;

    public ParametricEq() {
        for (int i = 0; i < Fx.BANDS; i++) bands[i] = new Biquad();
    }

    public void reset() {
        for (Biquad b : bands) b.reset();
    }

    private void sync(int sampleRate) {
        if (syncedVersion == Fx.version && syncedRate == sampleRate) return;
        syncedVersion = Fx.version;
        syncedRate = sampleRate;
        for (int i = 0; i < Fx.BANDS; i++) {
            int type = Fx.BAND_TYPE[i] == Fx.TYPE_LS ? Biquad.LOW_SHELF
                    : Fx.BAND_TYPE[i] == Fx.TYPE_HS ? Biquad.HIGH_SHELF : Biquad.PEAKING;
            if (Fx.bandOn[i]) {
                bands[i].set(type, Fx.bandFreq[i], Fx.bandGain10[i] / 10.0,
                        Fx.bandQ100[i] / 100.0, sampleRate);
            } else {
                bands[i].set(type, Fx.bandFreq[i], 0, 1.0, sampleRate); // identity
            }
        }
    }

    /** interleaved float バッファをその場で処理する。 */
    public void process(float[] buf, int frames, int channels, int sampleRate) {
        sync(sampleRate);
        for (int i = 0; i < frames; i++) {
            int o = i * channels;
            for (int ch = 0; ch < channels; ch++) {
                double x = buf[o + ch];
                for (int b = 0; b < Fx.BANDS; b++) x = bands[b].process(ch, x);
                buf[o + ch] = (float) x;
            }
        }
    }
}
