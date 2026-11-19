package jp.mk.recorder;

import android.app.Activity;
import android.media.MediaRecorder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

/** 設定画面のコントローラ。 */
public class SetUi {
    private boolean binding = true;

    private static final String[] SOURCE_LABELS = {
            "UNPROCESSED（無加工）", "MIC（標準）", "VOICE_RECOGNITION（音声認識）", "CAMCORDER（ビデオカメラ）"};
    private static final int[] SOURCE_VALUES = {
            MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER};
    private static final int[] PREROLL_VALUES = {0, 3, 5, 10};
    private static final String[] PREROLL_LABELS = {"なし", "3 秒", "5 秒", "10 秒"};

    public SetUi(Activity act) {
        Spinner spSource = act.findViewById(R.id.spSource);
        Spinner spPreroll = act.findViewById(R.id.spPreroll);
        CheckBox chkSafety = act.findViewById(R.id.chkSafety);
        TextView tvStorage = act.findViewById(R.id.tvStorage);
        TextView tvAbout = act.findViewById(R.id.tvAbout);

        spSource.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, SOURCE_LABELS));
        spPreroll.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, PREROLL_LABELS));

        RecorderService.Config c = RecorderService.CONFIG;
        int si = 0;
        for (int i = 0; i < SOURCE_VALUES.length; i++) if (SOURCE_VALUES[i] == c.source) si = i;
        spSource.setSelection(si);
        int pi = 2;
        for (int i = 0; i < PREROLL_VALUES.length; i++) if (PREROLL_VALUES[i] == c.prerollSec) pi = i;
        spPreroll.setSelection(pi);
        chkSafety.setChecked(c.safety);
        binding = false;

        spSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (binding) return;
                RecorderService.CONFIG.source = SOURCE_VALUES[pos];
                Prefs.save(act);
                RecorderService.applyConfigChange();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        spPreroll.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (binding) return;
                RecorderService.CONFIG.prerollSec = PREROLL_VALUES[pos];
                Prefs.save(act);
                RecorderService.applyConfigChange();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        chkSafety.setOnCheckedChangeListener((v, checked) -> {
            RecorderService.CONFIG.safety = checked;
            Prefs.save(act);
        });

        tvStorage.setText(RecStore.dir(act).getAbsolutePath());
        tvAbout.setText("Mk Recorder Mobile v1.0 (MRM)\n"
                + "44.1/48/96 kHz、16/24bit + 32bit float、WAV/FLAC\n"
                + "LUFS/RMS/PEAK メーター、プリロール、セーフティトラック (-12dB)\n"
                + "内蔵マイクや Android 側ドライバの上限により、実効レートは端末依存です。");
    }
}
