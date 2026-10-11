package pl.whisperfiles;

/**
 * Lekki licznik aktywności mowy dla telepromptera.
 *
 * Nie rozpoznaje treści słów. Każdy wyraźny impuls mowy po krótkiej ciszy
 * przesuwa teleprompter dokładnie o jedno słowo.
 */
final class WordPulseDetector {
    private static final int CALIBRATION_MS = 160;
    private static final int MIN_ONSET_MS = 20;
    private static final int RELEASE_MS = 60;
    private static final int MIN_WORD_INTERVAL_MS = 140;

    private static final double MIN_START_THRESHOLD = 55.0;
    private static final double START_NOISE_RATIO = 1.55;

    private double noiseFloor;
    private double envelope;
    private double calibrationMinimum = Double.MAX_VALUE;
    private int calibrationMs;

    private boolean speaking;
    private int onsetMs;
    private int quietMs;
    private int sinceLastWordMs = MIN_WORD_INTERVAL_MS;

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
        // Celowo bardzo mała histereza. Na realnym telefonie drugi strumień mikrofonu
        // ma niski poziom (np. ok. 51 przy progu 55). Żeby kolejne słowo mogło zostać
        // ponownie uzbrojone, cisza musi zejść tylko trochę poniżej progu startowego,
        // ale utrzymać się przez RELEASE_MS.
        double releaseThreshold = Math.max(40.0, startThreshold * 0.95);
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
                if (onsetMs >= MIN_ONSET_MS && sinceLastWordMs >= MIN_WORD_INTERVAL_MS) {
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

        // Po wykryciu słowa NIE ma już żadnego liczenia "co X ms".
        // Jedno otwarcie ust / jedna ciągła wypowiedź = maksymalnie jeden krok,
        // dopóki nie pojawi się rzeczywista krótka cisza.
        if (envelope < releaseThreshold) {
            quietMs += frameMs;
            if (quietMs >= RELEASE_MS) {
                speaking = false;
                quietMs = 0;
                onsetMs = 0;
            }
        } else {
            quietMs = 0;
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
        sinceLastWordMs = MIN_WORD_INTERVAL_MS;
        lastLevel = 0.0;
        lastStartThreshold = 0.0;
    }

    private double startThreshold() {
        return Math.max(
                MIN_START_THRESHOLD,
                Math.max(18.0, noiseFloor) * START_NOISE_RATIO + 10.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
