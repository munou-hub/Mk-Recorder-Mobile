package jp.mk.recorder;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 再生エンジン。MediaExtractor + MediaCodec でデコードし AudioTrack で再生。
 * デコーダ出力の PCM 形式 (16bit / 24bit packed / 32bit int / float) すべてに対応する。
 * FFT 用に再生済みサンプルの履歴リングを持つ。波形ピークの全デコードも担う。
 */
public class Player {
    public interface Listener {
        void onEnded();
        void onError(String msg);
    }

    public interface PeaksCallback {
        void onPeaks(float[] mins, float[] maxs, double durationSec, int sampleRate, int channels);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private volatile boolean playing = false;
    private volatile boolean stopFlag = false;
    private volatile int generation = 0;
    private Thread worker;
    private File file;
    private double durationSec = 0;
    private int sampleRate = 0;
    private int channels = 0;
    private AudioTrack track;
    private volatile long startOffsetFrames = 0;

    // FFT 用履歴 (モノラル)
    private final float[] history = new float[32768];
    private long historyPos = 0;
    private final Object historyLock = new Object();

    public void setListener(Listener l) { listener = l; }

    public boolean isPlaying() { return playing; }
    public double getDurationSec() { return durationSec; }
    public int getSampleRate() { return sampleRate; }

