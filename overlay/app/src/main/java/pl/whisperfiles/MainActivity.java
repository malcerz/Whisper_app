package pl.whisperfiles;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class MainActivity extends Activity implements TranscriptionService.Listener {
    private static final int PICK_MEDIA = 1001;
    private static final int PICK_MODEL = 1002;
    private static final int SAVE_TXT = 1003;
    private static final int SAVE_SRT = 1004;
    private static final String PREFS = "whisper_files_ui";
    private static final String PREF_MEDIA = "media_uri";
    private static final String PREF_MODEL = "model_uri";

    private Uri mediaUri;
    private Uri modelUri;
    private TranscriptionService service;
    private boolean bound;

    private TextView mediaLabel;
    private TextView modelLabel;
    private TextView statusLabel;
    private TextView preview;
    private ProgressBar progress;
    private Button startButton;
    private Button cancelButton;
    private Button saveTxtButton;
    private Button saveSrtButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TranscriptionService.LocalBinder local = (TranscriptionService.LocalBinder) binder;
            service = local.getService();
            bound = true;
            service.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.clearListener(MainActivity.this);
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreSelections();
        updateSelectionLabels();
        updateButtons(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, TranscriptionService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (bound && service != null) service.clearListener(this);
        if (bound) unbindService(connection);
        bound = false;
        service = null;
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

        TextView title = text("Whisper Files", 28, true);
        root.addView(title);
        TextView subtitle = text("Offline: WAV + MP4 → polska transkrypcja. Bez mikrofonu.", 15, false);
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

        startButton = button("Transkrybuj");
        startButton.setOnClickListener(v -> startTranscription());
        root.addView(startButton);

        cancelButton = button("Anuluj");
        cancelButton.setOnClickListener(v -> {
            if (service != null) service.cancel();
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
        saveTxtButton.setOnClickListener(v -> createOutput(false));
        saveSrtButton.setOnClickListener(v -> createOutput(true));
        saves.addView(saveTxtButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        saves.addView(saveSrtButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(saves);

        TextView resultTitle = text("Podgląd wyniku", 18, true);
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

        Intent i = new Intent(this, TranscriptionService.class);
        i.setAction(TranscriptionService.ACTION_START);
        i.putExtra(TranscriptionService.EXTRA_MEDIA_URI, mediaUri.toString());
        i.putExtra(TranscriptionService.EXTRA_MODEL_URI, modelUri.toString());
        startForegroundService(i);
        statusLabel.setText("Uruchamianie…");
        updateButtons(true);
    }

    private void createOutput(boolean srt) {
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            updateButtons(service != null && service.snapshot().running);
            return;
        }

        if (requestCode == SAVE_TXT || requestCode == SAVE_SRT) {
            File source = new File(getFilesDir(), requestCode == SAVE_SRT
                    ? "last_transcript.srt" : "last_transcript.txt");
            try {
                copyFileToUri(source, uri);
                Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Błąd zapisu: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
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

    private String defaultOutputName(String extension) {
        String name = mediaUri == null ? "transkrypcja" : displayName(mediaUri);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + extension;
    }

    private void copyFileToUri(File source, Uri target) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("Nie można otworzyć pliku docelowego");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    @Override
    public void onState(TranscriptionService.Snapshot snapshot) {
        runOnUiThread(() -> {
            String status = snapshot.status;
            if (snapshot.error != null && !snapshot.error.isEmpty()) status += ": " + snapshot.error;
            statusLabel.setText(status + (snapshot.running ? "  " + snapshot.progress + "%" : ""));
            progress.setProgress(snapshot.progress);
            preview.setText(snapshot.preview == null ? "" : snapshot.preview);
            updateButtons(snapshot.running);
        });
    }

    private void updateButtons(boolean running) {
        startButton.setEnabled(!running && mediaUri != null && modelUri != null);
        cancelButton.setEnabled(running);
        File txt = new File(getFilesDir(), "last_transcript.txt");
        File srt = new File(getFilesDir(), "last_transcript.srt");
        saveTxtButton.setEnabled(!running && txt.isFile() && txt.length() > 0);
        saveSrtButton.setEnabled(!running && srt.isFile() && srt.length() > 0);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
