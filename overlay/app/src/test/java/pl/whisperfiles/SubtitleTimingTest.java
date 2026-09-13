package pl.whisperfiles;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class SubtitleTimingTest {
    @Test
    public void firstWordStartIgnoresLeadingSilenceInSegment() {
        assertEquals(2400L, SubtitleTiming.firstWordStartMs(
                Arrays.asList(
                        new SubtitleWord(2400L, 2750L, "Pierwsze"),
                        new SubtitleWord(2750L, 3100L, "słowo")),
                0L));
    }

    @Test
    public void firstWordStartUsesFallbackWhenThereAreNoTimedWords() {
        assertEquals(0L, SubtitleTiming.firstWordStartMs(Collections.emptyList(), 0L));
    }
}