    public double getPositionSec() {
        AudioTrack t = track;
        if (t == null || sampleRate == 0) return 0;
        try {
            long head = t.getPlaybackHeadPosition() & 0xffffffffL;
            double pos = (startOffsetFrames + head) / (double) sampleRate;
            return durationSec > 0 ? Math.min(pos, durationSec) : pos;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- PCM 変換 (デコーダ出力の全形式対応) ----------

    static int sampleBytes(int enc) {
        if (enc == AudioFormat.ENCODING_PCM_16BIT) return 2;
        if (enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 3;
        return 4; // FLOAT / 32BIT
    }

    static int framesFor(int size, int enc, int ch) {
        return size / (sampleBytes(enc) * Math.max(1, ch));
    }

    /** buf[offset, offset+size) を float (interleaved) に変換し、フレーム数を返す。 */
    static int convert(ByteBuffer buf, int offset, int size, int enc, int ch, float[] out) {
        int frames = framesFor(size, enc, ch);
        int need = frames * ch;
        ByteBuffer le = buf.order(ByteOrder.LITTLE_ENDIAN);
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            for (int i = 0; i < need; i++) out[i] = le.getFloat(offset + i * 4);
        } else if (enc == AudioFormat.ENCODING_PCM_32BIT) {
            for (int i = 0; i < need; i++) out[i] = le.getInt(offset + i * 4) / 2147483648f;
        } else if (enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            for (int i = 0; i < need; i++) {
                int off = offset + i * 3;
                int v = (le.get(off) & 0xff) | ((le.get(off + 1) & 0xff) << 8) | (le.get(off + 2) << 16);
                out[i] = v / 8388608f;
            }
        } else { // 16BIT
            for (int i = 0; i < need; i++) {
                int v = (short) ((le.get(offset + i * 2) & 0xff) | (le.get(offset + i * 2 + 1) << 8));
                out[i] = v / 32768f;
            }
        }
        return frames;
    }

    private static int outputEncoding(MediaCodec dec) {
        try {
            MediaFormat of = dec.getOutputFormat();
            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                return of.getInteger(MediaFormat.KEY_PCM_ENCODING);
            }
        } catch (Exception ignored) { }
        return AudioFormat.ENCODING_PCM_16BIT;
    }

    // ---------- transport ----------

    public void open(File f) {
        stopInternal();
        file = f;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationSec = fmt.getLong(MediaFormat.KEY_DURATION) / 1e6;
                    }
                    break;
                }
            }
        } catch (Exception e) {
            if (listener != null) listener.onError("ファイルを開けません: " + e.getMessage());
        } finally {
            ex.release();
        }
    }

    public void play() { playFrom(0); }

    public void seekTo(double sec) {
        boolean wasPlaying = playing;
        playFrom(sec);
        if (!wasPlaying) pause();
    }

    private void playFrom(double sec) {
        stopInternal();
        if (file == null) return;
        stopFlag = false;
        playing = true;
        final double startSec = sec;
        final int myGen = generation;
        worker = new Thread(() -> runPipeline(startSec, myGen), "mrm-player");
        worker.start();
    }

    public void pause() {
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) { }
        }
        playing = false;
    }

    public void resume() {
        AudioTrack t = track;
        if (t != null) {
            try { t.play(); playing = true; } catch (Exception ignored) { }
        } else if (file != null) {
            playFrom(getPositionSec());
        }
    }

    public boolean isPaused() {
        return track != null && !playing;
    }

    public void stop() {
        stopInternal();
    }

    private void stopInternal() {
        // UI スレッドをブロックしない: ワーカは generation 不一致で自律終了する
        stopFlag = true;
        playing = false;
        generation++;
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); t.flush(); t.stop(); } catch (Exception ignored) { }
            t.release();
        }
        worker = null;
    }

    private void runPipeline(double startSec, int myGen) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        AudioTrack myTrack = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int trackIdx = -1;
            MediaFormat fmt = null;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    trackIdx = i; fmt = f; mime = m;
                    break;
                }
            }
            if (trackIdx < 0) throw new Exception("音声トラックがありません");
            ex.selectTrack(trackIdx);
            if (startSec > 0) {
                ex.seekTo((long) (startSec * 1e6), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                durationSec = fmt.getLong(MediaFormat.KEY_DURATION) / 1e6;
            }
            startOffsetFrames = (long) (startSec * sampleRate);

            int chMask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_FLOAT);
            myTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 2, sampleRate * channels * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (myGen != generation) {
                myTrack.release();
                return;
            }
            track = myTrack;
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();
            myTrack.play();
            if (!playing) myTrack.pause();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            float[] chunk = new float[8192 * 2];
            while (!stopFlag && myGen == generation) {
                if (!playing) {
                    try { Thread.sleep(40); } catch (InterruptedException ignored) { }
                    continue;
                }
                if (!inputEos) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer in = dec.getInputBuffer(inIdx);
                        in.clear();
                        int size = ex.readSampleData(in, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer out = dec.getOutputBuffer(outIdx);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0) {
                        int enc = outputEncoding(dec);
                        int frames = framesFor(info.size, enc, channels);
                        int need = frames * channels;
                        if (chunk.length < need) chunk = new float[need];
                        convert(out, info.offset, info.size, enc, channels, chunk);
                        myTrack.write(chunk, 0, need, AudioTrack.WRITE_BLOCKING);
                        pushHistory(chunk, frames);
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if (eos) break;
                }
            }
            boolean ended = !stopFlag && myGen == generation;
            if (ended) {
                playing = false;
                mainHandler.post(() -> { if (listener != null) listener.onEnded(); });
            }
        } catch (Exception e) {
            if (!stopFlag && myGen == generation) {
                playing = false;
                mainHandler.post(() -> { if (listener != null) listener.onError(e.getMessage()); });
            }
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { }
                dec.release();
            }
            ex.release();
        }
    }

    private void pushHistory(float[] buf, int frames) {
        synchronized (historyLock) {
            int ch = Math.max(1, channels);
            for (int i = 0; i < frames; i++) {
                float m = ch >= 2 ? (buf[i * ch] + buf[i * ch + 1]) / 2f : buf[i * ch];
                history[(int) (historyPos % history.length)] = m;
                historyPos++;
            }
        }
    }

    /** 直近の再生サンプル (モノラル) を末尾から n 個、古い順に out へコピーする。 */
    public void copyRecent(float[] out, int n) {
        synchronized (historyLock) {
            for (int i = 0; i < n; i++) {
                long pos = historyPos - n + i;
                out[i] = pos < 0 ? 0 : history[(int) (pos % history.length)];
            }
        }
    }

    // ---------- waveform peaks (全デコード) ----------

    public static void decodePeaks(File file, int buckets, PeaksCallback cb) {
        new Thread(() -> {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec dec = null;
            try {
                ex.setDataSource(file.getAbsolutePath());
                int trackIdx = -1;
                MediaFormat fmt = null;
                String mime = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { trackIdx = i; fmt = f; mime = m; break; }
                }
                if (trackIdx < 0) throw new Exception("no audio");
                ex.selectTrack(trackIdx);
                int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int ch = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                long durUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                        ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
                double durSec = durUs / 1e6;
                long totalFrames = (long) (durSec * rate);
                float[] mins = new float[buckets];
                float[] maxs = new float[buckets];
                for (int i = 0; i < buckets; i++) { mins[i] = 1; maxs[i] = -1; }
                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(fmt, null, null, 0);
                dec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputEos = false;
                long framePos = 0;
                float[] scratch = new float[8192 * 2];
                while (true) {
                    if (!inputEos) {
                        int inIdx = dec.dequeueInputBuffer(10000);
                        if (inIdx >= 0) {
                            ByteBuffer in = dec.getInputBuffer(inIdx);
                            in.clear();
                            int size = ex.readSampleData(in, 0);
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int outIdx = dec.dequeueOutputBuffer(info, 10000);
                    if (outIdx >= 0) {
                        ByteBuffer out = dec.getOutputBuffer(outIdx);
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0) {
                            int enc = outputEncoding(dec);
                            int frames = framesFor(info.size, enc, ch);
                            int need = frames * ch;
                            if (scratch.length < need) scratch = new float[need];
                            convert(out, info.offset, info.size, enc, ch, scratch);
                            for (int i = 0; i < frames; i++) {
                                float peak = 0;
                                for (int c = 0; c < ch; c++) {
                                    float f = Math.abs(scratch[i * ch + c]);
                                    if (f > peak) peak = f;
                                }
                                int b = totalFrames > 0
                                        ? (int) (framePos * buckets / totalFrames) : 0;
                                if (b >= buckets) b = buckets - 1;
                                if (-peak < mins[b]) mins[b] = -peak;
                                if (peak > maxs[b]) maxs[b] = peak;
                                framePos++;
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false);
                        if (eos) break;
                    }
                }
                for (int i = 0; i < buckets; i++) {
                    if (mins[i] > maxs[i]) { mins[i] = 0; maxs[i] = 0; }
                }
                final float[] fMins = mins, fMaxs = maxs;
                new Handler(Looper.getMainLooper()).post(
                        () -> cb.onPeaks(fMins, fMaxs, durSec, rate, ch));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(
                        () -> cb.onPeaks(new float[buckets], new float[buckets], 0, 0, 0));
            } finally {
                if (dec != null) {
                    try { dec.stop(); } catch (Exception ignored) { }
                    dec.release();
                }
                ex.release();
            }
        }, "mrm-peaks").start();
    }
}
