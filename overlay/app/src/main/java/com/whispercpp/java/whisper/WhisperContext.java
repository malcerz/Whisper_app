package com.whispercpp.java.whisper;

import java.util.ArrayList;
import java.util.List;

public final class WhisperContext implements AutoCloseable {
    public interface ProgressListener {
        void onProgress(long processedSamples, long totalSamples);
    }

    private static volatile ProgressListener progressListener;
    private long ptr;
    private final String vadModelPath;

    private WhisperContext(long ptr, String vadModelPath) {
        this.ptr = ptr;
        this.vadModelPath = vadModelPath;
    }

    public static WhisperContext create(String modelPath) {
        return create(modelPath, null);
    }

    public static WhisperContext create(String modelPath, String vadModelPath) {
        long ptr = WhisperLib.initContext(modelPath);
        if (ptr == 0L) {
            throw new IllegalStateException("Nie można wczytać modelu: " + modelPath);
        }
        WhisperLib.setAbortRequested(false);
        return new WhisperContext(ptr, vadModelPath);
    }

    public List<WhisperSegment> transcribe(float[] audio, String language) {
        return transcribe(audio, language, 0L, -1L, null);
    }

    public List<WhisperSegment> transcribe(float[] audio, String language,
                                           long chunkStartSamples, long totalSamples,
                                           ProgressListener listener) {
        if (ptr == 0L) throw new IllegalStateException("WhisperContext jest zamknięty");
        int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
        progressListener = listener;
        int rc;
        try {
            rc = WhisperLib.fullTranscribe(ptr, threads, audio,
                    language == null ? "pl" : language, vadModelPath,
                    chunkStartSamples, totalSamples);
        } finally {
            progressListener = null;
        }
        if (rc != 0) return new ArrayList<>();

        int count = WhisperLib.getTextSegmentCount(ptr);
        int vadCount = WhisperLib.getVadSegmentCount(ptr);
        long firstSpeechStart = vadCount > 0 ? WhisperLib.getVadSegmentT0(ptr, 0) : -1L;

        ArrayList<WhisperSegment> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long segmentStart = WhisperLib.getTextSegmentT0(ptr, i);
            long segmentEnd = WhisperLib.getTextSegmentT1(ptr, i);
            long timingFloor = i == 0 && firstSpeechStart >= 0L ? firstSpeechStart : -1L;
            if (timingFloor >= 0L) segmentStart = Math.max(segmentStart, timingFloor);
            String segmentText = WhisperLib.getTextSegment(ptr, i);
            List<WhisperWord> words = readWords(i, segmentStart, segmentEnd, segmentText, timingFloor);
            out.add(new WhisperSegment(segmentStart, segmentEnd, segmentText, words));
        }
        return out;
    }

    static void dispatchNativeProgress(long processedSamples, long totalSamples) {
        ProgressListener listener = progressListener;
        if (listener != null) listener.onProgress(processedSamples, totalSamples);
    }

    private List<WhisperWord> readWords(int segmentIndex, long segmentStart, long segmentEnd, String segmentText, long timingFloor) {
        ArrayList<WhisperWord> words = new ArrayList<>();
        int tokenCount = WhisperLib.getTextSegmentTokenCount(ptr, segmentIndex);
        StringBuilder current = new StringBuilder();
        long wordStart = -1L;
        long wordEnd = -1L;

        for (int tokenIndex = 0; tokenIndex < tokenCount; tokenIndex++) {
            if (!WhisperLib.isTextSegmentToken(ptr, segmentIndex, tokenIndex)) continue;
            String raw = WhisperLib.getTextSegmentToken(ptr, segmentIndex, tokenIndex);
            if (raw == null || raw.isEmpty()) continue;
            long t0 = WhisperLib.getTextSegmentTokenT0(ptr, segmentIndex, tokenIndex);
            long t1 = WhisperLib.getTextSegmentTokenT1(ptr, segmentIndex, tokenIndex);
            if (t0 < 0 || t1 <= t0) {
                if (wordEnd >= 0L) {
                    t0 = wordEnd;
                    t1 = wordEnd + 1L;
                } else {
                    t0 = segmentStart;
                    t1 = Math.max(segmentStart + 1L, segmentEnd);
                }
            }
            if (timingFloor >= 0L) {
                t0 = Math.max(t0, timingFloor);
                t1 = Math.max(t1, t0 + 1L);
            }

            boolean startsWithSpace = Character.isWhitespace(raw.charAt(0));
            boolean endsWithSpace = Character.isWhitespace(raw.charAt(raw.length() - 1));
            String normalized = raw.replace('\n', ' ').replace('\r', ' ');
            String[] pieces = normalized.trim().split("\\s+");

            if (startsWithSpace && current.length() > 0) {
                addWord(words, current, wordStart, wordEnd);
                current.setLength(0);
                wordStart = -1L;
                wordEnd = -1L;
            }

            if (pieces.length == 1 && !pieces[0].isEmpty()) {
                if (wordStart < 0) wordStart = t0;
                current.append(pieces[0]);
                wordEnd = Math.max(wordEnd, t1);
            } else {
                for (int p = 0; p < pieces.length; p++) {
                    String piece = pieces[p];
                    if (piece.isEmpty()) continue;
                    if (p > 0 && current.length() > 0) {
                        addWord(words, current, wordStart, wordEnd);
                        current.setLength(0);
                        wordStart = -1L;
                        wordEnd = -1L;
                    }
                    if (wordStart < 0) wordStart = t0;
                    current.append(piece);
                    wordEnd = Math.max(wordEnd, t1);
                }
            }

            if (endsWithSpace && current.length() > 0) {
                addWord(words, current, wordStart, wordEnd);
                current.setLength(0);
                wordStart = -1L;
                wordEnd = -1L;
            }
        }
        if (current.length() > 0) addWord(words, current, wordStart, wordEnd);

        if (words.isEmpty()) {
            words.addAll(distributeFallbackWords(segmentText, segmentStart, segmentEnd));
        }
        return words;
    }

    private static void addWord(List<WhisperWord> out, StringBuilder text, long start, long end) {
        String clean = text.toString().trim();
        if (clean.isEmpty()) return;
        out.add(new WhisperWord(Math.max(0L, start), Math.max(start + 1L, end), clean));
    }

    private static List<WhisperWord> distributeFallbackWords(String text, long start, long end) {
        ArrayList<WhisperWord> out = new ArrayList<>();
        String clean = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) return out;
        String[] parts = clean.split(" ");
        long duration = Math.max(parts.length, end - start);
        long cursor = start;
        int totalWeight = 0;
        for (String part : parts) totalWeight += Math.max(1, part.length());
        int consumed = 0;
        for (int i = 0; i < parts.length; i++) {
            consumed += Math.max(1, parts[i].length());
            long wordEnd = i == parts.length - 1
                    ? end
                    : start + Math.round(duration * (consumed / (double) totalWeight));
            wordEnd = Math.max(cursor + 1L, wordEnd);
            out.add(new WhisperWord(cursor, wordEnd, parts[i]));
            cursor = wordEnd;
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
