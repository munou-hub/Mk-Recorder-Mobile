package jp.mk.recorder;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/** FX エディタ (EQ 4 バンド + コンプ)。スライダーはコードで生成する。 */
public class FxUi {
    private final Activity act;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button btnEqOn, btnCompOn;
    private final GrView grView;
    private final TextView tvGr;
    private final List<Slider> sliders = new ArrayList<>();
    private final Button[] bandBtns = new Button[Fx.BANDS];
    private boolean visible = false;

    private final Runnable grTick = new Runnable() {
        @Override
        public void run() {
            grView.setGr(Fx.grDbNow);
            tvGr.setText(Fx.grDbNow > 0.05
                    ? String.format("-%.1f dB", Fx.grDbNow) : "0.0 dB");
            if (visible) handler.postDelayed(this, 50);
        }
    };

    private class Slider {
        final SeekBar seek;
        final TextView val;
        final IntSupplier get;
        final IntFunction<String> fmt;

        Slider(SeekBar seek, TextView val, IntSupplier get, IntFunction<String> fmt) {
            this.seek = seek; this.val = val; this.get = get; this.fmt = fmt;
        }

        void refresh() {
            int p = get.getAsInt();
            seek.setProgress(p);
            val.setText(fmt.apply(p));
        }
    }

    public FxUi(Activity act, View root) {
        this.act = act;
        this.root = root;
        btnEqOn = root.findViewById(R.id.btnFxEqOn);
        btnCompOn = root.findViewById(R.id.btnFxCompOn);
        grView = root.findViewById(R.id.grViewFx);
        tvGr = root.findViewById(R.id.tvGrValue);
        LinearLayout eqBox = root.findViewById(R.id.fxEqContainer);
        LinearLayout compBox = root.findViewById(R.id.fxCompContainer);

        root.findViewById(R.id.btnFxClose).setOnClickListener(v -> hide());
        btnEqOn.setOnClickListener(v -> {
            Fx.eqOn = !Fx.eqOn;
            Fx.touch(); Fx.save(act); refreshToggles();
        });
        btnCompOn.setOnClickListener(v -> {
            Fx.compOn = !Fx.compOn;
            Fx.touch(); Fx.save(act); refreshToggles();
        });
        root.findViewById(R.id.btnFxReset).setOnClickListener(v -> resetAll());

        String[] bandNames = {"B1 ローシェルフ", "B2 ピーク", "B3 ピーク", "B4 ハイシェルフ"};
        for (int i = 0; i < Fx.BANDS; i++) {
            final int b = i;
            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.topMargin = dp(8);
            head.setLayoutParams(hp);
            TextView name = new TextView(act);
            name.setText(bandNames[i]);
            name.setTextSize(13);
            name.setTextColor(act.getColor(R.color.text_main));
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            head.addView(name);
            Button onBtn = new Button(act);
            onBtn.setTextSize(11);
            onBtn.setOnClickListener(v -> {
                Fx.bandOn[b] = !Fx.bandOn[b];
                Fx.touch(); Fx.save(act); refreshToggles();
            });
            bandBtns[i] = onBtn;
            head.addView(onBtn);
            eqBox.addView(head);

            addSlider(eqBox, "周波数", 1000,
                    () -> freqToProgress(b),
                    p -> { Fx.bandFreq[b] = progressToFreq(b, p); Fx.touch(); Fx.save(act); },
                    p -> fmtFreq(progressToFreq(b, p)));
            addSlider(eqBox, "ゲイン", 300,
                    () -> Fx.bandGain10[b] + 150,
                    p -> { Fx.bandGain10[b] = p - 150; Fx.touch(); Fx.save(act); },
                    p -> String.format("%+.1f dB", (p - 150) / 10.0));
            addSlider(eqBox, "Q", 1000,
                    () -> qToProgress(Fx.bandQ100[b] / 100.0),
                    p -> { Fx.bandQ100[b] = (int) Math.round(progressToQ(p) * 100); Fx.touch(); Fx.save(act); },
                    p -> String.format("%.2f", progressToQ(p)));
        }

        addSlider(compBox, "スレッショルド", 600,
                () -> Fx.compThr10 + 600,
                p -> { Fx.compThr10 = p - 600; Fx.touch(); Fx.save(act); },
                p -> String.format("%.1f dB", (p - 600) / 10.0));
        addSlider(compBox, "レシオ", 190,
                () -> Fx.compRatio10 - 10,
                p -> { Fx.compRatio10 = p + 10; Fx.touch(); Fx.save(act); },
                p -> (p + 10) >= 200 ? "∞ : 1" : String.format("%.1f : 1", (p + 10) / 10.0));
        addSlider(compBox, "アタック", 1000,
                () -> atkToProgress(Fx.compAttack10 / 10.0),
                p -> { Fx.compAttack10 = (int) Math.round(progressToAtk(p) * 10); Fx.touch(); Fx.save(act); },
                p -> String.format("%.1f ms", progressToAtk(p)));
        addSlider(compBox, "リリース", 1000,
                () -> relToProgress(Fx.compReleaseMs),
                p -> { Fx.compReleaseMs = (int) Math.round(progressToRel(p)); Fx.touch(); Fx.save(act); },
                p -> String.format("%d ms", (int) Math.round(progressToRel(p))));
        addSlider(compBox, "ニー", 240,
                () -> Fx.compKnee10,
                p -> { Fx.compKnee10 = p; Fx.touch(); Fx.save(act); },
                p -> String.format("%.1f dB", p / 10.0));
        addSlider(compBox, "メイクアップ", 240,
                () -> Fx.compMakeup10,
                p -> { Fx.compMakeup10 = p; Fx.touch(); Fx.save(act); },
                p -> String.format("+%.1f dB", p / 10.0));

        refreshAll();
    }

