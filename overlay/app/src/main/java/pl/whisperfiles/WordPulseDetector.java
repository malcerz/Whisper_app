package pl.whisperfiles;

/**
 * Lekki licznik aktywności mowy dla telepromptera.
 *
 * Nie rozpoznaje treści. Liczy osobne impulsy mowy rozdzielone krótką ciszą,
 * a przy dłuższej ciągłej wypowiedzi dodaje kolejne kroki w umiarkowanym tempie.
 */
final class WordPulseDetector {
    private static final int CALIBRATION_MS = 160;
    private static final int MIN_ONSET_MS = 20;
    private static final int RELEASE_MS = 55;
    private static final int CONTINUOUS_WORD_MS = 430;

    private static final double MIN_START_THRESHOLD = 55.0;
    private static final double MIN_RELEASE_THRESHOLD = 42.0;
    private static final double START_NOISE_RATIO = 1.55;
    private static final double RELEASE_NOISE_RATIO = 1.18;

    private double noiseFloor;
    private double envelope;
    private double calibrationMinimum = Double.MAX_VALUE;
    private int calibrationMs;

    private boolean speaking;
    private int onsetMs;
    private int quietMs;
    private int voicedMsSinceWord;

    private double lastLevel;
    private double lastStartThreshold;

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
        // Peak pomaga przy cichych spółgłoskach, ale RMS nadal dominuje.
        double level = Math.max(rms, peak * 0.28);
        if (envelope == 0.0) envelope = level;
        else envelope = envelope * 0.45 + level * 0.55;

        int frameMs = Math.max(1, (int) Math.round(length * 1000.0 / sampleRate));
        lastLevel = envelope;

        // Krótka kalibracja po starcie nagrania. Bierzemy minimum, a nie średnią,
        // żeby przypadkowe słowo wypowiedziane od razu nie ustawiło za wysokiego progu.
        if (calibrationMs < CALIBRATION_MS) {
            calibrationMinimum = Math.min(calibrationMinimum, envelope);
            calibrationMs += frameMs;
            if (calibrationMs >= CALIBRATION_MS) {
                noiseFloor = Math.max(18.0,
                        calibrationMinimum == Double.MAX_VALUE ? envelope : calibrationMinimum);
            }
            lastStartThreshold = Math.max(MIN_START_THRESHOLD,
                    Math.max(18.0, noiseFloor) * START_NOISE_RATIO + 10.0);
            return 0;
        }

        if (noiseFloor <= 0.0) noiseFloor = Math.max(18.0, envelope * 0.45);

        double startThreshold = Math.max(
                MIN_START_THRESHOLD,
                noiseFloor * START_NOISE_RATIO + 10.0);
        double releaseThreshold = Math.max(
                MIN_RELEASE_THRESHOLD,
                noiseFloor * RELEASE_NOISE_RATIO + 6.0);
        lastStartThreshold = startThreshold;

        int words = 0;

        if (!speaking) {
            if (envelope < startThreshold) {
                // Gdy robi się ciszej, obniżaj próg dość szybko. Gdy tło rośnie,
                // podnoś go wolno, żeby nie "uciekał" przed cichą mową.
                double alpha = envelope < noiseFloor ? 0.14 : 0.025;
                noiseFloor = noiseFloor * (1.0 - alpha) + envelope * alpha;
                noiseFloor = clamp(noiseFloor, 12.0, 5000.0);
                startThreshold = Math.max(
                        MIN_START_THRESHOLD,
                        noiseFloor * START_NOISE_RATIO + 10.0);
                lastStartThreshold = startThreshold;
            }

            if (envelope >= startThreshold) {
                onsetMs += frameMs;
                if (onsetMs >= MIN_ONSET_MS) {
                    speaking = true;
                    onsetMs = 0;
                    quietMs = 0;
                    voicedMsSinceWord = 0;
                    words++;
                }
            } else {
                onsetMs = 0;
            }
        } else {
            if (envelope >= releaseThreshold) {
                quietMs = 0;
                voicedMsSinceWord += frameMs;

                // Bez rozpoznawania treści nie da się pewnie rozdzielić słów w ciągłej
                // wypowiedzi. Ten licznik podtrzymuje przesuwanie przy braku pauz.
                while (voicedMsSinceWord >= CONTINUOUS_WORD_MS) {
                    voicedMsSinceWord -= CONTINUOUS_WORD_MS;
                    words++;
                }
            } else {
                quietMs += frameMs;
                if (quietMs >= RELEASE_MS) {
                    speaking = false;
                    quietMs = 0;
                    onsetMs = 0;
                    voicedMsSinceWord = 0;
                }
            }
        }

        return words;
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
        voicedMsSinceWord = 0;
        lastLevel = 0.0;
        lastStartThreshold = 0.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
