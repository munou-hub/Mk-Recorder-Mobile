package jp.mk.recorder;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** 録音画面のコントローラ。 */
public class RecUi {
    private final Activity act;
    private final MeterView meterView;
    private final TextView tvRecTime, tvRemain, tvStatus, tvFmtNote, tvGain;
    private final Button btnRec, btnStop, btnMarker;
    private final Button btnFmtWav, btnFmtFlac;
    private final Button btnMeterLufs, btnMeterRms, btnMeterPeak;
    private final Spinner spRate, spDepth, spCh;
    private final SeekBar seekGain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean binding = true;
    private int meterMode;
    private String shownError = null;

    private static final int[] RATES = {44100, 48000, 96000};
    private static final String[] RATE_LABELS = {"44.1 kHz", "48 kHz", "96 kHz"};
    private static final int[] DEPTHS = {WavWriter.MODE_INT16, WavWriter.MODE_INT24, WavWriter.MODE_FLOAT32};
    private static final String[] DEPTH_LABELS = {"16 bit", "24 bit", "32 bit float"};

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 150);
        }
    };

    public RecUi(Activity act) {
        this.act = act;
        meterMode = Prefs.getInt(act, "meter_mode", 0);
        meterView = act.findViewById(R.id.meterView);
        tvRecTime = act.findViewById(R.id.tvRecTime);
        tvRemain = act.findViewById(R.id.tvRemain);
        tvStatus = act.findViewById(R.id.tvStatus);
        tvFmtNote = act.findViewById(R.id.tvFmtNote);
        tvGain = act.findViewById(R.id.tvGain);
        btnRec = act.findViewById(R.id.btnRec);
        btnStop = act.findViewById(R.id.btnStop);
        btnMarker = act.findViewById(R.id.btnMarker);
        btnFmtWav = act.findViewById(R.id.btnFmtWav);
        btnFmtFlac = act.findViewById(R.id.btnFmtFlac);
        btnMeterLufs = act.findViewById(R.id.btnMeterLufs);
        btnMeterRms = act.findViewById(R.id.btnMeterRms);
        btnMeterPeak = act.findViewById(R.id.btnMeterPeak);
        spRate = act.findViewById(R.id.spRate);
        spDepth = act.findViewById(R.id.spDepth);
        spCh = act.findViewById(R.id.spCh);
        seekGain = act.findViewById(R.id.seekGain);

        spRate.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, RATE_LABELS));
        spDepth.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, DEPTH_LABELS));
        spCh.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ステレオ", "モノラル"}));

        RecorderService.Config c = RecorderService.CONFIG;
        spRate.setSelection(indexOf(RATES, c.sampleRate, 1));
        spDepth.setSelection(indexOf(DEPTHS, c.depthMode, 1));
        spCh.setSelection(c.stereo ? 0 : 1);
        seekGain.setProgress((int) Math.round(c.gainDb * 10) + 480);
        tvGain.setText(fmtGain(c.gainDb));
        updateFormatButtons();
        updateMeterButtons();
        RecorderService.lufsWanted = meterMode == 0;
        binding = false;

        AdapterView.OnItemSelectedListener structural = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (binding) return;
                RecorderService.Config cfg = RecorderService.CONFIG;
                cfg.sampleRate = RATES[spRate.getSelectedItemPosition()];
                cfg.depthMode = DEPTHS[spDepth.getSelectedItemPosition()];
                cfg.stereo = spCh.getSelectedItemPosition() == 0;
                Prefs.save(act);
                updateFormatNote();
                RecorderService.applyConfigChange();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        };
        spRate.setOnItemSelectedListener(structural);
        spDepth.setOnItemSelectedListener(structural);
        spCh.setOnItemSelectedListener(structural);

        btnFmtWav.setOnClickListener(v -> setFormat(false));
        btnFmtFlac.setOnClickListener(v -> setFormat(true));
        btnMeterLufs.setOnClickListener(v -> setMeterMode(0));
        btnMeterRms.setOnClickListener(v -> setMeterMode(1));
        btnMeterPeak.setOnClickListener(v -> setMeterMode(2));

        seekGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                double db = (progress - 480) / 10.0;
                RecorderService.CONFIG.gainDb = db;
                tvGain.setText(fmtGain(db));
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Prefs.save(act);
            }
        });

        btnRec.setOnClickListener(v -> {
            if (!RecorderService.engineOn) {
                Toast.makeText(act, "入力エンジン起動中です", Toast.LENGTH_SHORT).show();
                return;
            }
            RecorderService.startRecording();
        });
        btnStop.setOnClickListener(v -> RecorderService.stopRecording());
        btnMarker.setOnClickListener(v -> {
            if (RecorderService.recording) {
                RecorderService.addMarker();
                Toast.makeText(act, "マーカー " + RecorderService.curMarkers.size() + " 個目",
                        Toast.LENGTH_SHORT).show();
            }
        });
        meterView.setOnClickListener(v -> meterView.clearClip());
        updateFormatNote();
        handler.post(tick);
    }

    private static int indexOf(int[] arr, int v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return def;
    }

    private static String fmtGain(double db) {
        return String.format("%+.1f dB", db);
    }

    private void setFormat(boolean flac) {
        RecorderService.CONFIG.flac = flac;
        Prefs.save(act);
        updateFormatButtons();
        updateFormatNote();
        RecorderService.applyConfigChange();
    }

    private void updateFormatButtons() {
        boolean flac = RecorderService.CONFIG.flac;
        btnFmtWav.setBackgroundResource(!flac ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnFmtFlac.setBackgroundResource(flac ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
    }

    private void updateFormatNote() {
        RecorderService.Config c = RecorderService.CONFIG;
        if (c.flac && c.depthMode != WavWriter.MODE_INT16) {
            tvFmtNote.setText("FLAC は 16bit 固定で録音されます");
        } else {
            tvFmtNote.setText("");
        }
    }

    private void setMeterMode(int mode) {
        meterMode = mode;
        Prefs.putInt(act, "meter_mode", mode);
        RecorderService.lufsWanted = mode == 0;
        updateMeterButtons();
    }

    private void updateMeterButtons() {
        btnMeterLufs.setBackgroundResource(meterMode == 0 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnMeterRms.setBackgroundResource(meterMode == 1 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnMeterPeak.setBackgroundResource(meterMode == 2 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        meterView.setLabel(meterMode == 0 ? "LUFS" : meterMode == 1 ? "RMS" : "PEAK");
    }

    private void update() {
        Meters m = RecorderService.meters;
        if (m != null && RecorderService.engineOn && meterView.isShown()) {
            double l, r;
            switch (meterMode) {
                case 1: l = m.rmsDb(0); r = m.rmsDb(1); break;
                case 2: l = m.peakDb(0); r = m.peakDb(1); break;
                default: l = m.lufsDb(0); r = m.lufsDb(1); break;
            }
            meterView.setStereo(RecorderService.effOutCh == 2);
            meterView.setLevels(l, RecorderService.effOutCh == 2 ? r : l,
                    m.peakLin(0), m.peakLin(1));
        }
        if (RecorderService.recording && RecorderService.effRate > 0) {
            tvRecTime.setText(RecStore.fmtHms(RecorderService.recFrames / (double) RecorderService.effRate));
        } else if (!RecorderService.saving) {
            tvRecTime.setText("00:00:00");
        }
        long remain = RecStore.remainingSec(act, RecorderService.bytesPerSec());
        tvRemain.setText(remain >= 0 ? "残り " + RecStore.fmtHms(remain) : "残り --:--");

        String st;
        if (RecorderService.lastError != null) {
            st = "⚠ " + RecorderService.lastError;
            if (!RecorderService.lastError.equals(shownError)) {
                shownError = RecorderService.lastError;
                Toast.makeText(act, shownError, Toast.LENGTH_LONG).show();
            }
        } else if (RecorderService.saving) {
            st = "保存中…";
        } else if (RecorderService.recording) {
            st = "録音中: " + (RecorderService.currentFile != null ? RecorderService.currentFile.getName() : "");
        } else if (RecorderService.engineOn) {
            st = String.format("監視中 %s %.1fkHz / %s / %s",
                    RecorderService.effSourceLabel,
                    RecorderService.effRate / 1000.0,
                    RecorderService.effDepthMode == WavWriter.MODE_INT16 ? "16bit"
                            : RecorderService.effDepthMode == WavWriter.MODE_INT24 ? "24bit" : "32bit float",
                    RecorderService.effOutCh == 2 ? "ST" : "MONO");
            if (!RecorderService.effNote.isEmpty()) st += "（" + RecorderService.effNote + "）";
        } else {
            st = "エンジン起動中…";
        }
        if (RecorderService.engineOn) {
            st += " [chk=" + RecorderService.diagChunks + " rd=" + RecorderService.diagLastRead + "]";
        }
        tvStatus.setText(st);
        btnRec.setText(RecorderService.recording ? "● 録音中" : "● 録音");
        btnRec.setEnabled(!RecorderService.recording && !RecorderService.saving);
        btnStop.setEnabled(RecorderService.recording);
        btnMarker.setEnabled(RecorderService.recording);
    }

    public void destroy() {
        handler.removeCallbacks(tick);
    }
}
