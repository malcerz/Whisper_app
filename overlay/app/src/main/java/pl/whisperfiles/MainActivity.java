package pl.whisperfiles;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

@UnstableApi
public final class MainActivity extends Activity implements
        TranscriptionService.Listener, SubtitleBurnService.Listener {
    private static final int PICK_MEDIA = 1001;
    private static final int PICK_MODEL = 1002;
    private static final int SAVE_TXT = 1003;
    private static final int SAVE_SRT = 1004;
    private static final int SAVE_MP4 = 1005;
    private static final int EDIT_TRANSCRIPT = 1006;
    private static final int EDIT_SUBTITLES = 1007;

    private static final String PREFS = "whisper_files_ui";
    private static final String PREF_MEDIA = "media_uri";
    private static final String PREF_MODEL = "model_uri";
    private static final String PREF_SUB_SIZE_PERCENT = "subtitle_size_percent_x10";
    private static final String PREF_SUB_POSITION_PERCENT = "subtitle_position_percent";
    private static final String PREF_SUB_BACKGROUND = "subtitle_background";
    private static final String PREF_TRACK_WORD = "track_word";
    private static final String PREF_WORD_SCALE = "word_scale_percent";
    private static final String PREF_WORD_HIGHLIGHT = "word_highlight";
    private static final String PREF_WORD_HIGHLIGHT_COLOR = "word_highlight_color";
    // Legacy v0.4 keys, kept only to migrate old installations.
    private static final String PREF_SUB_SIZE_LEGACY = "subtitle_size";
    private static final String PREF_SUB_POSITION_LEGACY = "subtitle_position";

    private Uri mediaUri;
    private Uri modelUri;

    private TranscriptionService transcriptionService;
    private SubtitleBurnService burnService;
    private boolean transcriptionBound;
    private boolean burnBound;
    private boolean transcriptionRunning;
    private boolean burnRunning;

    private TextView mediaLabel;
    private TextView modelLabel;
    private TextView statusLabel;
    private TextView burnStatusLabel;
    private TextView preview;
    private ProgressBar progress;
    private ProgressBar burnProgress;
    private Button startButton;
    private Button cancelButton;
    private Button saveTxtButton;
    private Button saveSrtButton;
    private Button editTranscriptButton;
    private Button editSubtitlesButton;
    private Button burnButton;
    private Button cancelBurnButton;
    private Button saveMp4Button;
    private SeekBar sizeSeek;
    private SeekBar positionSeek;
    private TextView sizeLabel;
    private TextView positionLabel;
    private TextView highlightColorLabel;
    private TextView[] highlightSwatches;
    private int highlightColorIndex = SubtitleStyle.HIGHLIGHT_COLOR_DEFAULT_INDEX;
    private CheckBox subtitleBackground;
    private CheckBox trackWord;
    private SeekBar wordScaleSeek;
    private SeekBar wordHighlightSeek;
    private TextView wordScaleLabel;
    private TextView wordHighlightLabel;

    private final ServiceConnection transcriptionConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TranscriptionService.LocalBinder local = (TranscriptionService.LocalBinder) binder;
            transcriptionService = local.getService();
            transcriptionBound = true;
            transcriptionService.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (transcriptionService != null) transcriptionService.clearListener(MainActivity.this);
            transcriptionService = null;
            transcriptionBound = false;
        }
    };

    private final ServiceConnection burnConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            SubtitleBurnService.LocalBinder local = (SubtitleBurnService.LocalBinder) binder;
            burnService = local.getService();
            burnBound = true;
            burnService.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (burnService != null) burnService.clearListener(MainActivity.this);
            burnService = null;
            burnBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreSelections();
        updateSelectionLabels();
        updateButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, TranscriptionService.class),
                transcriptionConnection, Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, SubtitleBurnService.class),
                burnConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (transcriptionBound && transcriptionService != null) {
            transcriptionService.clearListener(this);
            unbindService(transcriptionConnection);
        }
        if (burnBound && burnService != null) {
            burnService.clearListener(this);
            unbindService(burnConnection);
        }
        transcriptionBound = false;
        burnBound = false;
        transcriptionService = null;
        burnService = null;
        super.onStop();
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(text("Whisper Files", 28, true));
        TextView subtitle = text("Offline: WAV + MP4 → transkrypcja PL + napisy do filmu.", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        Button pickMedia = button("Wybierz WAV lub MP4");
        pickMedia.setOnClickListener(v -> pickMedia());
        root.addView(pickMedia);
        mediaLabel = text("Nie wybrano pliku", 14, false);
        mediaLabel.setPadding(0, dp(5), 0, dp(14));
        root.addView(mediaLabel);

        Button pickModel = button("Wybierz model Whisper .bin");
        pickModel.setOnClickListener(v -> pickModel());
        root.addView(pickModel);
        modelLabel = text("Nie wybrano modelu", 14, false);
        modelLabel.setPadding(0, dp(5), 0, dp(14));
        root.addView(modelLabel);

        startButton = button("Transkrybuj i utwórz napisy");
        startButton.setOnClickListener(v -> startTranscription());
        root.addView(startButton);

        cancelButton = button("Anuluj transkrypcję");
        cancelButton.setOnClickListener(v -> {
            if (transcriptionService != null) transcriptionService.cancel();
        });
        root.addView(cancelButton);

        statusLabel = text("Gotowy", 15, true);
        statusLabel.setPadding(0, dp(14), 0, dp(6));
        root.addView(statusLabel);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        LinearLayout saves = new LinearLayout(this);
        saves.setOrientation(LinearLayout.HORIZONTAL);
        saveTxtButton = button("Zapisz TXT");
        saveSrtButton = button("Zapisz SRT");
        saveTxtButton.setOnClickListener(v -> createTextOutput(false));
        saveSrtButton.setOnClickListener(v -> createTextOutput(true));
        saves.addView(saveTxtButton, weighted());
        saves.addView(saveSrtButton, weighted());
        root.addView(saves);

        editTranscriptButton = button("Edytuj transkrypcję");
        editTranscriptButton.setOnClickListener(v -> openTranscriptEditor());
        root.addView(editTranscriptButton);

        editSubtitlesButton = button("Podgląd i edycja napisów filmu");
        editSubtitlesButton.setOnClickListener(v -> openSubtitleEditor());
        root.addView(editSubtitlesButton);

        TextView videoTitle = text("Film z wypalonymi napisami", 19, true);
        videoTitle.setPadding(0, dp(22), 0, dp(4));
        root.addView(videoTitle);
        TextView videoHint = text(
                "Dla MP4 napisy są renderowane na stałe w obrazie. Audio pozostaje bez efektów. " +
                        "Rozmiar i położenie ustawisz suwakami — podgląd i eksport używają tych samych wartości.",
                14, false);
        videoHint.setPadding(0, 0, 0, dp(10));
        root.addView(videoHint);

        sizeLabel = text("", 14, false);
        sizeLabel.setPadding(0, dp(2), 0, 0);
        root.addView(sizeLabel);
        sizeSeek = new SeekBar(this);
        sizeSeek.setMax(SubtitleStyle.SIZE_X10_MAX - SubtitleStyle.SIZE_X10_MIN);
        root.addView(sizeSeek);

        positionLabel = text("", 14, false);
        positionLabel.setPadding(0, dp(6), 0, 0);
        root.addView(positionLabel);
        positionSeek = new SeekBar(this);
        positionSeek.setMax(SubtitleStyle.POSITION_PERCENT_MAX - SubtitleStyle.POSITION_PERCENT_MIN);
        root.addView(positionSeek);

        subtitleBackground = new CheckBox(this);
        subtitleBackground.setText("Półprzezroczyste czarne tło pod napisami");
        root.addView(subtitleBackground);

        trackWord = new CheckBox(this);
        trackWord.setText("Śledź aktualnie wypowiadane słowo");
        root.addView(trackWord);

        highlightColorLabel = text("", 14, false);
        highlightColorLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(highlightColorLabel);
        root.addView(buildColorSwatches());

        wordScaleLabel = text("Powiększenie aktywnego słowa", 14, false);
        wordScaleLabel.setPadding(0, dp(8), 0, 0);
        root.addView(wordScaleLabel);
        wordScaleSeek = new SeekBar(this);
        wordScaleSeek.setMax(40); // 100..140%
        root.addView(wordScaleSeek);

        wordHighlightLabel = text("Zaznaczenie aktywnego słowa", 14, false);
        wordHighlightLabel.setPadding(0, dp(6), 0, 0);
        root.addView(wordHighlightLabel);
        wordHighlightSeek = new SeekBar(this);
        wordHighlightSeek.setMax(100);
        root.addView(wordHighlightSeek);

        SeekBar.OnSeekBarChangeListener styleListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateStyleLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveSubtitleStyle(); }
        };
        sizeSeek.setOnSeekBarChangeListener(styleListener);
        positionSeek.setOnSeekBarChangeListener(styleListener);
        wordScaleSeek.setOnSeekBarChangeListener(styleListener);
        wordHighlightSeek.setOnSeekBarChangeListener(styleListener);
        trackWord.setOnCheckedChangeListener((buttonView, isChecked) -> updateStyleLabels());

        burnButton = button("Wypal napisy do MP4");
        burnButton.setOnClickListener(v -> startBurn());
        root.addView(burnButton);

        cancelBurnButton = button("Anuluj eksport filmu");
        cancelBurnButton.setOnClickListener(v -> {
            if (burnService != null) burnService.cancel();
        });
        root.addView(cancelBurnButton);

        burnStatusLabel = text("Eksport filmu: gotowy", 15, true);
        burnStatusLabel.setPadding(0, dp(10), 0, dp(6));
        root.addView(burnStatusLabel);

        burnProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        burnProgress.setMax(100);
        root.addView(burnProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        saveMp4Button = button("Zapisz MP4 z napisami");
        saveMp4Button.setOnClickListener(v -> createMp4Output());
        root.addView(saveMp4Button);

        TextView resultTitle = text("Podgląd transkrypcji", 18, true);
        resultTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(resultTitle);
        preview = text("", 15, false);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(10), dp(10), dp(10), dp(24));
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/wav", "audio/x-wav", "audio/*", "video/mp4", "audio/mp4", "video/*"
        });
        startActivityForResult(i, PICK_MEDIA);
    }

    private void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_MODEL);
    }

    private void startTranscription() {
        if (mediaUri == null || modelUri == null) {
            Toast.makeText(this, "Wybierz plik WAV/MP4 i model .bin", Toast.LENGTH_LONG).show();
            return;
        }
        if (burnRunning) {
            Toast.makeText(this, "Najpierw zakończ eksport filmu", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(this, TranscriptionService.class);
        i.setAction(TranscriptionService.ACTION_START);
        i.putExtra(TranscriptionService.EXTRA_MEDIA_URI, mediaUri.toString());
        i.putExtra(TranscriptionService.EXTRA_MODEL_URI, modelUri.toString());
        startForegroundService(i);
        transcriptionRunning = true;
        statusLabel.setText("Uruchamianie…");
        updateButtons();
    }

    private void startBurn() {
        if (mediaUri == null || !isVideo(mediaUri)) {
            Toast.makeText(this, "Wybierz film MP4", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TranscriptionService.hasTranscriptFor(this, mediaUri)) {
            Toast.makeText(this, "Najpierw wykonaj transkrypcję tego filmu", Toast.LENGTH_LONG).show();
            return;
        }
        if (transcriptionRunning) return;

        saveSubtitleStyle();
        try {
            SubtitleProject.regenerateOutputs(this, mediaUri,
                    currentRelativeSize(), effectiveWordScale());
        } catch (Exception e) {
            Toast.makeText(this, "Nie można przygotować napisów: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, SubtitleBurnService.class);
        i.setAction(SubtitleBurnService.ACTION_START);
        i.putExtra(SubtitleBurnService.EXTRA_MEDIA_URI, mediaUri.toString());
        i.putExtra(SubtitleBurnService.EXTRA_SIZE_PERCENT, currentSizePercentX10());
        i.putExtra(SubtitleBurnService.EXTRA_POSITION_PERCENT, currentPositionPercent());
        i.putExtra(SubtitleBurnService.EXTRA_BACKGROUND, subtitleBackground.isChecked());
        i.putExtra(SubtitleBurnService.EXTRA_TRACK_WORD, trackWord.isChecked());
        i.putExtra(SubtitleBurnService.EXTRA_WORD_SCALE, currentWordScale());
        i.putExtra(SubtitleBurnService.EXTRA_HIGHLIGHT, wordHighlightSeek.getProgress());
        i.putExtra(SubtitleBurnService.EXTRA_HIGHLIGHT_COLOR, currentHighlightColor());
        startForegroundService(i);
        burnRunning = true;
        burnStatusLabel.setText("Eksport filmu: uruchamianie…");
        updateButtons();
    }

    private void createTextOutput(boolean srt) {
        if (srt && mediaUri != null && isVideo(mediaUri)) {
            try {
                saveSubtitleStyle();
                SubtitleProject.regenerateOutputs(this, mediaUri,
                        currentRelativeSize(), effectiveWordScale());
            } catch (Exception e) {
                Toast.makeText(this, "Nie można przeliczyć napisów: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        File source = new File(getFilesDir(), srt ? "last_transcript.srt" : "last_transcript.txt");
        if (!source.isFile() || source.length() == 0) {
            Toast.makeText(this, "Brak wyniku do zapisania", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(srt ? "application/x-subrip" : "text/plain");
        i.putExtra(Intent.EXTRA_TITLE, defaultOutputName(srt ? ".srt" : ".txt"));
        startActivityForResult(i, srt ? SAVE_SRT : SAVE_TXT);
    }

    private void createMp4Output() {
        File source = SubtitleBurnService.outputFile(this);
        if (!SubtitleBurnService.hasRenderFor(this, mediaUri) || !source.isFile() || source.length() == 0) {
            Toast.makeText(this, "Brak gotowego filmu z napisami", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_TITLE, defaultOutputName("_napisy.mp4"));
        startActivityForResult(i, SAVE_MP4);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EDIT_TRANSCRIPT || requestCode == EDIT_SUBTITLES) {
            if (resultCode == RESULT_OK) {
                statusLabel.setText(requestCode == EDIT_TRANSCRIPT
                        ? "Transkrypcja poprawiona i zapisana"
                        : "Napisy poprawione i zapisane");
                try {
                    File edited = new File(getFilesDir(), "last_transcript.txt");
                    if (edited.isFile()) preview.setText(readSmallText(edited));
                } catch (Exception ignored) {}
                updateButtons();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == PICK_MEDIA || requestCode == PICK_MODEL) {
            persistPermission(uri, data.getFlags());
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (requestCode == PICK_MEDIA) {
                mediaUri = uri;
                prefs.edit().putString(PREF_MEDIA, uri.toString()).apply();
            } else {
                modelUri = uri;
                prefs.edit().putString(PREF_MODEL, uri.toString()).apply();
            }
            updateSelectionLabels();
            updateButtons();
            return;
        }

        if (requestCode == SAVE_TXT || requestCode == SAVE_SRT) {
            File source = new File(getFilesDir(), requestCode == SAVE_SRT
                    ? "last_transcript.srt" : "last_transcript.txt");
            try {
                copyFileToUri(source, uri, null);
                Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Błąd zapisu: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == SAVE_MP4) {
            File source = SubtitleBurnService.outputFile(this);
            saveMp4Button.setEnabled(false);
            burnStatusLabel.setText("Zapisywanie filmu…");
            new Thread(() -> {
                try {
                    copyFileToUri(source, uri, percent -> runOnUiThread(() ->
                            burnStatusLabel.setText("Zapisywanie filmu… " + percent + "%")));
                    runOnUiThread(() -> {
                        burnStatusLabel.setText("Film zapisany");
                        Toast.makeText(this, "Zapisano MP4 z napisami", Toast.LENGTH_LONG).show();
                        updateButtons();
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        burnStatusLabel.setText("Błąd zapisu filmu");
                        Toast.makeText(this, "Błąd zapisu: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        updateButtons();
                    });
                }
            }, "WhisperFiles-save-mp4").start();
        }
    }

    private void persistPermission(Uri uri, int flags) {
        int take = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, take);
        } catch (Exception ignored) {}
    }

    private void restoreSelections() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String media = prefs.getString(PREF_MEDIA, null);
        String model = prefs.getString(PREF_MODEL, null);
        if (media != null) mediaUri = Uri.parse(media);
        if (model != null) modelUri = Uri.parse(model);
        sizeSeek.setProgress(loadSizePercentX10(prefs) - SubtitleStyle.SIZE_X10_MIN);
        positionSeek.setProgress(loadPositionPercent(prefs) - SubtitleStyle.POSITION_PERCENT_MIN);
        subtitleBackground.setChecked(prefs.getBoolean(PREF_SUB_BACKGROUND, false));
        trackWord.setChecked(prefs.getBoolean(PREF_TRACK_WORD, true));
        wordScaleSeek.setProgress(prefs.getInt(PREF_WORD_SCALE, 12));
        wordHighlightSeek.setProgress(prefs.getInt(PREF_WORD_HIGHLIGHT, 55));
        highlightColorIndex = SubtitleStyle.highlightColorIndex(
                prefs.getInt(PREF_WORD_HIGHLIGHT_COLOR, SubtitleStyle.defaultHighlightColor()));
        refreshHighlightSwatches();
        updateStyleLabels();
    }

    private void saveSubtitleStyle() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_SUB_SIZE_PERCENT, currentSizePercentX10())
                .putInt(PREF_SUB_POSITION_PERCENT, currentPositionPercent())
                .putBoolean(PREF_SUB_BACKGROUND, subtitleBackground.isChecked())
                .putBoolean(PREF_TRACK_WORD, trackWord.isChecked())
                .putInt(PREF_WORD_SCALE, wordScaleSeek.getProgress())
                .putInt(PREF_WORD_HIGHLIGHT, wordHighlightSeek.getProgress())
                .putInt(PREF_WORD_HIGHLIGHT_COLOR, currentHighlightColor())
                .apply();
    }

    /** Legacy Małe/Średnie/Duże value (0/1/2) is converted to a text height percentage. */
    private int loadSizePercentX10(SharedPreferences prefs) {
        if (prefs.contains(PREF_SUB_SIZE_PERCENT)) {
            return prefs.getInt(PREF_SUB_SIZE_PERCENT, SubtitleStyle.SIZE_X10_DEFAULT);
        }
        int legacy = prefs.getInt(PREF_SUB_SIZE_LEGACY, -1);
        if (legacy == 0) return 38;
        if (legacy == 2) return 60;
        return SubtitleStyle.SIZE_X10_DEFAULT;
    }

    /** Legacy Dół/Środek/Góra value (0/1/2) is converted to a position percentage from the top. */
    private int loadPositionPercent(SharedPreferences prefs) {
        if (prefs.contains(PREF_SUB_POSITION_PERCENT)) {
            return prefs.getInt(PREF_SUB_POSITION_PERCENT, SubtitleStyle.POSITION_PERCENT_DEFAULT);
        }
        int legacy = prefs.getInt(PREF_SUB_POSITION_LEGACY, -1);
        if (legacy == 1) return 50;
        if (legacy == 2) return 12;
        return SubtitleStyle.POSITION_PERCENT_DEFAULT;
    }

    private void openSubtitleEditor() {
        if (mediaUri == null || !isVideo(mediaUri) ||
                !TranscriptionService.hasTranscriptFor(this, mediaUri)) {
            Toast.makeText(this, "Najpierw wykonaj transkrypcję filmu", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            saveSubtitleStyle();
            Intent i = new Intent(this, SubtitleEditorActivity.class);
            i.putExtra(SubtitleEditorActivity.EXTRA_MEDIA_URI, mediaUri.toString());
            i.putExtra(SubtitleEditorActivity.EXTRA_SIZE_PERCENT, currentSizePercentX10());
            i.putExtra(SubtitleEditorActivity.EXTRA_POSITION_PERCENT, currentPositionPercent());
            i.putExtra(SubtitleEditorActivity.EXTRA_BACKGROUND, subtitleBackground.isChecked());
            i.putExtra(SubtitleEditorActivity.EXTRA_TRACK_WORD, trackWord.isChecked());
            i.putExtra(SubtitleEditorActivity.EXTRA_WORD_SCALE, currentWordScale());
            i.putExtra(SubtitleEditorActivity.EXTRA_HIGHLIGHT, wordHighlightSeek.getProgress());
            i.putExtra(SubtitleEditorActivity.EXTRA_HIGHLIGHT_COLOR, currentHighlightColor());
            startActivityForResult(i, EDIT_SUBTITLES);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Nie można otworzyć edytora napisów", Toast.LENGTH_LONG).show();
        }
    }

    private void openTranscriptEditor() {
        if (mediaUri == null || !TranscriptionService.hasTranscriptFor(this, mediaUri)) {
            Toast.makeText(this, "Najpierw wykonaj transkrypcję", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            saveSubtitleStyle();
            Intent i = new Intent(this, TranscriptEditorActivity.class);
            i.putExtra(TranscriptEditorActivity.EXTRA_MEDIA_URI, mediaUri.toString());
            i.putExtra(TranscriptEditorActivity.EXTRA_SIZE_PERCENT, currentSizePercentX10());
            i.putExtra(TranscriptEditorActivity.EXTRA_WORD_SCALE, effectiveWordScale());
            startActivityForResult(i, EDIT_TRANSCRIPT);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Nie można otworzyć edycji transkrypcji", Toast.LENGTH_LONG).show();
        }
    }

    private float currentWordScale() {
        return 1f + wordScaleSeek.getProgress() / 100f;
    }

    private int currentSizePercentX10() {
        return sizeSeek.getProgress() + SubtitleStyle.SIZE_X10_MIN;
    }

    private int currentPositionPercent() {
        return positionSeek.getProgress() + SubtitleStyle.POSITION_PERCENT_MIN;
    }

    private float currentRelativeSize() {
        return SubtitleStyle.relativeTextSize(currentSizePercentX10());
    }

    private int currentHighlightColor() {
        return SubtitleStyle.highlightColor(highlightColorIndex);
    }

    private float effectiveWordScale() {
        return trackWord.isChecked() ? currentWordScale() : 1f;
    }

    private void updateStyleLabels() {
        if (sizeLabel != null && sizeSeek != null) {
            sizeLabel.setText(String.format(Locale.ROOT,
                    "Rozmiar napisów: %.1f%% wysokości obrazu", currentSizePercentX10() / 10f));
        }
        if (positionLabel != null && positionSeek != null) {
            positionLabel.setText(String.format(Locale.ROOT,
                    "Położenie napisów: %d%% od góry obrazu (0%% = góra, 100%% = dół)",
                    currentPositionPercent()));
        }
        if (wordScaleLabel != null && wordScaleSeek != null) {
            wordScaleLabel.setText("Powiększenie aktywnego słowa: " + (100 + wordScaleSeek.getProgress()) + "%");
        }
        if (wordHighlightLabel != null && wordHighlightSeek != null) {
            wordHighlightLabel.setText("Zaznaczenie aktywnego słowa: " + wordHighlightSeek.getProgress() + "%");
        }
        if (wordScaleSeek != null && trackWord != null) wordScaleSeek.setEnabled(!burnRunning && trackWord.isChecked());
        if (wordHighlightSeek != null && trackWord != null) wordHighlightSeek.setEnabled(!burnRunning && trackWord.isChecked());
    }

    private LinearLayout buildColorSwatches() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        highlightSwatches = new TextView[SubtitleStyle.HIGHLIGHT_COLORS.length];
        for (int i = 0; i < highlightSwatches.length; i++) {
            final int index = i;
            TextView swatch = new TextView(this);
            swatch.setGravity(Gravity.CENTER);
            swatch.setTextSize(18);
            swatch.setContentDescription(SubtitleStyle.HIGHLIGHT_COLOR_NAMES[i]);
            swatch.setOnClickListener(v -> selectHighlightColor(index));
            highlightSwatches[i] = swatch;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(swatch, params);
        }
        return row;
    }

    private void selectHighlightColor(int index) {
        highlightColorIndex = index;
        refreshHighlightSwatches();
        saveSubtitleStyle();
    }

    private void refreshHighlightSwatches() {
        if (highlightSwatches == null) return;
        for (int i = 0; i < highlightSwatches.length; i++) {
            TextView swatch = highlightSwatches[i];
            int color = SubtitleStyle.HIGHLIGHT_COLORS[i];
            int contrast = SubtitleStyle.isBright(color) ? Color.BLACK : Color.WHITE;
            boolean selected = i == highlightColorIndex;
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color);
            shape.setCornerRadius(dp(8));
            shape.setStroke(selected ? dp(3) : dp(1), selected ? contrast : 0x33808080);
            swatch.setBackground(shape);
            swatch.setText(selected ? "✓" : "");
            swatch.setTextColor(contrast);
            swatch.setEnabled(!burnRunning);
            swatch.setAlpha(burnRunning ? 0.4f : 1f);
        }
        if (highlightColorLabel != null) {
            highlightColorLabel.setText("Kolor wyróżnienia: "
                    + SubtitleStyle.HIGHLIGHT_COLOR_NAMES[highlightColorIndex]);
        }
    }

    private String readSmallText(File file) throws IOException {
        byte[] data = new byte[(int) Math.min(file.length(), 30000L)];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = in.read(data);
            if (n <= 0) return "";
            return new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void updateSelectionLabels() {
        mediaLabel.setText(mediaUri == null ? "Nie wybrano pliku" : displayName(mediaUri));
        modelLabel.setText(modelUri == null ? "Nie wybrano modelu" : displayName(modelUri));
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? uri.toString() : uri.getLastPathSegment();
    }

    private boolean isVideo(Uri uri) {
        if (uri == null) return false;
        String type;
        try {
            type = getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            type = null;
        }
        if (type != null && type.startsWith("video/")) return true;
        String name = displayName(uri).toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov");
    }

    private String defaultOutputName(String suffix) {
        String name = mediaUri == null ? "transkrypcja" : displayName(mediaUri);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + suffix;
    }

    private interface CopyProgress { void onProgress(int percent); }

    private void copyFileToUri(File source, Uri target, CopyProgress progressCallback) throws IOException {
        long total = Math.max(1L, source.length());
        long done = 0L;
        int lastPercent = -1;
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("Nie można otworzyć pliku docelowego");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                if (progressCallback != null) {
                    int percent = (int) Math.min(100L, done * 100L / total);
                    if (percent != lastPercent && (percent == 100 || percent - lastPercent >= 2)) {
                        lastPercent = percent;
                        progressCallback.onProgress(percent);
                    }
                }
            }
        }
    }

    @Override
    public void onState(TranscriptionService.Snapshot snapshot) {
        runOnUiThread(() -> {
            transcriptionRunning = snapshot.running;
            String status = formatTranscriptionStatus(snapshot);
            if (snapshot.error != null && !snapshot.error.isEmpty()) status += ": " + snapshot.error;
            statusLabel.setText(status);
            progress.setProgress(snapshot.progress);
            preview.setText(snapshot.preview == null ? "" : snapshot.preview);
            updateButtons();
        });
    }

    private String formatTranscriptionStatus(TranscriptionService.Snapshot snapshot) {
        StringBuilder text = new StringBuilder(snapshot.status == null ? "" : snapshot.status);
        if (snapshot.running) {
            text.append("\nPostęp: ")
                    .append(String.format(Locale.ROOT, "%.1f", snapshot.preciseProgress))
                    .append("%");
            if (snapshot.etaMs >= 0L) {
                text.append(" • pozostało około ").append(formatDuration(snapshot.etaMs));
            }
            if (snapshot.realtimeFactor > 0f) {
                text.append(" • ").append(String.format(Locale.ROOT,
                        "%.2f× RT", snapshot.realtimeFactor));
            }
        } else if (snapshot.finished) {
            text.append("\nCzas: ").append(formatDuration(snapshot.elapsedMs))
                    .append(" • prędkość: ")
                    .append(String.format(Locale.ROOT, "%.2f× czasu rzeczywistego",
                            snapshot.realtimeFactor))
                    .append("\nJednostka: ").append(snapshot.computeBackend);
        }
        return text.toString();
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 0L) return "--:--";
        long totalSeconds = Math.max(0L, Math.round(durationMs / 1000d));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    @Override
    public void onBurnState(SubtitleBurnService.Snapshot snapshot) {
        runOnUiThread(() -> {
            burnRunning = snapshot.running;
            String status = snapshot.status;
            if (snapshot.error != null && !snapshot.error.isEmpty()) status += ": " + snapshot.error;
            burnStatusLabel.setText("Eksport filmu: " + status);
            burnProgress.setProgress(snapshot.progress);
            updateButtons();
        });
    }

    private void updateButtons() {
        startButton.setEnabled(!transcriptionRunning && !burnRunning && mediaUri != null && modelUri != null);
        cancelButton.setEnabled(transcriptionRunning);

        File txt = new File(getFilesDir(), "last_transcript.txt");
        File srt = new File(getFilesDir(), "last_transcript.srt");
        saveTxtButton.setEnabled(!transcriptionRunning && txt.isFile() && txt.length() > 0);
        saveSrtButton.setEnabled(!transcriptionRunning && srt.isFile() && srt.length() > 0);

        boolean transcriptMatches = TranscriptionService.hasTranscriptFor(this, mediaUri);
        editTranscriptButton.setEnabled(!transcriptionRunning && !burnRunning && transcriptMatches);
        editSubtitlesButton.setEnabled(!transcriptionRunning && !burnRunning && isVideo(mediaUri) && transcriptMatches);
        burnButton.setEnabled(!transcriptionRunning && !burnRunning && isVideo(mediaUri) && transcriptMatches);
        cancelBurnButton.setEnabled(burnRunning);
        subtitleBackground.setEnabled(!burnRunning);
        trackWord.setEnabled(!burnRunning);
        wordScaleSeek.setEnabled(!burnRunning && trackWord.isChecked());
        wordHighlightSeek.setEnabled(!burnRunning && trackWord.isChecked());
        sizeSeek.setEnabled(!burnRunning);
        positionSeek.setEnabled(!burnRunning);
        if (highlightSwatches != null) {
            for (TextView swatch : highlightSwatches) {
                swatch.setEnabled(!burnRunning);
                swatch.setAlpha(burnRunning ? 0.4f : 1f);
            }
        }
        saveMp4Button.setEnabled(!burnRunning && SubtitleBurnService.hasRenderFor(this, mediaUri));
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
