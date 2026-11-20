package jp.mk.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {
    private View viewRecord, viewLibrary, viewSettings;
    private Button btnTabRec, btnTabLib, btnTabSet;
    private RecUi recUi;
    private LibUi libUi;
    private SetUi setUi;
    private FxUi fxUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.load(this);
        Fx.load(this);
        setContentView(R.layout.activity_main);

        viewRecord = findViewById(R.id.viewRecord);
        viewLibrary = findViewById(R.id.viewLibrary);
        viewSettings = findViewById(R.id.viewSettings);
        btnTabRec = findViewById(R.id.btnTabRec);
        btnTabLib = findViewById(R.id.btnTabLib);
        btnTabSet = findViewById(R.id.btnTabSet);

        recUi = new RecUi(this);
        libUi = new LibUi(this);
        setUi = new SetUi(this);
        fxUi = new FxUi(this, findViewById(R.id.viewFx));

        btnTabRec.setOnClickListener(v -> showTab(0));
        btnTabLib.setOnClickListener(v -> showTab(1));
        btnTabSet.setOnClickListener(v -> showTab(2));
        showTab(0);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            RecorderService.ensureEngine(this);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    /** FX エディタを開く (録音/試聴の両画面から呼ぶ)。 */
    public void openFxEditor() {
        if (fxUi != null) fxUi.show();
    }

    @Override
    public void onBackPressed() {
        if (fxUi != null && fxUi.isVisible()) {
            fxUi.hide();
            return;
        }
        super.onBackPressed();
    }

    private void showTab(int idx) {
        viewRecord.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        viewLibrary.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        btnTabRec.setBackgroundResource(idx == 0 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnTabLib.setBackgroundResource(idx == 1 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnTabSet.setBackgroundResource(idx == 2 ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        if (idx == 1) libUi.refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                RecorderService.ensureEngine(this);
            } else {
                Toast.makeText(this, "マイク権限がないと録音できません", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (recUi != null) recUi.destroy();
        if (libUi != null) libUi.destroy();
        RecorderService.stopEngineIfIdle(this);
        super.onDestroy();
    }
}
