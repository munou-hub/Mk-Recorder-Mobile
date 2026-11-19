package jp.mk.recorder;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 録音ファイルの保管・一覧・メタ情報・マーカー・リネーム。 */
public class RecStore {

    public static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "MKRecorder");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static boolean isAudio(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".wav") || n.endsWith(".flac");
    }

    public static List<File> list(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        List<File> out = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && isAudio(f)) out.add(f);
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static String newFileName(String ext) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "REC_" + ts + "." + ext;
    }

    public static File sidecar(File audio) {
        return new File(audio.getParentFile(), audio.getName() + ".mrm.json");
    }

    // ---------- markers ----------

    public static void saveMarkers(File audio, List<Double> markersSec) {
        try {
            JSONArray arr = new JSONArray();
            for (Double d : markersSec) arr.put(d);
            JSONObject o = new JSONObject();
            o.put("markers", arr);
            FileOutputStream fos = new FileOutputStream(sidecar(audio));
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    public static List<Double> loadMarkers(File audio) {
        List<Double> out = new ArrayList<>();
        try {
            File sc = sidecar(audio);
            if (!sc.exists()) return out;
            byte[] buf = new byte[(int) sc.length()];
            FileInputStream fis = new FileInputStream(sc);
            int n = fis.read(buf);
            fis.close();
            if (n <= 0) return out;
            JSONObject o = new JSONObject(new String(buf, 0, n, "UTF-8"));
            JSONArray arr = o.optJSONArray("markers");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) out.add(arr.getDouble(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------- audio info ----------

    public static class Info {
        public double durationSec = 0;
        public int sampleRate = 0;
        public int channels = 0;
        public int bits = 0; // 0 = 不明 (FLAC等)
        public boolean isFloat = false;
    }

    public static Info info(File f) {
        Info inf = new Info();
        if (f.getName().toLowerCase(Locale.US).endsWith(".wav")) {
            parseWav(f, inf);
        }
        if (inf.durationSec <= 0 || inf.sampleRate == 0) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(f.getAbsolutePath());
                String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dur != null && inf.durationSec <= 0) {
                    inf.durationSec = Long.parseLong(dur) / 1000.0;
                }
                if (inf.sampleRate == 0) {
                    // MMR にはサンプリングレートの標準キーが無いため WAV 以外は 0 のまま
                }
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
        }
        return inf;
    }

    private static void parseWav(File f, Info inf) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] hdr = new byte[4096];
            int n = fis.read(hdr);
            fis.close();
            if (n < 44 || hdr[0] != 'R' || hdr[1] != 'I') return;
            int fmtTag = u16(hdr, 20);
            inf.channels = u16(hdr, 22);
            inf.sampleRate = (int) u32(hdr, 24);
            int byteRate = (int) u32(hdr, 28);
            inf.bits = u16(hdr, 34);
            inf.isFloat = fmtTag == 3;
            // data チャンクを探す
            int pos = 36;
            long dataLen = -1;
            while (pos + 8 <= n) {
                String id = new String(hdr, pos, 4, "US-ASCII");
                long size = u32(hdr, pos + 4);
                if ("data".equals(id)) {
                    dataLen = size;
                    break;
                }
                pos += 8 + (int) size;
                if (pos > 4000) break;
            }
            if (dataLen < 0) dataLen = f.length() - 44;
            if (byteRate > 0) inf.durationSec = (double) dataLen / byteRate;
        } catch (Exception ignored) {
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    public static String fmtDuration(double sec) {
        int s = (int) Math.round(sec);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    public static String fmtHms(double sec) {
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    /** 現在の設定での残り録音可能秒数。 */
    public static long remainingSec(Context ctx, long bytesPerSec) {
        if (bytesPerSec <= 0) return -1;
        try {
            StatFs sf = new StatFs(dir(ctx).getAbsolutePath());
            long avail = sf.getAvailableBytes();
            return avail / bytesPerSec;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- rename / delete ----------

    public static boolean rename(File f, String newBaseName) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        File target = new File(f.getParentFile(), newBaseName + ext);
        if (target.exists()) return false;
        File sc = sidecar(f);
        boolean ok = f.renameTo(target);
        if (ok && sc.exists()) {
            sc.renameTo(sidecar(target));
        }
        return ok;
    }

    public static void delete(File f) {
        File sc = sidecar(f);
        if (sc.exists()) sc.delete();
        f.delete();
    }

    /**
     * 一斉リネーム。pattern: 0 = 接頭辞+連番, 1 = 日時+連番。
     * 成功件数を返す。
     */
    public static int batchRename(List<File> files, int pattern, String prefix, int start, int digits) {
        // 古い順に処理すると名前の意味が保たれる
        List<File> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int ok = 0;
        int num = start;
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        for (File f : sorted) {
            String base;
            if (pattern == 1) {
                base = df.format(new Date(f.lastModified())) + "_"
                        + String.format(Locale.US, "%0" + digits + "d", num);
            } else {
                base = prefix + String.format(Locale.US, "%0" + digits + "d", num);
            }
            num++;
            if (rename(f, base)) ok++;
        }
        return ok;
    }

    public static String metaLine(File f, Info inf) {
        StringBuilder sb = new StringBuilder();
        if (inf.sampleRate > 0) {
            sb.append(String.format(Locale.US, "%.1fkHz", inf.sampleRate / 1000.0));
        } else {
            sb.append(f.getName().toLowerCase(Locale.US).endsWith(".flac") ? "FLAC" : "WAV");
        }
        if (inf.bits > 0) sb.append(" / ").append(inf.bits).append("bit");
        if (inf.channels > 0) sb.append(inf.channels >= 2 ? " / ST" : " / MONO");
        sb.append(" / ").append(fmtDuration(inf.durationSec));
        sb.append(" / ").append(String.format(Locale.US, "%.1fMB", f.length() / 1048576.0));
        return sb.toString();
    }
}
