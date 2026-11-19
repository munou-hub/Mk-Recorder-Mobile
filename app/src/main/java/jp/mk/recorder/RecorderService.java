package jp.mk.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 録音エンジン。フォアグラウンドサービスとして常駐し、
 * 入力監視 (メーター) と録音 (プリロール/セーフティ/マーカー) を担う。
 * UI とは static フィールド経由で状態をやり取りする。
 */
public class RecorderService extends Service {

    public static class Config {
        public int source = android.media.MediaRecorder.AudioSource.UNPROCESSED;
        public int sampleRate = 48000;
        public int depthMode = WavWriter.MODE_INT24;
        public boolean stereo = true;
        public boolean flac = false;
        public double gainDb = 0.0;
        public int prerollSec = 5;
        public boolean safety = true;
    }

    public static final Config CONFIG = new Config();

    public static volatile boolean engineOn = false;
    public static volatile boolean recording = false;
    public static volatile boolean saving = false;
    public static volatile Meters meters = null;
    public static volatile int effRate = 0;
    public static volatile String effSourceLabel = "";
    public static volatile int effOutCh = 0;
    public static volatile int effDepthMode = 0;
    public static volatile boolean effFlac = false;
    public static volatile String effNote = "";
    public static volatile long recFrames = 0;
    public static volatile File currentFile = null;
    public static volatile File lastSavedFile = null;
    public static volatile String lastError = null;
    /** 診断用: 処理したチャンク数と直近の read() 戻り値。ステータス欄に表示する。 */
    public static volatile long diagChunks = 0;
    public static volatile int diagLastRead = 0;
    /** LUFS 表示中だけ K-weighting を回すためのフラグ (軽量化)。 */
    public static volatile boolean lufsWanted = true;
    public static final List<Double> curMarkers =
            Collections.synchronizedList(new ArrayList<>());

    private static RecorderService instance;

    private Thread engineThread;
    private volatile boolean runFlag = false;
    private PowerManager.WakeLock wakeLock;
    private final Object sinkLock = new Object();
    private SampleSink sink;
    private SampleSink sinkSafe;
    private File recFile;
    private File recSafeFile;

    // ---------- static control ----------

