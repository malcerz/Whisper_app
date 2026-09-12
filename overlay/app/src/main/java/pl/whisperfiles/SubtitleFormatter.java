package pl.whisperfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SubtitleFormatter {
    private static final int MAX_LINE_CHARS = 42;
    private static final int MAX_CUE_CHARS = 78;
    private static final long MAX_CUE_MS = 6000L;
    private static final long MIN_CUE_MS = 650L;

    private SubtitleFormatter() {}

    static List<SubtitleCue> format(long startMs, long endMs, String rawText) {
        String clean = normalize(rawText);
        if (clean.isEmpty()) return Collections.emptyList();

        long duration = Math.max(MIN_CUE_MS, endMs - startMs);
        int byChars = Math.max(1, (clean.length() + MAX_CUE_CHARS - 1) / MAX_CUE_CHARS);
        int byTime = Math.max(1, (int) ((duration + MAX_CUE_MS - 1) / MAX_CUE_MS));
        int targetParts = Math.max(byChars, byTime);

        List<String> pieces = splitWords(clean, targetParts);
        if (pieces.isEmpty()) pieces.add(clean);

        long totalWeight = 0L;
        for (String piece : pieces) totalWeight += Math.max(1, piece.replace(" ", "").length());

        List<SubtitleCue> out = new ArrayList<>();
        long cursor = startMs;
        long consumedWeight = 0L;
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            long weight = Math.max(1, piece.replace(" ", "").length());
            consumedWeight += weight;
            long cueEnd = i == pieces.size() - 1
                    ? endMs
                    : startMs + Math.round((endMs - startMs) * (consumedWeight / (double) totalWeight));
            cueEnd = Math.max(cursor + MIN_CUE_MS, cueEnd);
            cueEnd = Math.min(endMs, cueEnd);
            if (cueEnd <= cursor) cueEnd = Math.min(endMs, cursor + MIN_CUE_MS);
            out.add(new SubtitleCue(cursor, Math.max(cursor + 1, cueEnd), wrapTwoLines(piece)));
            cursor = cueEnd;
        }
        return out;
    }

    private static List<String> splitWords(String text, int targetParts) {
        String[] words = text.split(" ");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int targetChars = Math.max(28, Math.min(MAX_CUE_CHARS,
                (int) Math.ceil(text.length() / (double) targetParts)));

        for (String word : words) {
            if (word.isEmpty()) continue;
            boolean tooLong = current.length() > 0 && current.length() + 1 + word.length() > targetChars;
            boolean hardLimit = current.length() > 0 && current.length() + 1 + word.length() > MAX_CUE_CHARS;
            boolean naturalBreak = current.length() >= 24 && endsSentence(current);
            if (hardLimit || (tooLong && (out.size() + 1 < targetParts || naturalBreak))) {
                out.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) out.add(current.toString().trim());
        return out;
    }

    private static boolean endsSentence(StringBuilder s) {
        if (s.length() == 0) return false;
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == ':' || c == ';';
    }

    private static String wrapTwoLines(String text) {
        if (text.length() <= MAX_LINE_CHARS) return text;
        int ideal = text.length() / 2;
        int split = -1;
        for (int d = 0; d <= MAX_LINE_CHARS; d++) {
            int left = ideal - d;
            int right = ideal + d;
            if (left > 0 && left < text.length() && text.charAt(left) == ' ') {
                split = left;
                break;
            }
            if (right > 0 && right < text.length() && text.charAt(right) == ' ') {
                split = right;
                break;
            }
        }
        if (split < 0) split = Math.min(MAX_LINE_CHARS, text.length());
        String a = text.substring(0, split).trim();
        String b = text.substring(split).trim();
        if (b.isEmpty()) return a;
        return a + "\n" + b;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }
}
