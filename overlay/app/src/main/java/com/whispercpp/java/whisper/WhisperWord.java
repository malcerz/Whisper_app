package com.whispercpp.java.whisper;

public final class WhisperWord {
    private final long start;
    private final long end;
    private final String text;

    public WhisperWord(long start, long end, String text) {
        this.start = Math.max(0L, start);
        this.end = Math.max(this.start + 1L, end);
        this.text = text == null ? "" : text;
    }

    /** Whisper time in centiseconds (10 ms units). */
    public long getStart() { return start; }
    /** Whisper time in centiseconds (10 ms units). */
    public long getEnd() { return end; }
    public String getText() { return text; }
}