    // ---------- スライダー生成 ----------

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addSlider(LinearLayout parent, String label, int max,
                           IntSupplier get, IntConsumer set, IntFunction<String> fmt) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView lab = new TextView(act);
        lab.setText(label);
        lab.setTextSize(12);
        lab.setTextColor(act.getColor(R.color.text_sub));
        lab.setLayoutParams(new LinearLayout.LayoutParams(dp(76),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(lab);
        SeekBar seek = new SeekBar(act);
        seek.setMax(max);
        seek.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(seek);
        TextView val = new TextView(act);
        val.setTextSize(12);
        val.setTextColor(act.getColor(R.color.text_main));
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        val.setGravity(android.view.Gravity.END);
        val.setLayoutParams(new LinearLayout.LayoutParams(dp(76),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(val);
        Slider s = new Slider(seek, val, get, fmt);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    set.accept(progress);
                    val.setText(fmt.apply(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        sliders.add(s);
        parent.addView(row);
    }

    // ---------- 値変換 ----------

    private int freqToProgress(int b) {
        double min = Fx.FREQ_MIN[b], max = Fx.FREQ_MAX[b];
        return (int) Math.round(1000 * Math.log(Fx.bandFreq[b] / min) / Math.log(max / min));
    }

    private int progressToFreq(int b, int p) {
        double min = Fx.FREQ_MIN[b], max = Fx.FREQ_MAX[b];
        return (int) Math.round(min * Math.pow(max / min, p / 1000.0));
    }

    private String fmtFreq(int hz) {
        return hz >= 1000 ? String.format("%.2f kHz", hz / 1000.0) : hz + " Hz";
    }

    private int qToProgress(double q) {
        return (int) Math.round(1000 * Math.log(q / 0.4) / Math.log(20.0));
    }

    private double progressToQ(int p) {
        return 0.4 * Math.pow(20.0, p / 1000.0);
    }

    private int atkToProgress(double ms) {
        return (int) Math.round(1000 * Math.log(ms / 0.1) / Math.log(3000.0));
    }

    private double progressToAtk(int p) {
        return 0.1 * Math.pow(3000.0, p / 1000.0);
    }

    private int relToProgress(double ms) {
        return (int) Math.round(1000 * Math.log(ms / 10.0) / Math.log(100.0));
    }

    private double progressToRel(int p) {
        return 10.0 * Math.pow(100.0, p / 1000.0);
    }

    // ---------- 表示制御 ----------

    private void styleToggle(Button b, boolean on, String label) {
        b.setText(label + (on ? " ON" : " OFF"));
        b.setBackgroundResource(on ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
    }

    private void refreshToggles() {
        styleToggle(btnEqOn, Fx.eqOn, "EQ");
        styleToggle(btnCompOn, Fx.compOn, "COMP");
        for (int i = 0; i < Fx.BANDS; i++) {
            if (bandBtns[i] != null) styleToggle(bandBtns[i], Fx.bandOn[i], "B" + (i + 1));
        }
    }

    private void refreshAll() {
        refreshToggles();
        for (Slider s : sliders) s.refresh();
    }

    private void resetAll() {
        Fx.eqOn = false;
        Fx.compOn = false;
        for (int i = 0; i < Fx.BANDS; i++) {
            Fx.bandOn[i] = true;
            Fx.bandFreq[i] = Fx.FREQ_DEF[i];
            Fx.bandGain10[i] = 0;
            Fx.bandQ100[i] = i == 1 || i == 2 ? 100 : 90;
        }
        Fx.compThr10 = -180;
        Fx.compRatio10 = 30;
        Fx.compAttack10 = 100;
        Fx.compReleaseMs = 150;
        Fx.compKnee10 = 120;
        Fx.compMakeup10 = 0;
        Fx.touch();
        Fx.save(act);
        refreshAll();
    }

    public void show() {
        visible = true;
        root.setVisibility(View.VISIBLE);
        refreshAll();
        handler.post(grTick);
    }

    public void hide() {
        visible = false;
        root.setVisibility(View.GONE);
        handler.removeCallbacks(grTick);
    }

    public boolean isVisible() {
        return visible;
    }
}
