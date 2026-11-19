package jp.mk.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/** 設定の永続化。RecorderService.CONFIG と UI 状態を SharedPreferences に保存する。 */
public class Prefs {
    private static final String NAME = "mrm_prefs";

    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        RecorderService.Config c = RecorderService.CONFIG;
        c.source = p.getInt("source", MediaRecorder.AudioSource.UNPROCESSED);
        c.sampleRate = p.getInt("rate", 48000);
        c.depthMode = p.getInt("depth", WavWriter.MODE_INT24);
        c.stereo = p.getBoolean("stereo", true);
        c.flac = p.getBoolean("flac", false);
        c.gainDb = p.getFloat("gain", 0f);
        c.prerollSec = p.getInt("preroll", 5);
        c.safety = p.getBoolean("safety", true);
    }

    public static void save(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        RecorderService.Config c = RecorderService.CONFIG;
        p.edit()
                .putInt("source", c.source)
                .putInt("rate", c.sampleRate)
                .putInt("depth", c.depthMode)
                .putBoolean("stereo", c.stereo)
                .putBoolean("flac", c.flac)
                .putFloat("gain", (float) c.gainDb)
                .putInt("preroll", c.prerollSec)
                .putBoolean("safety", c.safety)
                .apply();
    }

    public static int getInt(Context ctx, String key, int def) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(key, def);
    }

    public static void putInt(Context ctx, String key, int v) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(key, v).apply();
    }
}
