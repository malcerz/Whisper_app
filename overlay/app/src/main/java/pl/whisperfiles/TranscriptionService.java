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
import android.os.IBinder;
import android.os.PowerManager;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TranscriptionService extends Service {
    static final String ACTION_START = "pl.whisperfiles.START";
    static final String EXTRA_MEDIA_URI = "media_uri";
    static final String EXTRA_MODEL_URI = "model_uri";

    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "whisper_transcription";
    private static final String PREFS = "whisper_files";
    private static final String PREF_CACHED_MODEL_URI = "cached_model_uri";

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
        error = "";
        status = "Uruchamianie…";
        synchronized (preview) { preview.setLength(0); }
        cancelled.set(false);

        startForeground(NOTIFICATION_ID, buildNotification("Uruchamianie…", 0, true));
        publish();
        worker.submit(() -> runTranscription(Uri.parse(mediaUri), Uri.parse(modelUri)));
        return START_NOT_STICKY;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onState(snapshot());
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

    public File getTxtResultFile() { return new File(getFilesDir(), "last_transcript.txt"); }
    public File getSrtResultFile() { return new File(getFilesDir(), "last_transcript.srt"); }

    private void runTranscription(Uri mediaUri, Uri modelUri) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperFiles:Transcription");
            wakeLock.acquire();

            File modelFile = ensureModelCached(modelUri);
            if (cancelled.get()) throw new CancelledException();

            status = "Ładowanie modelu…";
            progress = 0;
            publish();

            try (WhisperContext whisper = WhisperContext.create(modelFile.getAbsolutePath());
                 BufferedWriter txt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(getTxtResultFile(), false), StandardCharsets.UTF_8));
                 BufferedWriter srt = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(getSrtResultFile(), false), StandardCharsets.UTF_8))) {

                activeContext = whisper;
                final int[] srtIndex = {1};

                AudioTranscriber.transcribe(
                        getContentResolver(), mediaUri, whisper, cancelled,
                        new AudioTranscriber.Callback() {
                            @Override
                            public void onProgress(int percent, String newStatus) {
                                progress = percent;
                                status = newStatus;
                                publish();
                            }

                            @Override
                            public void onSegment(long startMs, long endMs, String text) throws IOException {
                                txt.write(text);
                                txt.newLine();
                                txt.flush();

                                srt.write(Integer.toString(srtIndex[0]++));
                                srt.newLine();
                                srt.write(formatSrtTime(startMs) + " --> " + formatSrtTime(endMs));
                                srt.newLine();
                                srt.write(text);
                                srt.newLine();
                                srt.newLine();
                                srt.flush();

                                appendPreview(text);
                                publish();
                            }
                        });
            } finally {
                activeContext = null;
            }

            if (cancelled.get()) throw new CancelledException();
            progress = 100;
            status = "Gotowe";
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
            return model;
        }

        long total = querySize(getContentResolver(), modelUri);
        File temp = new File(getFilesDir(), "whisper-model.tmp");
        if (temp.exists()) temp.delete();

        status = "Kopiowanie modelu…";
        progress = 0;
        publish();

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
                    int p = (int) Math.min(99, copied * 100L / total);
                    status = "Kopiowanie modelu… " + p + "%";
                    publish();
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

    private void publish() {
        Listener l = listener;
        if (l != null) l.onState(snapshot());
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
