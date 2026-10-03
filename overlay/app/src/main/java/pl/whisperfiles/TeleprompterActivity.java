package pl.whisperfiles;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TeleprompterActivity extends ComponentActivity {
    public static final String EXTRA_SCRIPT = "teleprompter_script";
    public static final String EXTRA_WINDOW_WORDS = "teleprompter_window_words";

    private static final int REQUEST_PERMISSIONS = 4101;
    private static final int MIN_WINDOW_WORDS = 3;
    private static final int MAX_WINDOW_WORDS = 30;
    private static final int TRACK_SAMPLE_RATE = 16000;

    private PreviewView cameraPreview;
    private TextView promptView;
    private TextView statusView;
    private TextView wordCountView;
    private Button recordButton;

    private String[] scriptWords = new String[0];
    private int wordCursor;
    private int windowWords = HomeActivity.DEFAULT_WINDOW_WORDS;

    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private boolean recordingStarted;
    private boolean stopping;

    private final WordPulseDetector wordPulseDetector = new WordPulseDetector();
    private volatile boolean speechTracking;
    private AudioRecord speechAudioRecord;
    private Thread speechThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String script = getIntent().getStringExtra(EXTRA_SCRIPT);
        if (script == null) script = "";
        scriptWords = splitWords(script);
        if (scriptWords.length == 0) {
            Toast.makeText(this, "Brak tekstu telepromptera", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        int savedWindow = getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE)
                .getInt(HomeActivity.PREF_WINDOW_WORDS, HomeActivity.DEFAULT_WINDOW_WORDS);
        windowWords = clampWindowWords(getIntent().getIntExtra(EXTRA_WINDOW_WORDS, savedWindow));

        buildUi();
        updatePrompt();

        if (hasRequiredPermissions()) {
            startCamera();
        } else {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        cameraPreview = new PreviewView(this);
        cameraPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        cameraPreview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(cameraPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14), dp(16), dp(14), dp(12));
        top.setBackgroundColor(0xB8000000);

        promptView = new TextView(this);
        promptView.setTextColor(Color.WHITE);
        promptView.setTextSize(29);
        promptView.setGravity(Gravity.CENTER);
        promptView.setLineSpacing(0f, 1.08f);
        promptView.setOnClickListener(v -> {
            if (recordingStarted) {
                advanceWords(1);
                statusView.setText("Nagrywanie • ręczne przesunięcie +1");
            }
        });
        top.addView(promptView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER);
        countRow.setPadding(0, dp(8), 0, 0);

        Button minus = smallButton("−");
        minus.setOnClickListener(v -> setWindowWords(windowWords - 1));
        countRow.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(48)));

        wordCountView = new TextView(this);
        wordCountView.setTextColor(0xFFEEEEEE);
        wordCountView.setTextSize(14);
        wordCountView.setGravity(Gravity.CENTER);
        countRow.addView(wordCountView, new LinearLayout.LayoutParams(dp(126), dp(48)));

        Button plus = smallButton("+");
        plus.setOnClickListener(v -> setWindowWords(windowWords + 1));
        countRow.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(48)));
        top.addView(countRow);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFDDDDDD);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(6), 0, 0);
        statusView.setText("Przygotowanie kamery…");
        top.addView(statusView);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(top, topParams);

        recordButton = new Button(this);
        recordButton.setText("● START NAGRYWANIA");
        recordButton.setAllCaps(false);
        recordButton.setTextSize(17);
        recordButton.setEnabled(false);
        recordButton.setOnClickListener(v -> {
            if (recording == null) startRecording();
            else stopRecording();
        });
        FrameLayout.LayoutParams recordParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.BOTTOM);
        recordParams.setMargins(dp(20), dp(10), dp(20), dp(24));
        root.addView(recordButton, recordParams);

        setContentView(root);
        updateWordCountLabel();
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(20);
        return button;
    }

    private void setWindowWords(int value) {
        int next = clampWindowWords(value);
        if (next == windowWords) return;
        windowWords = next;
        getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE)
                .edit().putInt(HomeActivity.PREF_WINDOW_WORDS, windowWords).apply();
        updateWordCountLabel();
        updatePrompt();
    }

    private void updateWordCountLabel() {
        if (wordCountView != null) wordCountView.setText(windowWords + " słów");
    }

    private static int clampWindowWords(int value) {
        return Math.max(MIN_WINDOW_WORDS, Math.min(MAX_WINDOW_WORDS, value));
    }

    private void startCamera() {
        statusView.setText("Uruchamianie kamery…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview, videoCapture);
                    statusView.setText("Przednia kamera gotowa • stuknij START");
                } catch (RuntimeException frontError) {
                    provider.unbindAll();
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, videoCapture);
                    statusView.setText("Tylna kamera gotowa • stuknij START");
                }
                recordButton.setEnabled(true);
            } catch (Exception e) {
                statusView.setText("Błąd kamery: " + e.getMessage());
                recordButton.setEnabled(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startRecording() {
        if (videoCapture == null || recording != null) return;
        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
            return;
        }

        ContentValues values = new ContentValues();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "WhisperCaptions2_" + stamp);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WhisperCaptions2");
        }

        MediaStoreOutputOptions output = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();

        try {
            PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
            pending = pending.withAudioEnabled();
            recording = pending.start(ContextCompat.getMainExecutor(this), this::onVideoEvent);
            recordButton.setEnabled(false);
            statusView.setText("Uruchamianie nagrania…");
        } catch (SecurityException e) {
            Toast.makeText(this, "Brak uprawnień kamery lub mikrofonu", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            statusView.setText("Nie można rozpocząć nagrania: " + e.getMessage());
        }
    }

    private void onVideoEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            recordingStarted = true;
            stopping = false;
            recordButton.setEnabled(true);
            recordButton.setText("■ STOP I ZAPISZ MP4");
            wordCursor = 0;
            wordPulseDetector.reset();
            updatePrompt();
            startSpeechTracking();
            return;
        }

        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize done = (VideoRecordEvent.Finalize) event;
            stopSpeechTracking();
            Recording old = recording;
            recording = null;
            recordingStarted = false;
            stopping = false;
            if (old != null) {
                try { old.close(); } catch (RuntimeException ignored) {}
            }

            if (done.hasError()) {
                recordButton.setEnabled(true);
                recordButton.setText("● START NAGRYWANIA");
                statusView.setText("Błąd zapisu MP4: " + done.getError());
                return;
            }

            Uri uri = done.getOutputResults().getOutputUri();
            statusView.setText("MP4 zapisany w Movies/WhisperCaptions2");
            Intent result = new Intent();
            result.setData(uri);
            setResult(RESULT_OK, result);
            Toast.makeText(this, "Film zapisany. Teraz możesz wygenerować napisy.", Toast.LENGTH_LONG).show();
            promptView.postDelayed(this::finish, 600L);
        }
    }

    private void stopRecording() {
        if (recording == null || stopping) return;
        stopping = true;
        recordButton.setEnabled(false);
        statusView.setText("Kończenie i zapisywanie MP4…");
        stopSpeechTracking();
        try {
            recording.stop();
        } catch (RuntimeException e) {
            statusView.setText("Błąd zatrzymania nagrania: " + e.getMessage());
            stopping = false;
            recordButton.setEnabled(true);
        }
    }

    @SuppressLint("MissingPermission")
    private void startSpeechTracking() {
        stopSpeechTracking();
        if (!hasRequiredPermissions() || !recordingStarted || stopping) return;

        int minBytes = AudioRecord.getMinBufferSize(
                TRACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) minBytes = 4096;
        int bufferBytes = Math.max(4096, minBytes * 2);

        AudioRecord audio = createTrackingAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferBytes);
        if (audio == null) audio = createTrackingAudioRecord(MediaRecorder.AudioSource.MIC, bufferBytes);
        if (audio == null) {
            statusView.setText("Nagrywanie • licznik mowy niedostępny; dotknij tekstu, aby przesuwać ręcznie");
            return;
        }

        try {
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("mikrofon nie wystartował");
            }
        } catch (RuntimeException e) {
            try { audio.release(); } catch (RuntimeException ignored) {}
            statusView.setText("Nagrywanie • licznik mowy nie dostał mikrofonu; dotknij tekstu, aby przesuwać ręcznie");
            return;
        }

        speechAudioRecord = audio;
        speechTracking = true;
        wordPulseDetector.reset();
        statusView.setText("Nagrywanie • licznik mowy aktywny (treść słów jest ignorowana)");

        AudioRecord loopAudio = audio;
        speechThread = new Thread(() -> runSpeechLoop(loopAudio), "teleprompter-word-counter");
        speechThread.start();
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createTrackingAudioRecord(int source, int bufferBytes) {
        try {
            AudioRecord audio = new AudioRecord(
                    source,
                    TRACK_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                audio.release();
                return null;
            }
            return audio;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void runSpeechLoop(AudioRecord audio) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[320]; // 20 ms przy 16 kHz
        int silentFrames = 0;

        while (speechTracking && recordingStarted && !stopping && audio == speechAudioRecord) {
            int read;
            try {
                read = audio.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException e) {
                break;
            }
            if (read <= 0) {
                silentFrames++;
                if (silentFrames > 25) break;
                continue;
            }
            silentFrames = 0;

            int words = wordPulseDetector.accept(buffer, read, TRACK_SAMPLE_RATE);
            if (words > 0) {
                int delta = words;
                runOnUiThread(() -> {
                    if (!speechTracking || !recordingStarted || stopping) return;
                    advanceWords(delta);
                    statusView.setText("Nagrywanie • naliczono ok. " + wordCursor + " / " + scriptWords.length + " słów");
                });
            }
        }

        if (speechTracking && recordingStarted && !stopping) {
            runOnUiThread(() -> statusView.setText(
                    "Nagrywanie • licznik mowy stracił mikrofon; dotknij tekstu, aby przesuwać ręcznie"));
        }
    }

    private void stopSpeechTracking() {
        speechTracking = false;

        AudioRecord audio = speechAudioRecord;
        speechAudioRecord = null;
        if (audio != null) {
            try { audio.stop(); } catch (RuntimeException ignored) {}
            try { audio.release(); } catch (RuntimeException ignored) {}
        }

        Thread thread = speechThread;
        speechThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(250L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        wordPulseDetector.reset();
    }

    private void advanceWords(int count) {
        if (count <= 0 || scriptWords.length == 0) return;
        wordCursor = Math.min(scriptWords.length, wordCursor + count);
        updatePrompt();
    }

    private void updatePrompt() {
        if (promptView == null) return;
        if (wordCursor >= scriptWords.length) {
            promptView.setText("✓ KONIEC TEKSTU");
            return;
        }

        int end = Math.min(scriptWords.length, wordCursor + windowWords);
        StringBuilder shown = new StringBuilder();
        for (int i = wordCursor; i < end; i++) {
            if (shown.length() > 0) shown.append(' ');
            shown.append(scriptWords[i]);
        }
        promptView.setText(shown.toString());
    }

    private static String[] splitWords(String value) {
        if (value == null) return new String[0];
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("\\s+");
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT <= 28) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (hasRequiredPermissions()) {
            startCamera();
        } else {
            Toast.makeText(this, "Kamera i mikrofon są wymagane do nagrywania filmu", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (recording != null) {
            stopRecording();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopSpeechTracking();
        if (recording != null) {
            try { recording.stop(); } catch (RuntimeException ignored) {}
            try { recording.close(); } catch (RuntimeException ignored) {}
            recording = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
