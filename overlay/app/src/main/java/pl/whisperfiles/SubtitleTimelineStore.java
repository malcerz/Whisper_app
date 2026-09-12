package pl.whisperfiles;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class SubtitleTimelineStore {
    private static final String FILE_NAME = "last_words.tsv";

    private SubtitleTimelineStore() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static void write(Context context, List<SubtitleWord> words) throws Exception {
        File target = file(context);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp, false), StandardCharsets.UTF_8))) {
            for (SubtitleWord word : words) {
                if (word == null || word.text.isEmpty()) continue;
                String encoded = encoder.encodeToString(word.text.getBytes(StandardCharsets.UTF_8));
                out.write(Long.toString(word.startMs));
                out.write('\t');
                out.write(Long.toString(word.endMs));
                out.write('\t');
                out.write(encoded);
                out.newLine();
            }
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Nie można zastąpić osi słów");
        if (!temp.renameTo(target)) throw new IllegalStateException("Nie można zapisać osi słów");
    }

    static List<SubtitleWord> read(Context context) throws Exception {
        File source = file(context);
        ArrayList<SubtitleWord> out = new ArrayList<>();
        if (!source.isFile()) return out;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split("\\t", 3);
                if (p.length != 3) continue;
                try {
                    long start = Long.parseLong(p[0]);
                    long end = Long.parseLong(p[1]);
                    String text = new String(decoder.decode(p[2]), StandardCharsets.UTF_8);
                    if (!text.trim().isEmpty()) out.add(new SubtitleWord(start, end, text));
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    static List<SubtitleWord> fromSrt(List<SubtitleCue> cues) {
        ArrayList<SubtitleWord> out = new ArrayList<>();
        for (SubtitleCue cue : cues) {
            String clean = cue.text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
            if (clean.isEmpty()) continue;
            out.addAll(wordsFromText(clean, cue.startMs, cue.endMs));
        }
        return out;
    }

    static List<SubtitleWord> wordsFromText(String text, long startMs, long endMs) {
        ArrayList<SubtitleWord> out = new ArrayList<>();
        String clean = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return out;
        String[] parts = clean.split(" ");
        long duration = Math.max(parts.length, endMs - startMs);
        int totalWeight = 0;
        for (String part : parts) totalWeight += Math.max(1, visibleWeight(part));
        int consumed = 0;
        long cursor = startMs;
        for (int i = 0; i < parts.length; i++) {
            consumed += Math.max(1, visibleWeight(parts[i]));
            long next = i == parts.length - 1
                    ? endMs
                    : startMs + Math.round(duration * (consumed / (double) totalWeight));
            next = Math.max(cursor + 1L, next);
            out.add(new SubtitleWord(cursor, next, parts[i]));
            cursor = next;
        }
        return out;
    }

    static List<SubtitleWord> replaceRange(List<SubtitleWord> source, int fromInclusive,
                                           int toInclusive, String editedText) {
        ArrayList<SubtitleWord> out = new ArrayList<>(source);
        if (fromInclusive < 0 || toInclusive < fromInclusive || toInclusive >= source.size()) return out;
        List<SubtitleWord> old = source.subList(fromInclusive, toInclusive + 1);
        String clean = editedText == null ? "" : editedText.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        ArrayList<SubtitleWord> replacement = new ArrayList<>();
        if (!clean.isEmpty()) {
            String[] parts = clean.split(" ");
            if (parts.length == old.size()) {
                for (int i = 0; i < parts.length; i++) {
                    SubtitleWord prev = old.get(i);
                    replacement.add(new SubtitleWord(prev.startMs, prev.endMs, parts[i]));
                }
            } else {
                long start = old.get(0).startMs;
                long end = old.get(old.size() - 1).endMs;
                replacement.addAll(wordsFromText(clean, start, end));
            }
        }
        for (int i = toInclusive; i >= fromInclusive; i--) out.remove(i);
        out.addAll(fromInclusive, replacement);
        return out;
    }

    private static int visibleWeight(String value) {
        return value == null ? 1 : Math.max(1, value.replaceAll("[\\p{Punct}]", "").length());
    }
}
