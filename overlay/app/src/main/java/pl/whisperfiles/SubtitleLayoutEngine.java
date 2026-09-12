package pl.whisperfiles;

import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SubtitleLayoutEngine {
    static final float SAFE_WIDTH_FRACTION = 0.84f;
    private static final long MAX_GAP_MS = 700L;

    private SubtitleLayoutEngine() {}

    static List<SubtitleCue> layout(List<SubtitleWord> words, int videoWidth, int videoHeight,
                                    float relativeTextSize, float activeScale) {
        if (words == null || words.isEmpty()) return Collections.emptyList();
        int width = Math.max(1, videoWidth);
        int height = Math.max(1, videoHeight);
        boolean portrait = height > width;
        long maxCueMs = portrait ? 2500L : 3400L;
        int maxWords = portrait ? 6 : 10;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(textSizePx(height, relativeTextSize));
        float maxWidth = width * SAFE_WIDTH_FRACTION;
        float scale = Math.max(1f, Math.min(1.6f, activeScale));

        ArrayList<SubtitleCue> out = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            SubtitleWord first = words.get(i);
            if (first == null || first.text.isEmpty()) { i++; continue; }

            int end = i;
            int bestBreak = fitLines(words, i, end, paint, maxWidth, scale);
            if (bestBreak < 0) bestBreak = 1;

            while (end + 1 < words.size()) {
                SubtitleWord current = words.get(end);
                SubtitleWord next = words.get(end + 1);
                if (next == null || next.text.isEmpty()) { end++; continue; }
                int count = end - i + 1;
                if (count >= maxWords) break;
                if (next.startMs - current.endMs > MAX_GAP_MS) break;
                if (next.endMs - first.startMs > maxCueMs) break;
                if (count >= 3 && endsSentence(current.text)) break;

                int candidateBreak = fitLines(words, i, end + 1, paint, maxWidth, scale);
                if (candidateBreak < 0) break;
                end++;
                bestBreak = candidateBreak;
            }

            List<SubtitleWord> cueWords = new ArrayList<>(words.subList(i, end + 1));
            String text = buildText(cueWords, bestBreak);
            long start = cueWords.get(0).startMs;
            long cueEnd = cueWords.get(cueWords.size() - 1).endMs;
            out.add(new SubtitleCue(start, Math.max(start + 1L, cueEnd), text,
                    cueWords, bestBreak, i, end));
            i = end + 1;
        }
        return out;
    }

    static float textSizePx(int videoHeight, float relativeTextSize) {
        return Math.max(28f, Math.max(1, videoHeight) * relativeTextSize);
    }

    private static int fitLines(List<SubtitleWord> words, int from, int to, Paint paint,
                                float maxWidth, float activeScale) {
        int count = to - from + 1;
        if (count <= 0) return -1;
        if (worstLineWidth(words, from, to, paint, activeScale) <= maxWidth) return count;
        if (count == 1) return 1; // Painter scales an exceptionally long single word down safely.

        int bestSplit = -1;
        float bestScore = Float.MAX_VALUE;
        for (int split = 1; split < count; split++) {
            int leftTo = from + split - 1;
            int rightFrom = from + split;
            float left = worstLineWidth(words, from, leftTo, paint, activeScale);
            float right = worstLineWidth(words, rightFrom, to, paint, activeScale);
            if (left > maxWidth || right > maxWidth) continue;
            float score = Math.abs(left - right) + Math.max(left, right) * 0.08f;
            if (score < bestScore) {
                bestScore = score;
                bestSplit = split;
            }
        }
        return bestSplit;
    }

    static float worstLineWidth(List<SubtitleWord> words, int from, int to,
                                Paint paint, float activeScale) {
        float base = 0f;
        float maxWord = 0f;
        float space = paint.measureText(" ");
        for (int i = from; i <= to; i++) {
            String text = words.get(i).text;
            float w = paint.measureText(text);
            if (i > from) base += space;
            base += w;
            maxWord = Math.max(maxWord, w);
        }
        return base + Math.max(0f, activeScale - 1f) * maxWord;
    }

    private static String buildText(List<SubtitleWord> words, int firstLineCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(i == firstLineCount ? '\n' : ' ');
            sb.append(words.get(i).text);
        }
        return sb.toString();
    }

    private static boolean endsSentence(String value) {
        if (value == null || value.isEmpty()) return false;
        char c = value.charAt(value.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == ':' || c == ';';
    }
}
