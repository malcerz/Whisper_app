package pl.whisperfiles;

/**
 * Lekki licznik aktywności mowy dla telepromptera.
 *
 * Nie rozpoznaje treści słów. Parametry detekcji są regulowane z menu Opcje.
 */
final class WordPulseDetector {
    static final int DEFAULT_START_THRESHOLD = 55;
    static final int DEFAULT_NOISE_RATIO_PERCENT = 155;
    static final int DEFAULT_RELEASE_PERCENT = 95;
    static final int DEFAULT_RELEASE_MS = 60;
    static final int DEFAULT_ONSET_MS = 20;
    static final int DEFAULT_MIN_WORD_INTERVAL_MS = 140;
    static final int DEFAULT_CONTINUOUS_WORD_MS = 0; // 0 = wyłączone

    private static final int CALIBRATION_MS = 160;

    private volatile int minStartThreshold = DEFAULT_START_THRESHOLD;
    private volatile int noiseRatioPercent = DEFAULT_NOISE_RATIO_PERCENT;
    private volatile int releasePercent = DEFAULT_RELEASE_PERCENT;
    private volatile int releaseMs = DEFAULT_RELEASE_MS;
    private volatile int onsetMsRequired = DEFAULT_ONSET_MS;
    private volatile int minWordIntervalMs = DEFAULT_MIN_WORD_INTERVAL_MS;
    private volatile int continuousWordMs = DEFAULT_CONTINUOUS_WORD_MS;

    private double noiseFloor;
    private double envelope;
    private double calibrationMinimum = Double.MAX_VALUE;
    private int calibrationMs;

    private boolean speaking;
    private int onsetMs;
    private int quietMs;
    private int sinceLastWordMs = 10_000;

    private double lastLevel;
    private double lastStartThreshold;

    void configure(
            int startThreshold,
            int noiseRatioPercent,
            int releasePercent,
            int releaseMs,
            int onsetMs,
            int minWordIntervalMs,
            int continuousWordMs) {
        this.minStartThreshold = clampInt(startThreshold, 20, 300);
        this.noiseRatioPercent = clampInt(noiseRatioPercent, 100, 300);
        this.releasePercent = clampInt(releasePercent, 60, 100);
        this.releaseMs = clampInt(releaseMs, 20, 300);
        this.onsetMsRequired = clampInt(onsetMs, 20, 200);
        this.minWordIntervalMs = clampInt(minWordIntervalMs, 80, 800);
        this.continuousWordMs = continuousWordMs <= 0
                ? 0
                : clampInt(continuousWordMs, 250, 1500);
    }

    int accept(short[] samples, int length, int sampleRate) {
        if (samples == null || length <= 0 || sampleRate <= 0) return 0;

        long sum = 0L;
        int peak = 0;
        for (int i = 0; i < length; i++) {
            int value = samples[i];
            long v = value;
            sum += v * v;
            int abs = Math.abs(value);
            if (abs > peak) peak = abs;
        }

        double rms = Math.sqrt(sum / (double) length);
        double level = Math.max(rms, peak * 0.28);
        if (envelope == 0.0) envelope = level;
        else envelope = envelope * 0.20 + level * 0.80;

        int frameMs = Math.max(1, (int) Math.round(length * 1000.0 / sampleRate));
        sinceLastWordMs = Math.min(10_000, sinceLastWordMs + frameMs);
        lastLevel = envelope;

        if (calibrationMs < CALIBRATION_MS) {
            calibrationMinimum = Math.min(calibrationMinimum, envelope);
            calibrationMs += frameMs;
            if (calibrationMs >= CALIBRATION_MS) {
                noiseFloor = Math.max(18.0,
                        calibrationMinimum == Double.MAX_VALUE ? envelope : calibrationMinimum);
            }
            lastStartThreshold = startThreshold();
            return 0;
        }

        if (noiseFloor <= 0.0) noiseFloor = Math.max(18.0, envelope * 0.45);

        double startThreshold = startThreshold();
        double releaseThreshold = Math.max(20.0, startThreshold * releasePercent / 100.0);
        lastStartThreshold = startThreshold;

        if (!speaking) {
            if (envelope < startThreshold) {
                double alpha = envelope < noiseFloor ? 0.14 : 0.025;
                noiseFloor = noiseFloor * (1.0 - alpha) + envelope * alpha;
                noiseFloor = clamp(noiseFloor, 12.0, 5000.0);
                startThreshold = startThreshold();
                lastStartThreshold = startThreshold;
            }

            if (envelope >= startThreshold) {
                onsetMs += frameMs;
                if (onsetMs >= onsetMsRequired && sinceLastWordMs >= minWordIntervalMs) {
                    speaking = true;
                    onsetMs = 0;
                    quietMs = 0;
                    sinceLastWordMs = 0;
                    return 1;
                }
            } else {
                onsetMs = 0;
            }
            return 0;
        }

        if (envelope < releaseThreshold) {
            quietMs += frameMs;
            if (quietMs >= releaseMs) {
                speaking = false;
                quietMs = 0;
                onsetMs = 0;
            }
        } else {
            quietMs = 0;

            // Opcjonalny tryb dla mowy bez pauz. Domyślnie wyłączony, żeby
            // pojedyncze otwarcie ust nie powodowało lawinowego przewijania.
            if (continuousWordMs > 0
                    && sinceLastWordMs >= continuousWordMs
                    && sinceLastWordMs >= minWordIntervalMs) {
                sinceLastWordMs = 0;
                return 1;
            }
        }

        return 0;
    }

    double getLastLevel() {
        return lastLevel;
    }

    double getLastStartThreshold() {
        return lastStartThreshold;
    }

    void reset() {
        noiseFloor = 0.0;
        envelope = 0.0;
        calibrationMinimum = Double.MAX_VALUE;
        calibrationMs = 0;
        speaking = false;
        onsetMs = 0;
        quietMs = 0;
        sinceLastWordMs = 10_000;
        lastLevel = 0.0;
        lastStartThreshold = 0.0;
    }

    private double startThreshold() {
        return Math.max(
                minStartThreshold,
                Math.max(18.0, noiseFloor) * noiseRatioPercent / 100.0 + 10.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
