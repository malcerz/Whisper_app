package pl.whisperfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SubtitleCue {
    final long startMs;
    final long endMs;
    final String text;
    final List<SubtitleWord> words;
    final int firstLineCount;
    final int sourceStartIndex;
    final int sourceEndIndex;

    SubtitleCue(long startMs, long endMs, String text) {
        this(startMs, endMs, text, Collections.emptyList(), 0, -1, -1);
    }

    SubtitleCue(long startMs, long endMs, String text, List<SubtitleWord> words,
                int firstLineCount, int sourceStartIndex, int sourceEndIndex) {
        this.startMs = Math.max(0L, startMs);
        this.endMs = Math.max(this.startMs + 1L, endMs);
        this.text = text == null ? "" : text;
        this.words = words == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(words));
        this.firstLineCount = firstLineCount <= 0
                ? this.words.size() : Math.min(firstLineCount, this.words.size());
        this.sourceStartIndex = sourceStartIndex;
        this.sourceEndIndex = sourceEndIndex;
    }
}
