package pl.whisperfiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.Collections;
import java.util.List;

@UnstableApi
public final class SubtitleBurnService extends Service {
    static final String ACTION_START = "pl.whisperfiles.BURN_START";
    static final String EXTRA_MEDIA_URI = "media_uri";
    static final String EXTRA_POSITION_PERCENT = "subtitle_position_percent";
    static final String EXTRA_SIZE_PERCENT = "subtitle_size_percent";
    static final String EXTRA_BACKGROUND = "subtitle_background";
    static final String EXTRA_TRACK_WORD = "track_word";
    static final String EXTRA_WORD_SCALE = "word_scale";
    static final String EXTRA_HIGHLIGHT = "word_highlight";
    static final String EXTRA_HIGHLIGHT_COLOR = "word_highlight_color";

    private static final int NOTIFICATION_ID = 4108;
    private static final String CHANNEL_ID = "whisper_video_export";
    private static final String PREFS = "whisper_files_video";
    private static final String PREF_LAST_RENDER_MEDIA_URI = "last_render_media_uri";

    public interface Listener {
        void onBurnState(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String status;
        public final int progress;
        public final boolean running;
        public final boolean finished;
        public final String error;

        Snapshot(String status, int progress, boolean running, boolean finished, String error) {
            this.status = status;
            this.progress = progress;
            this.running = running;
            this.finished = finished;
            this.error = error;
        }
    }

    public final class LocalBinder extends Binder {
        public SubtitleBurnService getService() { return SubtitleBurnService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ProgressHolder progressHolder = new ProgressHolder();

    private volatile Listener listener;
    private Transformer transformer;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private boolean finished;
    private int progress;
    private String status = "Gotowy do eksportu";
    private String error = "";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Eksport filmu z napisami", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) return START_NOT_STICKY;

        String media = intent.getStringExtra(EXTRA_MEDIA_URI);
        if (media == null) return START_NOT_STICKY;
        int sizePercentX10 = intent.getIntExtra(EXTRA_SIZE_PERCENT, SubtitleStyle.SIZE_X10_DEFAULT);
        int positionPercent = intent.getIntExtra(EXTRA_POSITION_PERCENT,
                SubtitleStyle.POSITION_PERCENT_DEFAULT);
        boolean background = intent.getBooleanExtra(EXTRA_BACKGROUND, false);
        boolean trackWord = intent.getBooleanExtra(EXTRA_TRACK_WORD, true);
        float wordScale = intent.getFloatExtra(EXTRA_WORD_SCALE, 1.12f);
        int highlight = intent.getIntExtra(EXTRA_HIGHLIGHT, 55);
        int highlightColor = intent.getIntExtra(EXTRA_HIGHLIGHT_COLOR,
                SubtitleStyle.defaultHighlightColor());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_LAST_RENDER_MEDIA_URI).apply();

        startBurn(Uri.parse(media), sizePercentX10, positionPercent, background, trackWord,
                wordScale, highlight, highlightColor);
        return START_NOT_STICKY;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onBurnState(snapshot());
    }

    public void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    public Snapshot snapshot() {
        return new Snapshot(status, progress, running, finished, error);
    }

    public void cancel() {
        mainHandler.post(() -> {
            if (transformer != null && running) {
                status = "Anulowanie eksportu…";
                publish();
                transformer.cancel();
                transformer = null;
                running = false;
                finished = false;
                status = "Eksport anulowany";
                File out = getOutputFile();
                if (out.exists()) out.delete();
                finishForeground("Eksport anulowany");
                publish();
                releaseWakeLock();
                stopSelf();
            }
        });
    }

    public File getOutputFile() {
        return outputFile(this);
    }

    public static File outputFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = context.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "WhisperFiles_subtitled.mp4");
    }

    public static boolean hasRenderFor(Context context, Uri mediaUri) {
        if (context == null || mediaUri == null) return false;
        String stored = context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LAST_RENDER_MEDIA_URI, "");
        File file = outputFile(context);
        return mediaUri.toString().equals(stored) && file.isFile() && file.length() > 0;
    }

    private void startBurn(Uri mediaUri, int sizePercentX10, int positionPercent,
                           boolean drawBackground, boolean trackWord, float wordScale,
                           int highlightStrength, int highlightColor) {
        try {
            List<SubtitleWord> words = SubtitleProject.loadWords(this);
            if (words.isEmpty()) throw new IllegalStateException("Brak osi słów / napisów");

            File output = getOutputFile();
            if (output.exists() && !output.delete()) {
                throw new IllegalStateException("Nie można zastąpić poprzedniego filmu");
            }

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperFiles:SubtitleExport");
            wakeLock.acquire();

            running = true;
            finished = false;
            progress = 0;
            error = "";
            status = "Przygotowanie filmu…";
            startForeground(NOTIFICATION_ID, buildNotification(status, 0, true));
            publish();

            float relativeSize = SubtitleStyle.relativeTextSize(sizePercentX10);
            float positionFraction = SubtitleStyle.positionFraction(positionPercent);
            SubtitleCanvasOverlay subtitleOverlay = new SubtitleCanvasOverlay(
                    words, positionFraction, relativeSize, drawBackground,
                    trackWord, wordScale, highlightStrength, highlightColor);
            OverlayEffect overlayEffect = new OverlayEffect(Collections.singletonList(subtitleOverlay));
            Effects effects = new Effects(
                    Collections.emptyList(), Collections.<Effect>singletonList(overlayEffect));
            EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(mediaUri))
                    .setEffects(effects)
                    .build();

            transformer = new Transformer.Builder(this)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(PREF_LAST_RENDER_MEDIA_URI, mediaUri.toString()).apply();
                            transformer = null;
                            running = false;
                            finished = true;
                            progress = 100;
                            status = "Film z napisami gotowy";
                            finishForeground(status);
                            publish();
                            releaseWakeLock();
                            stopSelf();
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException exception) {
                            transformer = null;
                            running = false;
                            finished = false;
                            error = exception.getMessage() == null
                                    ? exception.getClass().getSimpleName() : exception.getMessage();
                            status = "Błąd eksportu";
                            if (output.exists()) output.delete();
                            finishForeground(status);
                            publish();
                            releaseWakeLock();
                            stopSelf();
                        }
                    })
                    .build();

            transformer.start(item, output.getAbsolutePath());
            mainHandler.post(progressPoll);
        } catch (Throwable t) {
            running = false;
            finished = false;
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            status = "Błąd eksportu";
            publish();
            releaseWakeLock();
            stopForeground(false);
            stopSelf();
        }
    }

    private final Runnable progressPoll = new Runnable() {
        @Override
        public void run() {
            Transformer t = transformer;
            if (t == null || !running) return;
            try {
                int state = t.getProgress(progressHolder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress = Math.max(0, Math.min(99, progressHolder.progress));
                    status = "Wypalanie napisów… " + progress + "%";
                    publish();
                    updateNotification(status, progress, true);
                } else if (state == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                    status = "Analiza filmu…";
                    publish();
                }
            } catch (Throwable ignored) {}
            if (transformer != null && running) mainHandler.postDelayed(this, 500L);
        }
    };

    private void publish() {
        Listener l = listener;
        if (l != null) l.onBurnState(snapshot());
    }

    private Notification buildNotification(String text, int percent, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Whisper Files — film z napisami")
                .setContentText(text)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        return builder.build();
    }

    private void updateNotification(String text, int percent, boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text, percent, ongoing));
    }

    private void finishForeground(String text) {
        stopForeground(false);
        updateNotification(text, 100, false);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(progressPoll);
        releaseWakeLock();
        super.onDestroy();
    }
}
