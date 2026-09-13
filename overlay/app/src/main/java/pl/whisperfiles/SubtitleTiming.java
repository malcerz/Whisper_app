package pl.whisperfiles;

import java.util.List;

final class SubtitleTiming {
    private SubtitleTiming() {}

    static long firstWordStartMs(List<SubtitleWord> words, long fallbackMs) {
        if (words != null) {
            for (SubtitleWord word : words) {
                if (word == null || word.text == null || word.text.trim().isEmpty()) continue;
                if (word.endMs > word.startMs) return Math.max(0L, word.startMs);
            }
        }
        return Math.max(0L, fallbackMs);
    }

    static long lastWordEndMs(List<SubtitleWord> words, long fallbackMs) {
        if (words != null) {
            for (int i = words.size() - 1; i >= 0; i--) {
                SubtitleWord word = words.get(i);
                if (word == null || word.text == null || word.text.trim().isEmpty()) continue;
                if (word.endMs > word.startMs) return Math.max(0L, word.endMs);
            }
        }
        return Math.max(0L, fallbackMs);
    }
}
