package jp.mk.recorder;

import java.io.IOException;

/** 録音ファイルへの書き出し先 (WAV / FLAC 共通インターフェース)。 */
public interface SampleSink {
    /** interleaved float サンプル (-1..1) を frames 分書き込む。 */
    void write(float[] buf, int frames) throws IOException;

    void close() throws IOException;
}
