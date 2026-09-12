package pl.whisperfiles;

final class SubtitleWord {
    final long startMs;
    final long endMs;
    final String text;

    SubtitleWord(long startMs, long endMs, String text) {
        this.startMs = Math.max(0L, startMs);
        this.endMs = Math.max(this.startMs + 1L, endMs);
        this.text = text == null ? "" : text.trim();
    }
}
