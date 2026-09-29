package pl.whisperfiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.util.UnstableApi;

@UnstableApi
public final class HomeActivity extends Activity {
    private static final int RECORD_VIDEO = 2001;
    private static final String PREFS = "whisper_captions_2_teleprompter";
    private static final String PREF_SCRIPT = "script";

    private TextView scriptPreview;
    private TextView lastRecording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshScriptPreview();
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("Whisper Captions 2", 28, true);
        root.addView(title);

        TextView subtitle = text("Teleprompter + nagrywanie MP4 + dotychczasowe generowanie napisów.", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        LinearLayout topButtons = new LinearLayout(this);
        topButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = button("Edytuj tekst");
        edit.setOnClickListener(v -> editScript());
        topButtons.addView(edit, weighted());

        Button record = button("Nagrywaj film");
        record.setOnClickListener(v -> startRecording());
        topButtons.addView(record, weighted());
        root.addView(topButtons);

        TextView teleTitle = text("Tekst telepromptera", 18, true);
        teleTitle.setPadding(0, dp(16), 0, dp(6));
        root.addView(teleTitle);

        scriptPreview = text("", 17, false);
        scriptPreview.setPadding(dp(10), dp(10), dp(10), dp(10));
        scriptPreview.setGravity(Gravity.START);
        root.addView(scriptPreview);

        lastRecording = text("", 14, false);
        lastRecording.setPadding(0, dp(10), 0, dp(10));
        root.addView(lastRecording);

        Button generator = button("Wczytaj MP4 / generator napisów");
        generator.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(generator);

        TextView hint = text(
                "Nagrania są zapisywane jako MP4 w folderze Movies/WhisperCaptions2. " +
                        "Po nagraniu otwórz generator i wybierz ten MP4 przyciskiem „Wybierz WAV lub MP4”.",
                14, false);
        hint.setPadding(0, dp(12), 0, dp(18));
        root.addView(hint);

        setContentView(scroll);
    }

    private void editScript() {
        EditText input = new EditText(this);
        input.setText(getScript());
        input.setTextSize(18);
        input.setMinLines(12);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setSingleLine(false);
        input.setSelection(input.length());

        ScrollView holder = new ScrollView(this);
        holder.setPadding(dp(12), dp(4), dp(12), dp(4));
        holder.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Tekst telepromptera")
                .setView(holder)
                .setPositiveButton("Zapisz", (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putString(PREF_SCRIPT, input.getText().toString()).apply();
                    refreshScriptPreview();
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void startRecording() {
        String script = getScript().trim();
        if (script.isEmpty()) {
            Toast.makeText(this, "Najpierw wpisz tekst telepromptera", Toast.LENGTH_LONG).show();
            editScript();
            return;
        }
        Intent i = new Intent(this, TeleprompterActivity.class);
        i.putExtra(TeleprompterActivity.EXTRA_SCRIPT, script);
        startActivityForResult(i, RECORD_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RECORD_VIDEO || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) {
            lastRecording.setText("Ostatnie nagranie zapisane: " + uri);
            Toast.makeText(this, "MP4 zapisany. Możesz teraz otworzyć generator napisów.", Toast.LENGTH_LONG).show();
        }
    }

    private String getScript() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString(PREF_SCRIPT, "");
    }

    private void refreshScriptPreview() {
        String script = getScript().trim();
        if (script.isEmpty()) {
            scriptPreview.setText("Brak tekstu. Użyj „Edytuj tekst”.");
            return;
        }
        String shown = script.length() > 700 ? script.substring(0, 700) + "…" : script;
        scriptPreview.setText(shown);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
