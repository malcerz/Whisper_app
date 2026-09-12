package pl.whisperfiles;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.util.Locale;

@UnstableApi
public final class SubtitleEditorActivity extends Activity {
    static final String EXTRA_MEDIA_URI = "media_uri";
    static final String EXTRA_SIZE = "subtitle_size";
    static final String EXTRA_POSITION = "subtitle_position";
    static final String EXTRA_BACKGROUND = "subtitle_background";
    static final String EXTRA_TRACK_WORD = "track_word";
    static final String EXTRA_WORD_SCALE = "word_scale";
    static final String EXTRA_HIGHLIGHT = "word_highlight";

    private Uri mediaUri;
    private int size;
    private int position;
    private boolean background;
    private boolean trackWord;
    private float wordScale;
    private int highlight;
    private VideoInfo videoInfo;

    private List<SubtitleWord> words = new ArrayList<>();
    private List<SubtitleCue> cues = new ArrayList<>();
    private int cueIndex;
    private boolean bindingText;
    private boolean dirty;

    private SubtitlePreviewView preview;
    private TextView cueLabel;
    private TextView finalLayout;
    private EditText editor;
    private Button previous;
    private Button next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String media = getIntent().getStringExtra(EXTRA_MEDIA_URI);
        if (media == null) {
            finish();
            return;
        }
        mediaUri = Uri.parse(media);
        size = getIntent().getIntExtra(EXTRA_SIZE, SubtitleBurnService.SIZE_MEDIUM);
        position = getIntent().getIntExtra(EXTRA_POSITION, SubtitleCanvasOverlay.POSITION_BOTTOM);
        background = getIntent().getBooleanExtra(EXTRA_BACKGROUND, false);
        trackWord = getIntent().getBooleanExtra(EXTRA_TRACK_WORD, true);
        wordScale = getIntent().getFloatExtra(EXTRA_WORD_SCALE, 1.12f);
        highlight = getIntent().getIntExtra(EXTRA_HIGHLIGHT, 55);
        videoInfo = VideoInfo.read(this, mediaUri);

