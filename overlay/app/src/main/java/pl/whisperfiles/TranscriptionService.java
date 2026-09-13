package pl.whisperfiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.media3.common.util.UnstableApi;
import android.provider.OpenableColumns;

import com.whispercpp.java.whisper.WhisperContext;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class TranscriptionService extends Service {
    static final String ACTION_START = "pl.whisperfiles.START";
    static final String EXTRA_MEDIA_URI = "media_uri";
    static final String EXTRA_MODEL_URI = "model_uri";

    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "whisper_transcription";
    private static final String PREFS = "whisper_files";
    private static final String PREF_CACHED_MODEL_URI = "cached_model_uri";
    private static final String PREF_LAST_TRANSCRIPT_MEDIA_URI = "last_transcript_media_uri";

    public interface Listener {
        void onState(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String status;
        public final int progress;
        public final String preview;
        public final boolean running;
        public final boolean finished;
        public final String error;

        Snapshot(String status, int progress, String preview, boolean running, boolean finished, String error) {
            this.status = status;
            this.progress = progress;
            this.preview = preview;
            this.running = running;
            this.finished = finished;
            this.error = error;
        }
    }

    public final class LocalBinder extends Binder {
        public TranscriptionService getService() { return TranscriptionService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile WhisperContext activeContext;
    private volatile Listener listener;
    private volatile boolean running;
    private volatile boolean finished;
    private volatile int progress;
    private volatile String status = "Gotowy";
    private volatile String error = "";
    private final StringBuilder preview = new StringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastProgressPublishAt;
    private int lastPublishedProgress = -1;
    private int lastNotificationProgress = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Transkrypcja Whisper", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) return START_NOT_STICKY;

        final String mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI);
        final String modelUri = intent.getStringExtra(EXTRA_MODEL_URI);
        if (mediaUri == null || modelUri == null) return START_NOT_STICKY;

        running = true;
        finished = false;
        progress = 0;
        lastProgressPublishAt = 0L;
        lastPublishedProgress = -1;
        lastNotificationProgress = -1;
        error = "";
        status = "Uruchamianie…";
        synchronized (preview) { preview.setLength(0); }
        cancelled.set(false);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_LAST_TRANSCRIPT_MEDIA_URI).apply();
        File wordsFile = SubtitleTimelineStore.file(this);
        if (wordsFile.exists()) wordsFile.delete();

        startForeground(NOTIFICATION_ID, buildNotification("Uruchamianie…", 0, true));
        publish();
        worker.submit(() -> runTranscription(Uri.parse(mediaUri), Uri.parse(modelUri)));
        return START_NOT_STICKY;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            Snapshot state = snapshot();
            mainHandler.post(() -> {
                Listener current = this.listener;
                if (current != null) current.onState(state);
            });
        }
    }

    public void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    public Snapshot snapshot() {
        String p;
        synchronized (preview) { p = preview.toString(); }
        return new Snapshot(status, progress, p, running, finished, error);
    }

    public void cancel() {
        cancelled.set(true);
        status = "Anulowanie…";
        WhisperContext ctx = activeContext;
        if (ctx != null) ctx.requestAbort();
        publish();
    }


    public static boolean hasTranscriptFor(android.content.Context context, Uri mediaUri) {
        if (context == null || mediaUri == null) return false;
        String stored = context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LAST_TRANSCRIPT_MEDIA_URI, "");
        File srt = new File(context.getFilesDir(), "last_transcript.srt");
        return mediaUri.toString().equals(stored) && srt.isFile() && srt.length() > 0;
    }

    public File getTxtResultFile() { return new File(getFilesDir(), "last_transcript.txt"); }
    public File getSrtResultFile() { return new File(getFilesDir(), "last_transcript.srt"); }

    private void runTranscription(Uri mediaUri, Uri modelUri) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperFiles:Transcription");
            wakeLock.acquire();

            publishProgress(2, "Przygotowanie wejścia…");
            File modelFile = ensureModelCached(modelUri);
            if (cancelled.get()) throw new CancelledException();

            publishProgress(11, "Ładowanie modelu…");

            try (WhisperContext whisper = WhisperContext.create(modelFile.getAbsolutePath());
                 BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(getTxtResultFile(), false), StandardCharsets.UTF_8));
                 BufferedWriter srt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(getSrtResultFile(), false), StandardCharsets.UTF_8))) {

                activeContext = whisper;
                publishProgress(15, "Transkrypcja…");
                final int[] srtIndex = {1};
                final long[] lastSrtEndMs = {0L};
                final ArrayList<SubtitleWord> allWords = new ArrayList<>();

                AudioTranscriber.transcribe(
                        getContentResolver(), mediaUri, whisper, cancelled,
                        new AudioTranscriber.Callback() {
                            @Override
                            public void onProgress(long processedSamples, long totalSamples, String newStatus) {
                                int mapped = mapTranscriptionProgress(processedSamples, totalSamples);
                                publishProgress(mapped, newStatus);
                            }

                            @Override
                            public void onSegment(long startMs, long endMs, String text, List<SubtitleWord> words) throws IOException {
                                if (words != null) allWords.addAll(words);
                                txt.write(text);
                                txt.newLine();
                                txt.flush();

                                List<SubtitleCue> cues = SubtitleFormatter.format(startMs, endMs, text);
                                for (SubtitleCue cue : cues) {
                                    long cueStart = Math.max(cue.startMs, lastSrtEndMs[0]);
                                    long cueEnd = cue.endMs;
                                    if (cueEnd <= cueStart + 80L) continue;
                                    srt.write(Integer.toString(srtIndex[0]++));
                                    srt.newLine();
                                    srt.write(formatSrtTime(cueStart) + " --> " + formatSrtTime(cueEnd));
                                    srt.newLine();
                                    srt.write(cue.text);
                                    srt.newLine();
                                    srt.newLine();
                                    lastSrtEndMs[0] = cueEnd;
                                }
                                srt.flush();

                                appendPreview(text);
                                publish();
                            }
                        });
                if (cancelled.get()) throw new CancelledException();
                publishProgress(96, "Budowanie napisów…");
                if (!cancelled.get() && !allWords.isEmpty()) {
                    SubtitleTimelineStore.write(this, allWords);
                }
                publishProgress(99, "Zapisywanie napisów…");
            } finally {
                activeContext = null;
            }

            if (cancelled.get()) throw new CancelledException();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_LAST_TRANSCRIPT_MEDIA_URI, mediaUri.toString()).apply();
            progress = 100;
            status = "Gotowe — transkrypcja i czasy słów utworzone";
            finished = true;
            running = false;
            publish();
            stopForeground(false);
            updateNotification("Transkrypcja zakończona", 100, false);

        } catch (CancelledException e) {
            status = "Anulowano";
            running = false;
            finished = false;
            publish();
            stopForeground(false);
            updateNotification("Transkrypcja anulowana", progress, false);
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            status = "Błąd";
            running = false;
            finished = false;
            publish();
            stopForeground(false);
            updateNotification("Błąd transkrypcji", progress, false);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(false);
            stopSelf();
        }
    }

    private File ensureModelCached(Uri modelUri) throws Exception {
        File model = new File(getFilesDir(), "whisper-model.bin");
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String previousUri = prefs.getString(PREF_CACHED_MODEL_URI, "");
        if (modelUri.toString().equals(previousUri) && model.isFile() && model.length() > 1024 * 1024) {
            publishProgress(8, "Ładowanie modelu…");
            return model;
        }

        long total = querySize(getContentResolver(), modelUri);
        File temp = new File(getFilesDir(), "whisper-model.tmp");
        if (temp.exists()) temp.delete();

        publishProgress(5, "Kopiowanie modelu…");

        long copied = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream raw = getContentResolver().openInputStream(modelUri);
             BufferedInputStream in = raw == null ? null : new BufferedInputStream(raw, buffer.length);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Nie można otworzyć modelu");
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (cancelled.get()) throw new CancelledException();
                out.write(buffer, 0, n);
                copied += n;
                if (total > 0) {
                    int p = 5 + (int) Math.min(5d, copied * 5d / total);
                    publishProgress(p, "Kopiowanie modelu…");
                }
            }
            out.getFD().sync();
        } catch (Throwable t) {
            temp.delete();
            throw t;
        }

        if (model.exists() && !model.delete()) throw new IOException("Nie można zastąpić cache modelu");
        if (!temp.renameTo(model)) throw new IOException("Nie można zapisać cache modelu");
        prefs.edit().putString(PREF_CACHED_MODEL_URI, modelUri.toString()).apply();
        return model;
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1L;
    }

    private void appendPreview(String text) {
        synchronized (preview) {
            if (preview.length() > 0) preview.append('\n');
            preview.append(text);
            final int max = 30000;
            if (preview.length() > max) preview.delete(0, preview.length() - max);
        }
    }

    private int mapTranscriptionProgress(long processedSamples, long totalSamples) {
        if (totalSamples <= 0L || processedSamples < 0L) {
            // No duration metadata: one conservative step per completed chunk, never 100%.
            return Math.min(94, Math.max(15, progress + 1));
        }
        long safeProcessed = Math.max(0L, Math.min(processedSamples, totalSamples));
        float fraction = totalSamples > 0L
                ? (float) safeProcessed / (float) totalSamples : 0f;
        fraction = Math.max(0f, Math.min(1f, fraction));
        int mapped = 15 + Math.round(80f * fraction);
        return Math.min(95, Math.max(15, mapped));
    }

    private void publishProgress(int candidate, String newStatus) {
        int clamped = Math.max(0, Math.min(99, candidate));
        progress = Math.max(progress, clamped);
        status = newStatus == null ? "Transkrypcja…" : newStatus;
        long now = System.currentTimeMillis();
        if (progress > lastPublishedProgress || now - lastProgressPublishAt >= 200L) {
            lastPublishedProgress = progress;
            lastProgressPublishAt = now;
            publish();
        }
    }

    private void publish() {
        Snapshot state = snapshot();
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(state);
        });
        if (running && (progress != lastNotificationProgress)) {
            if (progress == 0 || progress == 100 || Math.abs(progress - lastNotificationProgress) >= 2) {
                lastNotificationProgress = progress;
                updateNotification(status, progress, true);
            }
        }
    }

    private Notification buildNotification(String text, int percent, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Whisper Files")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        return builder.build();
    }

    private void updateNotification(String text, int percent, boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text, percent, ongoing));
    }

    private static String formatSrtTime(long ms) {
        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;
        long minutes = ms / 60_000L;
        ms %= 60_000L;
        long seconds = ms / 1000L;
        long millis = ms % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    @Override
    public void onDestroy() {
        cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class CancelledException extends Exception {}
}
