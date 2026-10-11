package pl.whisperfiles;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WordPulseDetectorTest {
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320; // 20 ms

    @Test
    public void countsFifteenClearlySeparatedWords() {
        WordPulseDetector detector = new WordPulseDetector();
        short[] quiet = frame(20);
        short[] speech = frame(520);

        int words = 0;

        // Kalibracja + chwila ciszy.
        for (int i = 0; i < 12; i++) words += detector.accept(quiet, quiet.length, SAMPLE_RATE);

        // 15 "słów": 120 ms mowy + 60 ms wyraźnej pauzy.
        for (int w = 0; w < 15; w++) {
            for (int i = 0; i < 6; i++) words += detector.accept(speech, speech.length, SAMPLE_RATE);
            for (int i = 0; i < 3; i++) words += detector.accept(quiet, quiet.length, SAMPLE_RATE);
        }

        assertEquals(15, words);
    }

    @Test
    public void doesNotCountQuietBackground() {
        WordPulseDetector detector = new WordPulseDetector();
        short[] quiet = frame(24);

        int words = 0;
        for (int i = 0; i < 200; i++) {
            words += detector.accept(quiet, quiet.length, SAMPLE_RATE);
        }

        assertEquals(0, words);
    }

    private static short[] frame(int amplitude) {
        short[] data = new short[FRAME_SAMPLES];
        for (int i = 0; i < data.length; i++) {
            data[i] = (short) ((i & 1) == 0 ? amplitude : -amplitude);
        }
        return data;
    }
}
