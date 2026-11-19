package jp.mk.recorder;

/**
 * リアルタイムレベル計算。LUFS (Momentary, K-weighting, BS.1770 系),
 * RMS, PEAK (サンプルピーク) をチャンネルごとに返す。
 * 値はすべて dB。無音は -99。
 */
public class Meters {
    public static final double FLOOR = -99.0;

    private final int sampleRate;
    private final int channels;

    // K-weighting biquads (per channel): [shelf][rlb] x state
    private final double[][] bShelf = new double[2][3];
    private final double[][] aShelf = new double[2][3];
    private final double[][] bRlb = new double[2][3];
    private final double[][] aRlb = new double[2][3];
    private final double[][] xh = new double[2][4]; // filter history x: [ch][stage*2]
    private final double[][] yh = new double[2][4];

    // Momentary 用: 100ms サブブロックのリング (sumSq, count)
    private static final int SUB_BLOCKS = 4; // 4 x 100ms = 400ms
    private final double[][] subSum = new double[SUB_BLOCKS][2];
    private final int[] subCount = new int[SUB_BLOCKS];
    private int subIdx = 0;
    private int subFill = 0; // 現在のサブブロック内サンプル数
    private final double[] curSum = new double[2];

    private final double[] rmsSmooth = new double[2];
    private final double[] peakLin = new double[2];
    private long lastPeakTimeMs = 0;
    private volatile boolean lufsEnabled = true;

    // 公開値 (process の最後にまとめて更新。UI 側はロックなしで読む)
    private volatile double outLufs0 = FLOOR, outLufs1 = FLOOR;
    private volatile double outRms0 = FLOOR, outRms1 = FLOOR;
    private volatile double outPeak0 = FLOOR, outPeak1 = FLOOR;
    private volatile double outPeakLin0 = 0, outPeakLin1 = 0;

    /** LUFS が表示されていない間は K-weighting 計算を止めて軽くする。 */
    public void setLufsEnabled(boolean enabled) {
        if (enabled && !lufsEnabled) {
            // 再開時は古いブロックを捨てる
            for (int i = 0; i < SUB_BLOCKS; i++) {
                subSum[i][0] = 0; subSum[i][1] = 0; subCount[i] = 0;
            }
            curSum[0] = 0; curSum[1] = 0; subFill = 0;
        }
        lufsEnabled = enabled;
    }

    public Meters(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        initKWeighting(sampleRate);
        rmsSmooth[0] = rmsSmooth[1] = 0;
    }

    private void initKWeighting(int fs) {
        // Stage 1: high shelf (BS.1770 pre-filter)
        double f0 = 1681.974450955533;
        double G = 3.999843853973347;
        double Q = 0.7071752369554196;
        double K = Math.tan(Math.PI * f0 / fs);
        double Vh = Math.pow(10.0, G / 20.0);
        double Vb = Math.pow(Vh, 0.4996667741545416);
        double a0 = 1.0 + K / Q + K * K;
        double[] b = {
                (Vh + Vb * K / Q + K * K) / a0,
                2.0 * (K * K - Vh) / a0,
                (Vh - Vb * K / Q + K * K) / a0 };
        double[] a = { 1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0 };
        // Stage 2: RLB high-pass
        double f1 = 38.13547087602444;
        double Q1 = 0.5003270373238773;
        double K1 = Math.tan(Math.PI * f1 / fs);
        double a0b = 1.0 + K1 / Q1 + K1 * K1;
        double[] b2 = { 1.0 / a0b, -2.0 / a0b, 1.0 / a0b };
        double[] a2 = { 1.0, 2.0 * (K1 * K1 - 1.0) / a0b, (1.0 - K1 / Q1 + K1 * K1) / a0b };
        for (int ch = 0; ch < 2; ch++) {
            bShelf[ch] = b; aShelf[ch] = a; bRlb[ch] = b2; aRlb[ch] = a2;
        }
    }

