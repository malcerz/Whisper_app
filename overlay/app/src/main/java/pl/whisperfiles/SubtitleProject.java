package pl.whisperfiles;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.util.UnstableApi;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@UnstableApi
final class SubtitleProject {
    private SubtitleProject() {}

    static List<SubtitleWord> loadWords(Context context) throws Exception {
        List<SubtitleWord> words = SubtitleTimelineStore.read(context);
        if (!words.isEmpty()) return words;
        File srt = new File(context.getFilesDir(), "last_transcript.srt");
        if (srt.isFile() && srt.length() > 0) {
            words = SubtitleTimelineStore.fromSrt(SrtParser.read(srt));
            if (!words.isEmpty()) SubtitleTimelineStore.write(context, words);
        }
        return words;
    }

    static List<SubtitleCue> layout(Context context, Uri mediaUri, float relativeTextSize,
                                    float activeScale) throws Exception {
        List<SubtitleWord> words = loadWords(context);
        VideoInfo info = VideoInfo.read(context, mediaUri);
        return SubtitleLayoutEngine.layout(words, info.width, info.height,
                relativeTextSize, activeScale);
    }

    static void saveWordsAndOutputs(Context context, Uri mediaUri, List<SubtitleWord> words,
                                    float relativeTextSize, float activeScale) throws Exception {
        SubtitleTimelineStore.write(context, words);
        VideoInfo info = VideoInfo.read(context, mediaUri);
        List<SubtitleCue> cues = SubtitleLayoutEngine.layout(words, info.width, info.height,
                relativeTextSize, activeScale);
        writeSrt(context, cues);
        writeTxt(context, words);
    }

    static void regenerateOutputs(Context context, Uri mediaUri, float relativeTextSize,
                                  float activeScale) throws Exception {
        List<SubtitleWord> words = loadWords(context);
        if (words.isEmpty()) return;
        VideoInfo info = VideoInfo.read(context, mediaUri);
        List<SubtitleCue> cues = SubtitleLayoutEngine.layout(words, info.width, info.height,
                relativeTextSize, activeScale);
        writeSrt(context, cues);
        writeTxt(context, words);
    }

    private static void writeSrt(Context context, List<SubtitleCue> cues) throws Exception {
        File file = new File(context.getFilesDir(), "last_transcript.srt");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            int index = 1;
            long lastEnd = 0L;
            for (SubtitleCue cue : cues) {
                long start = Math.max(cue.startMs, lastEnd);
                long end = Math.max(start + 1L, cue.endMs);
                out.write(Integer.toString(index++));
                out.newLine();
                out.write(formatSrtTime(start) + " --> " + formatSrtTime(end));
                out.newLine();
                out.write(cue.text);
                out.newLine();
                out.newLine();
                lastEnd = end;
            }
        }
    }

    private static void writeTxt(Context context, List<SubtitleWord> words) throws Exception {
        File file = new File(context.getFilesDir(), "last_transcript.txt");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            for (SubtitleWord word : words) {
                if (word.text.isEmpty()) continue;
                if (line.length() > 0) line.append(' ');
                line.append(word.text);
                if (endsSentence(word.text) || line.length() > 160) {
                    out.write(line.toString().trim());
                    out.newLine();
                    line.setLength(0);
                }
            }
            if (line.length() > 0) {
                out.write(line.toString().trim());
                out.newLine();
            }
        }
    }

    static String plainText(List<SubtitleWord> words) {
        StringBuilder sb = new StringBuilder();
        for (SubtitleWord word : words) {
            if (word.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.text);
        }
        return sb.toString();
    }

    private static boolean endsSentence(String value) {
        if (value == null || value.isEmpty()) return false;
        char c = value.charAt(value.length() - 1);
        return c == '.' || c == '!' || c == '?';
    }

    private static String formatSrtTime(long ms) {
        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;
        long minutes = ms / 60_000L;
        ms %= 60_000L;
        long seconds = ms / 1000L;
        long millis = ms % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }
}
