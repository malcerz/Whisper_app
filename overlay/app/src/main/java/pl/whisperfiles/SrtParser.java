package pl.whisperfiles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SrtParser {
    private SrtParser() {}

    static List<SubtitleCue> read(File file) throws Exception {
        List<SubtitleCue> cues = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String timeLine;
                if (line.contains("-->")) {
                    timeLine = line;
                } else {
                    timeLine = reader.readLine();
                    if (timeLine == null) break;
                    timeLine = timeLine.trim();
                }
                if (!timeLine.contains("-->")) continue;

                String[] times = timeLine.split("-->");
                if (times.length != 2) continue;
                long start = parseTime(times[0].trim());
                long end = parseTime(times[1].trim());

                StringBuilder text = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) break;
                    if (text.length() > 0) text.append('\n');
                    text.append(line.trim());
                }
                if (text.length() > 0) cues.add(new SubtitleCue(start, end, text.toString()));
            }
        }
        return cues;
    }

    private static long parseTime(String value) {
        String normalized = value.replace('.', ',');
        String[] hm = normalized.split(":");
        if (hm.length != 3) return 0L;
        String[] secMs = hm[2].split(",");
        long h = parseLong(hm[0]);
        long m = parseLong(hm[1]);
        long s = secMs.length > 0 ? parseLong(secMs[0]) : 0L;
        long ms = secMs.length > 1 ? parseMillis(secMs[1]) : 0L;
        return h * 3600000L + m * 60000L + s * 1000L + ms;
    }

    private static long parseMillis(String v) {
        String t = v.trim();
        if (t.length() == 1) t += "00";
        else if (t.length() == 2) t += "0";
        else if (t.length() > 3) t = t.substring(0, 3);
        return parseLong(t);
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v.trim()); }
        catch (Exception e) { return 0L; }
    }
}
