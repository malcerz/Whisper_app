package com.whispercpp.java.whisper;

import java.util.ArrayList;
import java.util.List;

public final class WhisperContext implements AutoCloseable {
    private long ptr;

    private WhisperContext(long ptr) {
        this.ptr = ptr;
    }

    public static WhisperContext create(String modelPath) {
        long ptr = WhisperLib.initContext(modelPath);
        if (ptr == 0L) {
            throw new IllegalStateException("Nie można wczytać modelu: " + modelPath);
        }
        WhisperLib.setAbortRequested(false);
        return new WhisperContext(ptr);
    }

    public List<WhisperSegment> transcribe(float[] audio, String language) {
        if (ptr == 0L) throw new IllegalStateException("WhisperContext jest zamknięty");
        int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
        int rc = WhisperLib.fullTranscribe(ptr, threads, audio, language == null ? "pl" : language);
        if (rc != 0) return new ArrayList<>();

        int count = WhisperLib.getTextSegmentCount(ptr);
        ArrayList<WhisperSegment> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new WhisperSegment(
                    WhisperLib.getTextSegmentT0(ptr, i),
                    WhisperLib.getTextSegmentT1(ptr, i),
                    WhisperLib.getTextSegment(ptr, i)));
        }
        return out;
    }

    public void requestAbort() {
        WhisperLib.setAbortRequested(true);
    }

    @Override
    public void close() {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr);
            ptr = 0L;
        }
    }
}
