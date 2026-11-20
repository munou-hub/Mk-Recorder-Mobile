package jp.mk.recorder;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.ByteBuffer;

/** FX を適用した WAV (24bit) を新規ファイルへ書き出す (オフライン・レンダリング)。 */
public class FxRender {
    public interface Callback {
        void onDone(File out, String error);
    }

    public static void render(File src, Callback cb) {
        new Thread(() -> {
            File out = null;
            String err = null;
            MediaExtractor ex = new MediaExtractor();
            MediaCodec dec = null;
            WavWriter writer = null;
            try {
                String name = src.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                out = new File(src.getParentFile(), base + "_FX.wav");
                int n = 2;
                while (out.exists()) {
                    out = new File(src.getParentFile(), base + "_FX" + (n++) + ".wav");
                }

                ex.setDataSource(src.getAbsolutePath());
                int trackIdx = -1;
                MediaFormat fmt = null;
                String mime = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { trackIdx = i; fmt = f; mime = m; break; }
                }
                if (trackIdx < 0) throw new Exception("音声トラックがありません");
                ex.selectTrack(trackIdx);
                int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int ch = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));

                writer = new WavWriter(out, rate, ch, WavWriter.MODE_INT24);
                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(fmt, null, null, 0);
                dec.start();

                FxChain chain = new FxChain();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputEos = false;
                float[] chunk = new float[8192 * 2];
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
                        ByteBuffer ob = dec.getOutputBuffer(outIdx);
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0) {
                            int enc = Player.outputEncoding(dec);
                            int frames = Player.framesFor(info.size, enc, ch);
                            int need = frames * ch;
                            if (chunk.length < need) chunk = new float[need];
                            Player.convert(ob, info.offset, info.size, enc, ch, chunk);
                            chain.process(chunk, frames, ch, rate);
                            writer.write(chunk, frames);
                        }
                        dec.releaseOutputBuffer(outIdx, false);
                        if (eos) break;
                    }
                }
                writer.close();
                writer = null;
            } catch (Exception e) {
                err = e.getMessage();
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) { }
                    writer = null;
                }
                if (out != null) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    out = null;
                }
            } finally {
                if (dec != null) {
                    try { dec.stop(); } catch (Exception ignored) { }
                    dec.release();
                }
                ex.release();
            }
            final File fOut = out;
            final String fErr = err;
            new Handler(Looper.getMainLooper()).post(() -> cb.onDone(fOut, fErr));
        }, "mrm-fxrender").start();
    }
}
