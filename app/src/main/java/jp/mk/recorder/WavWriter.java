package jp.mk.recorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** ストリーミング WAV ライタ。16bit / 24bit 整数、32bit float 対応。 */
public class WavWriter implements SampleSink {
    public static final int MODE_INT16 = 16;
    public static final int MODE_INT24 = 24;
    public static final int MODE_FLOAT32 = 32;

    private final RandomAccessFile raf;
    private final int channels;
    private final int sampleRate;
    private final int mode;
    private final int bytesPerSample;
    private long dataBytes = 0;
    private final byte[] conv;

    public WavWriter(File file, int sampleRate, int channels, int mode) throws IOException {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.mode = mode;
        this.bytesPerSample = mode == MODE_INT16 ? 2 : mode == MODE_INT24 ? 3 : 4;
        this.raf = new RandomAccessFile(file, "rw");
        this.conv = new byte[8192 * channels * bytesPerSample];
        writeHeader(0);
    }

    private void writeHeader(long dataLen) throws IOException {
        raf.seek(0);
        int fmtTag = mode == MODE_FLOAT32 ? 3 : 1;
        int blockAlign = channels * bytesPerSample;
        int byteRate = sampleRate * blockAlign;
        raf.writeBytes("RIFF");
        writeU32(36 + dataLen);
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeU32(16);
        writeU16(fmtTag);
        writeU16(channels);
        writeU32(sampleRate);
        writeU32(byteRate);
        writeU16(blockAlign);
        writeU16(mode == MODE_FLOAT32 ? 32 : mode);
        raf.writeBytes("data");
        writeU32(dataLen);
    }

    private void writeU32(long v) throws IOException {
        raf.write((int) (v & 0xff));
        raf.write((int) ((v >> 8) & 0xff));
        raf.write((int) ((v >> 16) & 0xff));
        raf.write((int) ((v >> 24) & 0xff));
    }

    private void writeU16(int v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
    }

    @Override
    public synchronized void write(float[] buf, int frames) throws IOException {
        int total = frames * channels;
        int pos = 0;
        while (pos < total) {
            int chunk = Math.min(total - pos, conv.length / bytesPerSample);
            int n = 0;
            for (int i = 0; i < chunk; i++) {
                float f = buf[pos + i];
                if (f > 1f) f = 1f;
                if (f < -1f) f = -1f;
                if (mode == MODE_INT16) {
                    int v = Math.round(f * 32767f);
                    conv[n++] = (byte) (v & 0xff);
                    conv[n++] = (byte) ((v >> 8) & 0xff);
                } else if (mode == MODE_INT24) {
                    int v = Math.round(f * 8388607f);
                    conv[n++] = (byte) (v & 0xff);
                    conv[n++] = (byte) ((v >> 8) & 0xff);
                    conv[n++] = (byte) ((v >> 16) & 0xff);
                } else {
                    int v = Float.floatToIntBits(f);
                    conv[n++] = (byte) (v & 0xff);
                    conv[n++] = (byte) ((v >> 8) & 0xff);
                    conv[n++] = (byte) ((v >> 16) & 0xff);
                    conv[n++] = (byte) ((v >> 24) & 0xff);
                }
            }
            raf.write(conv, 0, n);
            dataBytes += n;
            pos += chunk;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writeHeader(dataBytes);
        raf.close();
    }
}
