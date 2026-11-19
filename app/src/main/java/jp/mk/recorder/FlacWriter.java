package jp.mk.recorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * MediaCodec の FLAC エンコーダで書き出す SampleSink。
 * Android の FLAC エンコーダは PCM 16bit 入力のため、FLAC 録音は 16bit 固定。
 * エンコーダが無い端末では isAvailable() が false を返し、呼び出し側が WAV に落とす。
 */
public class FlacWriter implements SampleSink {
    private static final String MIME = "audio/flac";

    public static boolean isAvailable() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (MIME.equalsIgnoreCase(t)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private final MediaCodec codec;
    private final FileOutputStream out;
    private final int channels;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean eos = false;
    private volatile IOException error;

    public FlacWriter(File file, int sampleRate, int channels) throws IOException {
        this.channels = channels;
        MediaFormat fmt = MediaFormat.createAudioFormat(MIME, sampleRate, channels);
        fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
        fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, sampleRate * channels * 16);
        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        out = new FileOutputStream(file);
        thread = new Thread(this::runCodec, "flac-encoder");
        thread.start();
    }

    @Override
    public void write(float[] buf, int frames) throws IOException {
        if (error != null) throw error;
        int total = frames * channels;
        byte[] pcm = new byte[total * 2];
        for (int i = 0; i < total; i++) {
            float f = buf[i];
            if (f > 1f) f = 1f;
            if (f < -1f) f = -1f;
            int v = Math.round(f * 32767f);
            pcm[i * 2] = (byte) (v & 0xff);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        queue.offer(pcm);
    }

    private void runCodec() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        try {
            while (true) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        in.clear();
                        byte[] chunk = eos ? null : queue.poll();
                        if (chunk == null && eos) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else if (chunk != null) {
                            // 入力バッファより大きい塊は分割して入れる
                            int off = 0;
                            while (off < chunk.length) {
                                if (off > 0) {
                                    inIdx = codec.dequeueInputBuffer(10000);
                                    if (inIdx < 0) break;
                                    in = codec.getInputBuffer(inIdx);
                                    in.clear();
                                }
                                int n = Math.min(in.remaining(), chunk.length - off);
                                in.put(chunk, off, n);
                                codec.queueInputBuffer(inIdx, 0, n, 0, 0);
                                off += n;
                            }
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, 0);
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (info.size > 0) {
                        byte[] data = new byte[info.size];
                        outBuf.position(info.offset);
                        outBuf.get(data, 0, info.size);
                        out.write(data);
                    }
                    boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIdx, false);
                    if (isEos) break;
                }
            }
        } catch (Exception e) {
            error = e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        eos = true;
        try {
            thread.join(15000);
        } catch (InterruptedException ignored) {
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
        out.close();
        if (error != null) throw error;
    }
}