    private double kFilter(int ch, double x) {
        // stage 1 (shelf)
        double y = bShelf[ch][0] * x + bShelf[ch][1] * xh[ch][0] + bShelf[ch][2] * xh[ch][1]
                - aShelf[ch][1] * yh[ch][0] - aShelf[ch][2] * yh[ch][1];
        xh[ch][1] = xh[ch][0]; xh[ch][0] = x;
        yh[ch][1] = yh[ch][0]; yh[ch][0] = y;
        // stage 2 (rlb)
        double y2 = bRlb[ch][0] * y + bRlb[ch][1] * xh[ch][2] + bRlb[ch][2] * xh[ch][3]
                - aRlb[ch][1] * yh[ch][2] - aRlb[ch][2] * yh[ch][3];
        xh[ch][3] = xh[ch][2]; xh[ch][2] = y;
        yh[ch][3] = yh[ch][2]; yh[ch][2] = y2;
        return y2;
    }

    /** interleaved float サンプルを 1 チャンク処理する。エンジンスレッド専用。 */
    public void process(float[] buf, int frames) {
        int subSize = Math.max(1, sampleRate / 10);
        long now = System.currentTimeMillis();
        double decaySec = lastPeakTimeMs == 0 ? 0 : (now - lastPeakTimeMs) / 1000.0;
        lastPeakTimeMs = now;
        // PEAK decay: 12 dB/s 相当
        double decayLin = Math.pow(10.0, -12.0 * decaySec / 20.0);
        peakLin[0] *= decayLin;
        peakLin[1] *= decayLin;

        double chunkSq0 = 0, chunkSq1 = 0;
        for (int i = 0; i < frames; i++) {
            float l = buf[i * channels];
            float r = channels > 1 ? buf[i * channels + 1] : l;
            double a0 = Math.abs(l), a1 = Math.abs(r);
            if (a0 > peakLin[0]) peakLin[0] = a0;
            if (a1 > peakLin[1]) peakLin[1] = a1;
            chunkSq0 += (double) l * l;
            chunkSq1 += (double) r * r;
            if (!lufsEnabled) continue;
            double kl = kFilter(0, l);
            double kr = channels > 1 ? kFilter(1, r) : kl;
            curSum[0] += kl * kl;
            curSum[1] += kr * kr;
            subFill++;
            if (subFill >= subSize) {
                subSum[subIdx][0] = curSum[0];
                subSum[subIdx][1] = curSum[1];
                subCount[subIdx] = subFill;
                curSum[0] = 0; curSum[1] = 0;
                subFill = 0;
                subIdx = (subIdx + 1) % SUB_BLOCKS;
            }
        }
        // RMS: チャンク RMS を attack 即時 / release 滑らかで追従
        if (frames > 0) {
            double r0 = Math.sqrt(chunkSq0 / frames);
            double r1 = Math.sqrt(chunkSq1 / frames);
            rmsSmooth[0] = Math.max(r0, rmsSmooth[0] * 0.82);
            rmsSmooth[1] = Math.max(r1, rmsSmooth[1] * 0.82);
        }
        // 公開値をまとめて更新
        outRms0 = toDb(rmsSmooth[0]);
        outRms1 = toDb(rmsSmooth[1]);
        outPeak0 = toDb(peakLin[0]);
        outPeak1 = toDb(peakLin[1]);
        outPeakLin0 = peakLin[0];
        outPeakLin1 = peakLin[1];
        if (lufsEnabled) {
            outLufs0 = computeLufs(0);
            outLufs1 = computeLufs(1);
        }
    }

    private static double toDb(double lin) {
        if (lin <= 1e-9) return FLOOR;
        double db = 20.0 * Math.log10(lin);
        return db < FLOOR ? FLOOR : db;
    }

    private double computeLufs(int ch) {
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < SUB_BLOCKS; i++) {
            sum += subSum[i][ch];
            cnt += subCount[i];
        }
        sum += curSum[ch];
        cnt += subFill;
        if (cnt == 0) return FLOOR;
        double ms = sum / cnt;
        if (ms <= 1e-12) return FLOOR;
        double db = -0.691 + 10.0 * Math.log10(ms);
        return db < FLOOR ? FLOOR : db;
    }

    public double lufsDb(int ch) {
        return ch == 0 ? outLufs0 : outLufs1;
    }

    public double rmsDb(int ch) {
        return ch == 0 ? outRms0 : outRms1;
    }

    public double peakDb(int ch) {
        return ch == 0 ? outPeak0 : outPeak1;
    }

    public double peakLin(int ch) {
        return ch == 0 ? outPeakLin0 : outPeakLin1;
    }
}
