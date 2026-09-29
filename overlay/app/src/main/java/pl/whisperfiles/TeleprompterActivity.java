package pl.whisperfiles;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class TeleprompterActivity extends ComponentActivity {
    public static final String EXTRA_SCRIPT = "teleprompter_script";
    private static final int REQUEST_PERMISSIONS = 4101;
    private static final int WINDOW_WORDS = 10;

    private PreviewView cameraPreview;
    private TextView promptView;
    private TextView statusView;
    private Button recordButton;

    private String[] scriptWords = new String[0];
    private int wordCursor;
    private int segmentMaxWords;

    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private boolean recordingStarted;
    private boolean stopping;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean speechTracking;
    private final Runnable restartRecognition = this::startListeningSegment;

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
                statusView.setText("Ręczne przesunięcie +1");
            }
        });
        top.addView(promptView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFDDDDDD);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(8), 0, 0);
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
            segmentMaxWords = 0;
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

    private void startSpeechTracking() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.setText("Nagrywanie • rozpoznawanie mowy niedostępne; dotknij tekstu, aby przesuwać ręcznie");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    statusView.setText("Nagrywanie • teleprompter śledzi tempo mowy");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) {
                    if (!speechTracking || !recordingStarted || stopping) return;
                    statusView.setText("Nagrywanie • ponawiam rozpoznawanie mowy…");
                    scheduleRecognitionRestart(error == SpeechRecognizer.ERROR_AUDIO ? 900L : 300L);
                }
                @Override public void onResults(Bundle results) {
                    consumeRecognition(results);
                    if (speechTracking && recordingStarted && !stopping) scheduleRecognitionRestart(150L);
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    consumeRecognition(partialResults);
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });

            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            speechTracking = true;
            startListeningSegment();
        } catch (RuntimeException e) {
            statusView.setText("Nagrywanie • brak śledzenia mowy; dotknij tekstu, aby przesuwać ręcznie");
            stopSpeechTracking();
        }
    }

    private void startListeningSegment() {
        if (!speechTracking || speechRecognizer == null || speechIntent == null ||
                !recordingStarted || stopping) return;
        segmentMaxWords = 0;
        try {
            speechRecognizer.startListening(speechIntent);
        } catch (RuntimeException e) {
            scheduleRecognitionRestart(700L);
        }
    }

    private void consumeRecognition(Bundle bundle) {
        if (bundle == null || !recordingStarted || stopping) return;
        ArrayList<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (results == null || results.isEmpty()) return;
        int count = splitWords(results.get(0)).length;
        if (count <= segmentMaxWords) return;
        int delta = count - segmentMaxWords;
        segmentMaxWords = count;
        advanceWords(delta);
        statusView.setText("Nagrywanie • rozpoznano ok. " + wordCursor + " / " + scriptWords.length + " słów");
    }

    private void scheduleRecognitionRestart(long delayMs) {
        if (promptView == null) return;
        promptView.removeCallbacks(restartRecognition);
        promptView.postDelayed(restartRecognition, delayMs);
    }

    private void stopSpeechTracking() {
        speechTracking = false;
        if (promptView != null) promptView.removeCallbacks(restartRecognition);
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (RuntimeException ignored) {}
            try { speechRecognizer.destroy(); } catch (RuntimeException ignored) {}
        }
        speechRecognizer = null;
        speechIntent = null;
        segmentMaxWords = 0;
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
        int end = Math.min(scriptWords.length, wordCursor + WINDOW_WORDS);
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
