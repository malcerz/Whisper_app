package pl.whisperfiles;

/**
 * Bardzo lekki licznik „wypowiedzianych słów”. Nie rozpoznaje treści.
 * Wykrywa aktywność mowy i zamienia czas ciągłej wypowiedzi na kolejne kroki telepromptera.
 */
final class WordPulseDetector {
    private static final int MIN_ONSET_MS = 40;
    private static final int RELEASE_MS = 120;
    private static final int WORD_CADENCE_MS = 340;
    private static final double ABSOLUTE_THRESHOLD = 380.0;

    private double noiseFloor = 180.0;
    private double envelope;
    private boolean speaking;
    private int onsetMs;
    private int quietMs;
    private int voicedMsSinceWord;

    int accept(short[] samples, int length, int sampleRate) {
        if (samples == null || length <= 0 || sampleRate <= 0) return 0;

        long sum = 0L;
        for (int i = 0; i < length; i++) {
            long v = samples[i];
            sum += v * v;
        }
        double rms = Math.sqrt(sum / (double) length);
        if (envelope == 0.0) envelope = rms;
        else envelope = envelope * 0.78 + rms * 0.22;

        int frameMs = Math.max(1, (int) Math.round(length * 1000.0 / sampleRate));

        double threshold = Math.max(ABSOLUTE_THRESHOLD, noiseFloor * 2.8);
        boolean active = envelope > threshold;

        if (!speaking && envelope < threshold * 0.85) {
            noiseFloor = noiseFloor * 0.985 + envelope * 0.015;
            if (noiseFloor < 80.0) noiseFloor = 80.0;
            if (noiseFloor > 1800.0) noiseFloor = 1800.0;
        }

        int words = 0;
        if (active) {
            quietMs = 0;
            onsetMs += frameMs;
            if (!speaking) {
                if (onsetMs >= MIN_ONSET_MS) {
                    speaking = true;
                    voicedMsSinceWord = 0;
                    words++;
                }
            } else {
                voicedMsSinceWord += frameMs;
                while (voicedMsSinceWord >= WORD_CADENCE_MS) {
                    voicedMsSinceWord -= WORD_CADENCE_MS;
                    words++;
                }
            }
        } else {
            onsetMs = 0;
            if (speaking) {
                quietMs += frameMs;
                if (quietMs >= RELEASE_MS) {
                    speaking = false;
                    quietMs = 0;
                    voicedMsSinceWord = 0;
                }
            }
        }
        return words;
    }

    void reset() {
        envelope = 0.0;
        speaking = false;
        onsetMs = 0;
        quietMs = 0;
        voicedMsSinceWord = 0;
    }
}
