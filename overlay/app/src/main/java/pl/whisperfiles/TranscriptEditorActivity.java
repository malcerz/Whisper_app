package pl.whisperfiles;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.List;

@UnstableApi
public final class TranscriptEditorActivity extends Activity {
    private static final String TAG = "TranscriptEditor";

    static final String EXTRA_MEDIA_URI = "media_uri";
    static final String EXTRA_SIZE_PERCENT = "subtitle_size_percent";
    static final String EXTRA_WORD_SCALE = "word_scale";

    private Uri mediaUri;
    private int subtitleSizePercentX10;
    private float activeWordScale;
    private List<SubtitleWord> words = new ArrayList<>();
    private EditText editor;
    private boolean dirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String media = getIntent().getStringExtra(EXTRA_MEDIA_URI);
        if (media == null || media.trim().isEmpty()) {
            Toast.makeText(this, "Brak pliku źródłowego transkrypcji", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mediaUri = Uri.parse(media);
        subtitleSizePercentX10 = getIntent().getIntExtra(EXTRA_SIZE_PERCENT,
                SubtitleStyle.SIZE_X10_DEFAULT);
        activeWordScale = getIntent().getFloatExtra(EXTRA_WORD_SCALE, 1f);

        try {
            words = new ArrayList<>(SubtitleProject.loadWords(this));
            if (words.isEmpty()) {
                Toast.makeText(this, "Brak tekstu transkrypcji do edycji", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            buildUi();
        } catch (Exception e) {
            Log.e(TAG, "Transcript editor initialization failed", e);
            Toast.makeText(this, "Nie można wczytać transkrypcji: " + safeMessage(e),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(text("Edytuj transkrypcję", 24, true));
        TextView hint = text(
                "Popraw rozpoznany tekst. Zapis zaktualizuje pliki TXT i SRT, zachowując czasy słów, gdy ich liczba się nie zmieni.",
                14, false);
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        editor = new EditText(this);
        editor.setTextSize(18);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(16);
        editor.setSingleLine(false);
        editor.setText(SubtitleProject.plainText(words));
        editor.setSelection(editor.length());
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { dirty = true; }
        });
        root.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button save = button("Zapisz transkrypcję");
        save.setOnClickListener(v -> {
            if (saveTranscript()) {
                Toast.makeText(this, "Transkrypcja zapisana", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
            }
        });
        root.addView(save);
        setContentView(scroll);
    }

    private boolean saveTranscript() {
        if (!dirty) return true;
        String text = editor.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Transkrypcja nie może być pusta", Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            List<SubtitleWord> updated = SubtitleTimelineStore.replaceRange(
                    words, 0, words.size() - 1, text);
            if (updated.isEmpty()) throw new IllegalStateException("Brak słów po edycji");
            SubtitleProject.saveWordsAndOutputs(this, mediaUri, updated,
                    SubtitleStyle.relativeTextSize(subtitleSizePercentX10), activeWordScale);
            words = updated;
            dirty = false;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Transcript save failed", e);
            Toast.makeText(this, "Nie można zapisać transkrypcji: " + safeMessage(e),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (!dirty || saveTranscript()) {
            if (!dirty) setResult(RESULT_OK);
            super.onBackPressed();
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }
}