    public static void ensureEngine(Context ctx) {
        if (instance == null) {
            Intent i = new Intent(ctx, RecorderService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        }
    }

    public static void stopEngineIfIdle(Context ctx) {
        if (instance != null && !recording) {
            ctx.stopService(new Intent(ctx, RecorderService.class));
        }
    }

    /** レート/深度/形式/ソースなど構造系の設定変更を反映する (録音中は無視)。 */
    public static void applyConfigChange() {
        if (instance != null && !recording) instance.restartEngine();
    }

    public static void startRecording() {
        if (instance != null) instance.beginRecording();
    }

    public static void stopRecording() {
        if (instance != null) instance.endRecording();
    }

    public static void addMarker() {
        if (recording && effRate > 0) {
            curMarkers.add(recFrames / (double) effRate);
        }
    }

    public static long bytesPerSec() {
        if (effRate == 0 || effOutCh == 0) return 0;
        if (effFlac) return (long) effRate * effOutCh * 1; // FLAC は概算 (約半分以下)
        int bps = effDepthMode == WavWriter.MODE_INT16 ? 2
                : effDepthMode == WavWriter.MODE_INT24 ? 3 : 4;
        return (long) effRate * effOutCh * bps;
    }

    // ---------- lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, buildNotification("入力監視中"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, buildNotification("入力監視中"));
        }
        startEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        runFlag = false;
        engineOn = false;
        if (engineThread != null) {
            try { engineThread.join(2000); } catch (InterruptedException ignored) { }
        }
        releaseWakeLock();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel("mrm_rec") == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        "mrm_rec", "録音状態", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "mrm_rec")
                .setContentTitle("MRM")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, buildNotification(text));
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mrm:rec");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    // ---------- engine ----------

    private synchronized void startEngine() {
        if (engineThread != null && engineThread.isAlive()) return;
        runFlag = true;
        engineThread = new Thread(this::engineLoop, "mrm-engine");
        engineThread.start();
    }

    private void restartEngine() {
        runFlag = false;
        if (engineThread != null) {
            try { engineThread.join(2000); } catch (InterruptedException ignored) { }
        }
        startEngine();
    }

    private AudioRecord tryBuild(int source, int rate, int enc, int chCfg, int[] outMinBuf) {
        try {
            int minBuf = AudioRecord.getMinBufferSize(rate, chCfg, enc);
            if (minBuf <= 0) return null;
            outMinBuf[0] = minBuf;
            AudioRecord rec = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(enc)
                            .setSampleRate(rate)
                            .setChannelMask(chCfg)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 2, rate / 5 * bytesPerSample(enc)
                            * (chCfg == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1)))
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            return rec;
        } catch (Exception e) {
            return null;
        }
    }

    private static int bytesPerSample(int enc) {
        return enc == AudioFormat.ENCODING_PCM_16BIT ? 2 : 4;
    }

    private static String sourceLabel(int src) {
        if (src == android.media.MediaRecorder.AudioSource.UNPROCESSED) return "UNPROCESSED";
        if (src == android.media.MediaRecorder.AudioSource.MIC) return "MIC";
        if (src == android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_REC";
        if (src == android.media.MediaRecorder.AudioSource.CAMCORDER) return "CAMCORDER";
        return "SRC" + src;
    }

    private void engineLoop() {
        // フォールバック順に AudioRecord を構築する
        int[] rateCandidates = CONFIG.sampleRate == 48000
                ? new int[]{48000, 44100}
                : CONFIG.sampleRate == 44100
                ? new int[]{44100, 48000}
                : new int[]{CONFIG.sampleRate, 48000, 44100};
        int[] encCandidates;
        if (CONFIG.depthMode == WavWriter.MODE_INT16) {
            encCandidates = new int[]{AudioFormat.ENCODING_PCM_16BIT};
        } else if (CONFIG.depthMode == WavWriter.MODE_INT24) {
            encCandidates = new int[]{AudioFormat.ENCODING_PCM_32BIT,
                    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT};
        } else {
            encCandidates = new int[]{AudioFormat.ENCODING_PCM_FLOAT,
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_16BIT};
        }
        AudioRecord rec = null;
        int useRate = 0, useEnc = 0, useRecCh = 0, useSource = 0;
        StringBuilder note = new StringBuilder();
        int[] minBufHolder = new int[1];
        // 試行順リスト (source, rate, ch, enc)。
        // 「構築できる」だけでは音が流れない設定がある (read が -3 を返す等) ため、
        // 採用条件はプローブで実際に音が来ること。前回成功した組み合わせを先頭にする。
        java.util.List<int[]> attempts = new java.util.ArrayList<>();
        int lastSrc = Prefs.getInt(this, "last_src", -1);
        int lastRate = Prefs.getInt(this, "last_rate", -1);
        int lastEnc = Prefs.getInt(this, "last_enc", -1);
        int lastCh = Prefs.getInt(this, "last_ch", -1);
        if (lastSrc == CONFIG.source && lastRate == CONFIG.sampleRate && lastEnc > 0 && lastCh > 0) {
            attempts.add(new int[]{lastSrc, lastRate, lastCh, lastEnc});
        }
        java.util.LinkedHashSet<Integer> srcSet = new java.util.LinkedHashSet<>();
        srcSet.add(CONFIG.source);
        srcSet.add(android.media.MediaRecorder.AudioSource.MIC);
        srcSet.add(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION);
        for (int src : srcSet) {
            for (int rate : rateCandidates) {
                for (int ch = 2; ch >= 1; ch--) {
                    for (int enc : encCandidates) {
                        boolean dup = false;
                        for (int[] a : attempts) {
                            if (a[0] == src && a[1] == rate && a[2] == ch && a[3] == enc) { dup = true; break; }
                        }
                        if (!dup) attempts.add(new int[]{src, rate, ch, enc});
                    }
                }
            }
        }
        byte[] probeRaw = null;
        int probeFilled = 0;
        for (int[] a : attempts) {
            int chCfg = a[2] == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            AudioRecord cand = tryBuild(a[0], a[1], a[3], chCfg, minBufHolder);
            if (cand == null) continue;
            boolean ok = false;
            byte[] buf = null;
            int filled = 0;
            try {
                cand.startRecording();
                if (cand.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    int want = (a[1] / 10) * a[2] * bytesPerSample(a[3]);
                    buf = new byte[want];
                    int errs = 0;
                    long deadline = System.currentTimeMillis() + 600;
                    while (System.currentTimeMillis() < deadline && filled < want) {
                        int n = cand.read(buf, filled, want - filled,
                                AudioRecord.READ_NON_BLOCKING);
                        diagLastRead = n;
                        if (n > 0) filled += n;
                        else if (n == 0) Thread.sleep(5);
                        else {
                            if (++errs > 20) break;
                            Thread.sleep(5);
                        }
                    }
                    ok = filled > 0;
                }
            } catch (Exception ignored) {
            }
            if (ok) {
                rec = cand;
                useSource = a[0]; useRate = a[1]; useRecCh = a[2]; useEnc = a[3];
                probeRaw = buf;
                probeFilled = filled;
                break;
            }
            try { cand.stop(); } catch (Exception ignored) { }
            cand.release();
        }
        if (rec == null) {
            lastError = "入力デバイスを開けませんでした（全設定で音声が来ません）";
            engineOn = false;
            return;
        }
        Prefs.putInt(this, "last_src", useSource);
        Prefs.putInt(this, "last_rate", useRate);
        Prefs.putInt(this, "last_enc", useEnc);
        Prefs.putInt(this, "last_ch", useRecCh);
        if (useSource != CONFIG.source) {
            note.append("入力ソースを ").append(sourceLabel(useSource)).append(" にフォールバック。");
        }
        effSourceLabel = sourceLabel(useSource);
        if (useRate != CONFIG.sampleRate) note.append("レート ").append(useRate).append("Hz にフォールバック。");
        effRate = useRate;
        int outCh = CONFIG.stereo ? useRecCh : 1;
        effOutCh = outCh;
        if (!CONFIG.stereo && useRecCh == 2) note.append("ステレオ入力をモノラル化。");
        if (CONFIG.stereo && useRecCh == 1) note.append("入力はモノラルのみ対応。");
        // 実効ビット深度
        if (CONFIG.depthMode == WavWriter.MODE_INT16 || useEnc == AudioFormat.ENCODING_PCM_16BIT) {
            effDepthMode = WavWriter.MODE_INT16;
            if (CONFIG.depthMode != WavWriter.MODE_INT16) note.append("入力16bitにフォールバック。");
        } else if (CONFIG.depthMode == WavWriter.MODE_INT24) {
            effDepthMode = WavWriter.MODE_INT24;
        } else {
            effDepthMode = WavWriter.MODE_FLOAT32;
        }
        // 実効形式
        effFlac = CONFIG.flac;
        if (CONFIG.flac && !FlacWriter.isAvailable()) {
            effFlac = false;
            note.append("FLACエンコーダが無いため WAV で録音。");
        }
        if (effFlac && effDepthMode != WavWriter.MODE_INT16) {
            note.append("FLAC は 16bit 固定。");
            effDepthMode = WavWriter.MODE_INT16;
        }
        effNote = note.toString();
        meters = new Meters(effRate, outCh);
        diagChunks = 0;
        diagLastRead = 0;
        engineOn = true;
        lastError = null;

        int bps = bytesPerSample(useEnc);
        int chunkFrames = Math.max(256, effRate / 10);
        byte[] raw = probeRaw != null ? probeRaw
                : new byte[chunkFrames * useRecCh * bps];
        float[] framesBuf = new float[chunkFrames * outCh];
        float[] safeBuf = new float[chunkFrames * outCh];
        final double gain = Math.pow(10.0, CONFIG.gainDb / 20.0);
        // プリロール用リングバッファ
        int preCap = Math.max(0, CONFIG.prerollSec) * effRate;
        float[] ring = preCap > 0 ? new float[preCap * outCh] : null;
        long ringPos = 0;
        ringBuf = ring;
        ringCapFrames = preCap;
        ringCh = outCh;
        this.ringPos = 0;
        // 録音開始と疎通確認はプローブで済んでいる。ここからは本ループ。
        try {
            // ノンブロッキング読みで少しずつ溜める方式にする。
            // ブロッキング read が端末側で返ってこないケースでも固まらない。
            int wantBytes = chunkFrames * useRecCh * bps;
            int filled = probeFilled;
            int stall = 0;
            int errStreak = 0;
            while (runFlag) {
                // ゲインは録音中に変えられるよう毎チャンク読む
                double g = Math.pow(10.0, CONFIG.gainDb / 20.0);
                int n = rec.read(raw, filled, wantBytes - filled,
                        AudioRecord.READ_NON_BLOCKING);
                diagLastRead = n;
                if (n > 0) {
                    filled += n;
                    stall = 0;
                    errStreak = 0;
                    if (filled < wantBytes) continue;
                } else if (n == 0) {
                    stall++;
                    if (filled == 0 || stall < 30) {
                        try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                        continue;
                    }
                    // 溜まった分だけ処理する
                } else {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new Exception("入力デバイスが切断されました (read=" + n + ")");
                    }
                    if (++errStreak > 100) {
                        throw new Exception("入力readエラーが続いています (read=" + n + ")");
                    }
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                    continue;
                }
                int gotFrames = filled / (useRecCh * bps);
                filled = 0;
                stall = 0;
                if (gotFrames == 0) continue;
                diagChunks++;
                for (int i = 0; i < gotFrames; i++) {
                    double l, r;
                    if (useEnc == AudioFormat.ENCODING_PCM_16BIT) {
                        int off = i * useRecCh * 2;
                        l = s16(raw, off) / 32768.0;
                        r = useRecCh == 2 ? s16(raw, off + 2) / 32768.0 : l;
                    } else if (useEnc == AudioFormat.ENCODING_PCM_32BIT) {
                        int off = i * useRecCh * 4;
                        l = s32(raw, off) / 2147483648.0;
                        r = useRecCh == 2 ? s32(raw, off + 4) / 2147483648.0 : l;
                    } else {
                        int off = i * useRecCh * 4;
                        l = f32(raw, off);
                        r = useRecCh == 2 ? f32(raw, off + 4) : l;
                    }
                    l *= g; r *= g;
                    if (outCh == 2) {
                        framesBuf[i * 2] = (float) l;
                        framesBuf[i * 2 + 1] = (float) r;
                    } else {
                        framesBuf[i] = (float) ((useRecCh == 2) ? (l + r) / 2.0 : l);
                    }
                }
                meters.setLufsEnabled(lufsWanted);
                meters.process(framesBuf, gotFrames);
                if (ring != null) {
                    for (int i = 0; i < gotFrames * outCh; i++) {
                        ring[(int) ((ringPos + i) % ring.length)] = framesBuf[i];
                    }
                    ringPos += gotFrames * outCh;
                    this.ringPos = ringPos;
                }
                synchronized (sinkLock) {
                    if (recording && sink != null) {
                        sink.write(framesBuf, gotFrames);
                        if (sinkSafe != null) {
                            for (int i = 0; i < gotFrames * outCh; i++) {
                                safeBuf[i] = framesBuf[i] * 0.25118864f; // -12dB
                            }
                            sinkSafe.write(safeBuf, gotFrames);
                        }
                        recFrames += gotFrames;
                    }
                }
            }
        } catch (Exception e) {
            lastError = "録音エラー: " + e.getMessage();
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            engineOn = false;
        }
    }

    // プリロールリングの共有状態 (beginRecording から読む)
    private volatile float[] ringBuf;
    private volatile long ringPos;
    private volatile int ringCapFrames;
    private volatile int ringCh;

    private static int s16(byte[] b, int off) {
        return (short) ((b[off] & 0xff) | ((b[off + 1] & 0xff) << 8));
    }

    private static int s32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | (b[off + 3] << 24);
    }

    private static float f32(byte[] b, int off) {
        return Float.intBitsToFloat(s32(b, off));
    }

    // ---------- recording control ----------

    private void beginRecording() {
        if (!engineOn || recording || saving) return;
        try {
            String ext = effFlac ? "flac" : "wav";
            File dir = RecStore.dir(this);
            File f = new File(dir, RecStore.newFileName(ext));
            int suffix = 2;
            while (f.exists()) {
                String base = RecStore.newFileName(ext);
                f = new File(dir, base.replace(".", "_" + (suffix++) + "."));
            }
            SampleSink main;
            if (effFlac) main = new FlacWriter(f, effRate, effOutCh);
            else main = new WavWriter(f, effRate, effOutCh, effDepthMode);
            SampleSink safe = null;
            File sf = null;
            if (CONFIG.safety) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                sf = new File(dir, name.substring(0, dot) + "_SAFE" + name.substring(dot));
                if (effFlac) safe = new FlacWriter(sf, effRate, effOutCh);
                else safe = new WavWriter(sf, effRate, effOutCh, effDepthMode);
            }
            // プリロールを先に書き出す
            long preFrames = 0;
            float[] ringSnap = ringBuf;
            if (ringSnap != null && ringCapFrames > 0 && ringCh > 0) {
                long total = Math.min(ringPos, (long) ringCapFrames * ringCh);
                int frames = (int) (total / ringCh);
                if (frames > 0) {
                    float[] pre = new float[frames * ringCh];
                    long start = ringPos - total;
                    for (long i = 0; i < total; i++) {
                        pre[(int) i] = ringSnap[(int) ((start + i) % ringSnap.length)];
                    }
                    main.write(pre, frames);
                    if (safe != null) {
                        float[] preSafe = new float[pre.length];
                        for (int i = 0; i < pre.length; i++) preSafe[i] = pre[i] * 0.25118864f;
                        safe.write(preSafe, frames);
                    }
                    preFrames = frames;
                }
            }
            synchronized (sinkLock) {
                sink = main;
                sinkSafe = safe;
                recFile = f;
                recSafeFile = sf;
                recFrames = preFrames;
                curMarkers.clear();
                currentFile = f;
                recording = true;
            }
            acquireWakeLock();
            updateNotification("録音中: " + f.getName());
        } catch (Exception e) {
            lastError = "録音開始に失敗: " + e.getMessage();
        }
    }

    private void endRecording() {
        if (!recording || saving) return;
        saving = true;
        final SampleSink main;
        final SampleSink safe;
        final File f;
        synchronized (sinkLock) {
            recording = false;
            main = sink; safe = sinkSafe; f = recFile;
            sink = null; sinkSafe = null;
        }
        new Thread(() -> {
            try {
                if (main != null) main.close();
                if (safe != null) safe.close();
                if (f != null) {
                    RecStore.saveMarkers(f, new ArrayList<>(curMarkers));
                    lastSavedFile = f;
                }
            } catch (Exception e) {
                lastError = "保存に失敗: " + e.getMessage();
            } finally {
                currentFile = null;
                saving = false;
                releaseWakeLock();
                updateNotification("入力監視中");
            }
        }, "mrm-save").start();
    }
}