        try {
            words = new ArrayList<>(SubtitleProject.loadWords(this));
        } catch (Exception e) {
            Toast.makeText(this, "Nie można wczytać napisów: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (words.isEmpty()) {
            Toast.makeText(this, "Brak napisów do edycji", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        buildUi();
        rebuildCues(0L);
        showCue();
    }

    private void buildUi() {
        int pad = dp(14);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("Edycja napisów", 24, true);
        root.addView(title);
        TextView hint = text(
                "Podgląd i podział na wiersze używają dokładnie tego samego układu co eksport MP4. " +
                        "Popraw tekst, a układ przeliczy się na żywo.", 14, false);
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        preview = new SubtitlePreviewView(this);
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(390)));

        cueLabel = text("", 14, true);
        cueLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        cueLabel.setPadding(0, dp(10), 0, dp(6));
        root.addView(cueLabel);

        finalLayout = text("", 20, true);
        finalLayout.setGravity(Gravity.CENTER);
        finalLayout.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(finalLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView editTitle = text("Tekst do poprawy", 15, true);
        editTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(editTitle);

        editor = new EditText(this);
        editor.setTextSize(18);
        editor.setMinLines(3);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setSingleLine(false);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (bindingText) return;
                dirty = true;
                updateLivePreview(s.toString());
            }
        });
        root.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        previous = button("← Poprzedni");
        next = button("Następny →");
        previous.setOnClickListener(v -> move(-1));
        next.setOnClickListener(v -> move(1));
        nav.addView(previous, weighted());
        nav.addView(next, weighted());
        root.addView(nav);

        Button save = button("Zapisz poprawki");
        save.setOnClickListener(v -> {
            if (commitCurrent()) {
                Toast.makeText(this, "Napisy zapisane", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
            }
        });
        root.addView(save);

        setContentView(scroll);
    }

    private void move(int delta) {
        long oldStart = currentCue() == null ? 0L : currentCue().startMs;
        if (!commitCurrent()) return;
        int target = cueIndex + delta;
        if (delta > 0 && cueIndex < cues.size() - 1) target = cueIndex + 1;
        if (delta < 0 && cueIndex > 0) target = cueIndex - 1;
        cueIndex = Math.max(0, Math.min(cues.size() - 1, target));
        showCue();
    }

    private boolean commitCurrent() {
        SubtitleCue cue = currentCue();
        if (cue == null) return true;
        if (dirty) {
            String text = editor.getText().toString();
            long anchor = cue.startMs;
            words = SubtitleTimelineStore.replaceRange(
                    words, cue.sourceStartIndex, cue.sourceEndIndex, text);
            rebuildCues(anchor);
            dirty = false;
        }
        try {
            SubtitleProject.saveWordsAndOutputs(this, mediaUri, words, size, effectiveScale());
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Błąd zapisu napisów: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void rebuildCues(long anchorMs) {
        cues = new ArrayList<>(SubtitleLayoutEngine.layout(
                words, videoInfo.width, videoInfo.height,
                SubtitleBurnService.relativeSize(size), effectiveScale()));
        if (cues.isEmpty()) {
            cueIndex = 0;
            return;
        }
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            if (anchorMs >= cue.startMs && anchorMs < cue.endMs) {
                best = i;
                break;
            }
            long distance = Math.abs(cue.startMs - anchorMs);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        cueIndex = Math.max(0, Math.min(best, cues.size() - 1));
    }

    private void showCue() {
        SubtitleCue cue = currentCue();
        if (cue == null) return;
        bindingText = true;
        editor.setText(cue.text.replace('\n', ' '));
        editor.setSelection(editor.length());
        bindingText = false;
        dirty = false;
        cueLabel.setText(String.format(Locale.ROOT, "%d / %d   %s – %s",
                cueIndex + 1, cues.size(), time(cue.startMs), time(cue.endMs)));
        finalLayout.setText(cue.text);
        preview.setPreview(videoInfo, cue, position, SubtitleBurnService.relativeSize(size),
                background, trackWord, wordScale, highlight);
        previous.setEnabled(cueIndex > 0);
        next.setEnabled(cueIndex + 1 < cues.size());
    }

    private void updateLivePreview(String text) {
        SubtitleCue original = currentCue();
        if (original == null) return;
        List<SubtitleWord> temporary = SubtitleTimelineStore.wordsFromText(
                text, original.startMs, original.endMs);
        List<SubtitleCue> laidOut = SubtitleLayoutEngine.layout(
                temporary, videoInfo.width, videoInfo.height,
                SubtitleBurnService.relativeSize(size), effectiveScale());
        if (laidOut.isEmpty()) {
            finalLayout.setText("");
            preview.setPreview(videoInfo, null, position, SubtitleBurnService.relativeSize(size),
                    background, trackWord, wordScale, highlight);
            return;
        }
        SubtitleCue first = laidOut.get(0);
        if (laidOut.size() == 1) {
            finalLayout.setText(first.text);
        } else {
            StringBuilder shown = new StringBuilder(first.text);
            shown.append("\n\n→ po zapisie: ").append(laidOut.size()).append(" krótkie napisy");
            finalLayout.setText(shown);
        }
        preview.setPreview(videoInfo, first, position, SubtitleBurnService.relativeSize(size),
                background, trackWord, wordScale, highlight);
    }

    private float effectiveScale() {
        return trackWord ? wordScale : 1f;
    }

    private SubtitleCue currentCue() {
        if (cues.isEmpty()) return null;
        cueIndex = Math.max(0, Math.min(cueIndex, cues.size() - 1));
        return cues.get(cueIndex);
    }

    @Override
    public void onBackPressed() {
        if (commitCurrent()) {
            setResult(RESULT_OK);
            super.onBackPressed();
        }
    }

    private static String time(long ms) {
        long minutes = ms / 60000L;
        long seconds = (ms / 1000L) % 60L;
        long millis = ms % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis);
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
