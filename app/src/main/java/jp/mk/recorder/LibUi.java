package jp.mk.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 試聴画面のコントローラ。一覧・再生・波形・FFT・マーカー・一斉リネーム。 */
public class LibUi {
    private final Activity act;
    private final ListView listRec;
    private final TextView tvLibCount, tvPlayTitle, tvPlayPos;
    private final WaveformView waveView;
    private final FftView fftView;
    private final Button btnPlay;
    private final LinearLayout markerRow;
    private final Player player = new Player();
    private Fft fft;
    private int fftSize;
    private final float[] fftBuf;
    private List<File> files = new ArrayList<>();
    private final Set<String> selection = new HashSet<>();
    private final Map<String, RecStore.Info> infoCache = new HashMap<>();
    private RecAdapter adapter;
    private File loadedFile;
    private double loadedDuration = 0;
    // チェック済みファイルの連続再生キュー
    private List<File> playQueue = new ArrayList<>();
    private int queueIndex = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updatePlayer();
            handler.postDelayed(this, 120);
        }
    };

    public LibUi(Activity act) {
        this.act = act;
        fftSize = Prefs.getInt(act, "fft_size", 4096);
        fft = new Fft(fftSize);
        fftBuf = new float[8192];

        listRec = act.findViewById(R.id.listRec);
        tvLibCount = act.findViewById(R.id.tvLibCount);
        tvPlayTitle = act.findViewById(R.id.tvPlayTitle);
        tvPlayPos = act.findViewById(R.id.tvPlayPos);
        waveView = act.findViewById(R.id.waveView);
        fftView = act.findViewById(R.id.fftView);
        btnPlay = act.findViewById(R.id.btnPlay);
        markerRow = act.findViewById(R.id.markerRow);
        Button btnStopPlay = act.findViewById(R.id.btnStopPlay);
        Button btnFft2048 = act.findViewById(R.id.btnFft2048);
        Button btnFft4096 = act.findViewById(R.id.btnFft4096);
        Button btnRenameBatch = act.findViewById(R.id.btnRenameBatch);
        Button btnClearSel = act.findViewById(R.id.btnClearSel);

        adapter = new RecAdapter();
        listRec.setAdapter(adapter);
        listRec.setOnItemClickListener((parent, view, pos, id) -> {
            // タップしたら即再生。チェック済みがあればその並びで連続再生する
            File f = files.get(pos);
            List<File> checked = checkedInOrder();
            if (!checked.isEmpty() && checked.contains(f)) {
                playQueue = checked;
                queueIndex = checked.indexOf(f);
            } else {
                playQueue = new ArrayList<>();
                playQueue.add(f);
                queueIndex = 0;
            }
            load(f);
            player.play();
            btnPlay.setText("⏸ 一時停止");
        });
        listRec.setOnItemLongClickListener((parent, view, pos, id) -> {
            showFileActions(files.get(pos));
            return true;
        });

        player.setListener(new Player.Listener() {
            @Override
            public void onEnded() {
                // キューに続きがあれば次のチェック済みファイルを再生
                if (queueIndex >= 0 && queueIndex + 1 < playQueue.size()) {
                    queueIndex++;
                    load(playQueue.get(queueIndex));
                    player.play();
                    btnPlay.setText("⏸ 一時停止");
                } else {
                    btnPlay.setText("▶ 再生");
                }
            }

            @Override
            public void onError(String msg) {
                Toast.makeText(act, "再生エラー: " + msg, Toast.LENGTH_LONG).show();
                btnPlay.setText("▶ 再生");
            }
        });

        btnPlay.setOnClickListener(v -> {
            if (loadedFile == null) {
                // 未選択でも、チェック済みがあれば先頭から連続再生
                List<File> checked = checkedInOrder();
                if (!checked.isEmpty()) {
                    playQueue = checked;
                    queueIndex = 0;
                    load(playQueue.get(0));
                    player.play();
                    btnPlay.setText("⏸ 一時停止");
                } else {
                    Toast.makeText(act, "ファイルを選んでください", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (player.isPlaying()) {
                player.pause();
                btnPlay.setText("▶ 再生");
            } else if (player.isPaused()) {
                player.resume();
                btnPlay.setText("⏸ 一時停止");
            } else {
                player.play();
                btnPlay.setText("⏸ 一時停止");
            }
        });
        btnStopPlay.setOnClickListener(v -> {
            player.stop();
            btnPlay.setText("▶ 再生");
            waveView.setProgress(0);
        });
        waveView.setOnSeekListener(frac -> {
            if (loadedFile != null && loadedDuration > 0) {
                player.seekTo(frac * loadedDuration);
            }
        });
        act.findViewById(R.id.btnFxLib).setOnClickListener(
                v -> ((MainActivity) act).openFxEditor());
        btnFft2048.setOnClickListener(v -> setFftSize(2048, btnFft2048, btnFft4096));
        btnFft4096.setOnClickListener(v -> setFftSize(4096, btnFft2048, btnFft4096));
        btnFft2048.setBackgroundResource(fftSize == 2048 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnFft4096.setBackgroundResource(fftSize == 4096 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);

        btnClearSel.setOnClickListener(v -> {
            selection.clear();
            adapter.notifyDataSetChanged();
        });
        btnRenameBatch.setOnClickListener(v -> showBatchRename());

        refresh();
        handler.post(tick);
    }

    private void setFftSize(int n, Button b2048, Button b4096) {
        fftSize = n;
        fft = new Fft(n);
        Prefs.putInt(act, "fft_size", n);
        b2048.setBackgroundResource(n == 2048 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        b4096.setBackgroundResource(n == 4096 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
    }

    public void refresh() {
        files = RecStore.list(act);
        // infoCache は path@mtime キーなので使い回す (再解析しない)
        if (adapter != null) adapter.notifyDataSetChanged();
        tvLibCount.setText(files.size() + " 件");
    }

    private RecStore.Info infoOf(File f) {
        String key = f.getAbsolutePath() + "@" + f.lastModified();
        RecStore.Info inf = infoCache.get(key);
        if (inf == null) {
            inf = RecStore.info(f);
            infoCache.put(key, inf);
        }
        return inf;
    }

    private List<File> checkedInOrder() {
        List<File> out = new ArrayList<>();
        for (File f : files) {
            if (selection.contains(f.getAbsolutePath())) out.add(f);
        }
        return out;
    }

    private void load(File f) {
        loadedFile = f;
        player.open(f);
        RecStore.Info inf = infoOf(f);
        loadedDuration = inf.durationSec > 0 ? inf.durationSec : player.getDurationSec();
        if (playQueue.size() > 1 && queueIndex >= 0) {
            tvPlayTitle.setText("▶ " + (queueIndex + 1) + "/" + playQueue.size() + "  " + f.getName());
        } else {
            tvPlayTitle.setText(f.getName());
        }
        btnPlay.setText("▶ 再生");
        waveView.setProgress(0);
        List<Double> markers = RecStore.loadMarkers(f);
        waveView.setMarkers(markers, loadedDuration);
        buildMarkerRow(markers);
        final File target = f;
        Player.decodePeaks(f, 480, (mins, maxs, dur, rate, ch) -> {
            if (target.equals(loadedFile)) {
                waveView.setPeaks(mins, maxs);
                if (dur > 0) {
                    loadedDuration = dur;
                    waveView.setMarkers(RecStore.loadMarkers(target), dur);
                }
            }
        });
    }

    private void buildMarkerRow(List<Double> markers) {
        markerRow.removeAllViews();
        for (Double m : markers) {
            Button b = new Button(act);
            b.setText("⚑ " + RecStore.fmtDuration(m));
            b.setTextSize(12);
            final double sec = m;
            b.setOnClickListener(v -> {
                if (loadedFile != null) player.seekTo(sec);
            });
            markerRow.addView(b);
        }
    }

    private void updatePlayer() {
        if (!listRec.isShown() || loadedFile == null) return;
        double pos = player.getPositionSec();
        if (loadedDuration > 0) {
            waveView.setProgress(Math.min(1.0, pos / loadedDuration));
        }
        tvPlayPos.setText(RecStore.fmtDuration(pos) + " / " + RecStore.fmtDuration(loadedDuration));
        if (player.isPlaying() && player.getSampleRate() > 0) {
            player.copyRecent(fftBuf, fftSize);
            float[] db = fft.magnitudeDb(fftBuf, 0);
            fftView.setData(db, player.getSampleRate());
        }
    }

    // ---------- file actions ----------

    private void showFileActions(File f) {
        String[] items = {"リネーム", "共有", "削除", "FX書き出し"};
        new AlertDialog.Builder(act)
                .setTitle(f.getName())
                .setItems(items, (d, which) -> {
                    if (which == 0) showRename(f);
                    else if (which == 1) share(f);
                    else if (which == 2) confirmDelete(f);
                    else if (which == 3) exportWithFx(f);
                })
                .show();
    }

    private void exportWithFx(File f) {
        if (!FxChain.anyOn()) {
            Toast.makeText(act, "FX が両方 OFF です。先に EQ / コンプを ON にしてください",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage("FX 書き出し中…");
        pd.setCancelable(false);
        pd.show();
        FxRender.render(f, (out, err) -> {
            pd.dismiss();
            if (out != null) {
                Toast.makeText(act, "書き出しました: " + out.getName(),
                        Toast.LENGTH_LONG).show();
                refresh();
            } else {
                Toast.makeText(act, "書き出しに失敗しました"
                        + (err != null ? ": " + err : ""), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showRename(File f) {
        EditText et = new EditText(act);
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        et.setText(dot > 0 ? name.substring(0, dot) : name);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(act)
                .setTitle("リネーム")
                .setView(et)
                .setPositiveButton("変更", (d, w) -> {
                    String base = et.getText().toString().trim();
                    if (!base.isEmpty()) {
                        if (RecStore.rename(f, base)) {
                            if (f.equals(loadedFile)) loadedFile = new File(f.getParentFile(),
                                    base + name.substring(dot));
                            refresh();
                        } else {
                            Toast.makeText(act, "同名ファイルがあります", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(act)
                .setTitle("削除")
                .setMessage(f.getName() + " を削除しますか？")
                .setPositiveButton("削除", (d, w) -> {
                    if (f.equals(loadedFile)) {
                        player.stop();
                        loadedFile = null;
                        tvPlayTitle.setText("（ファイル未選択）");
                    }
                    RecStore.delete(f);
                    selection.remove(f.getAbsolutePath());
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void share(File f) {
        Uri uri = Uri.parse("content://jp.mk.recorder.share/" + Uri.encode(f.getName()));
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        act.startActivity(Intent.createChooser(i, "共有"));
    }

    private void showBatchRename() {
        List<File> targets = new ArrayList<>();
        for (File f : files) {
            if (selection.contains(f.getAbsolutePath())) targets.add(f);
        }
        if (targets.isEmpty()) {
            Toast.makeText(act, "チェックボックスでファイルを選んでください", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 20, 40, 10);
        RadioGroup rg = new RadioGroup(act);
        RadioButton rbPrefix = new RadioButton(act);
        rbPrefix.setText("接頭辞 ＋ 連番");
        rbPrefix.setId(1);
        RadioButton rbDate = new RadioButton(act);
        rbDate.setText("録音日時 ＋ 連番");
        rbDate.setId(2);
        rg.addView(rbPrefix);
        rg.addView(rbDate);
        rg.check(1);
        EditText etPrefix = new EditText(act);
        etPrefix.setHint("接頭辞");
        etPrefix.setText("REC_");
        EditText etStart = new EditText(act);
        etStart.setHint("開始番号");
        etStart.setText("1");
        etStart.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(rg);
        box.addView(etPrefix);
        box.addView(etStart);
        new AlertDialog.Builder(act)
                .setTitle("一斉リネーム（" + targets.size() + " 件）")
                .setView(box)
                .setPositiveButton("実行", (d, w) -> {
                    int pattern = rg.getCheckedRadioButtonId() == 2 ? 1 : 0;
                    int start = 1;
                    try { start = Integer.parseInt(etStart.getText().toString()); } catch (Exception ignored) { }
                    int ok = RecStore.batchRename(targets, pattern,
                            etPrefix.getText().toString(), start, 3);
                    Toast.makeText(act, ok + " 件リネームしました", Toast.LENGTH_SHORT).show();
                    selection.clear();
                    loadedFile = null;
                    playQueue.clear();
                    queueIndex = -1;
                    tvPlayTitle.setText("（ファイル未選択）");
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    public void destroy() {
        handler.removeCallbacks(tick);
        player.stop();
    }

    // ---------- adapter ----------

    private class RecAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int pos) { return files.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(act).inflate(R.layout.item_rec, parent, false);
            }
            File f = files.get(pos);
            CheckBox chk = v.findViewById(R.id.chkItem);
            TextView tvName = v.findViewById(R.id.tvItemName);
            TextView tvMeta = v.findViewById(R.id.tvItemMeta);
            tvName.setText(f.getName());
            tvMeta.setText(RecStore.metaLine(f, infoOf(f)));
            chk.setOnCheckedChangeListener(null);
            chk.setChecked(selection.contains(f.getAbsolutePath()));
            chk.setOnCheckedChangeListener((bv, checked) -> {
                if (checked) selection.add(f.getAbsolutePath());
                else selection.remove(f.getAbsolutePath());
            });
            return v;
        }
    }
}
