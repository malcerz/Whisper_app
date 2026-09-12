package com.whispercpp.java.whisper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WhisperSegment {
    private final long start;
    private final long end;
    private final String text;
    private final List<WhisperWord> words;

    public WhisperSegment(long start, long end, String text) {
        this(start, end, text, Collections.emptyList());
    }

    public WhisperSegment(long start, long end, String text, List<WhisperWord> words) {
        this.start = start;
        this.end = end;
        this.text = text == null ? "" : text;
        this.words = words == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(words));
    }

    public long getStart() { return start; }
    public long getEnd() { return end; }
    public String getText() { return text; }
    public List<WhisperWord> getWords() { return words; }
}
