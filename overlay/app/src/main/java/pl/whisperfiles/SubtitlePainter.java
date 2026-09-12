package pl.whisperfiles;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.media3.common.util.UnstableApi;

import java.util.List;

@UnstableApi
final class SubtitlePainter {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);

    SubtitlePainter() {
        fill.setTextAlign(Paint.Align.LEFT);
        fill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setTextAlign(Paint.Align.LEFT);
        stroke.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        stroke.setColor(Color.BLACK);
        background.setColor(Color.argb(120, 0, 0, 0));
    }

    void draw(Canvas canvas, SubtitleCue cue, long timeMs, int width, int height, int position,
              float baseTextPx, boolean drawBackground, boolean trackWord,
              float activeScale, int highlightStrength) {
        if (cue == null || cue.text.isEmpty() || width <= 0 || height <= 0) return;
        float safeWidth = width * SubtitleLayoutEngine.SAFE_WIDTH_FRACTION;
        float scale = Math.max(1f, Math.min(1.6f, activeScale));
        int active = trackWord ? activeWord(cue.words, timeMs) : -1;

        fill.setTextSize(baseTextPx);
        fill.setColor(Color.WHITE);
        fill.setShadowLayer(Math.max(2f, baseTextPx * 0.035f), 0f,
                Math.max(1f, baseTextPx * 0.02f), Color.BLACK);
        stroke.setTextSize(baseTextPx);
        stroke.setStrokeWidth(Math.max(3f, baseTextPx * 0.075f));

        int wordCount = cue.words.size();
        int firstCount = cue.firstLineCount > 0 ? cue.firstLineCount : wordCount;
        if (wordCount == 0) {
            drawFallbackText(canvas, cue.text, width, height, position, baseTextPx, drawBackground, safeWidth);
            return;
        }

        int lineCount = firstCount < wordCount ? 2 : 1;
        float lineHeight = fontHeight(baseTextPx * scale) * 1.06f;
        float totalHeight = lineHeight * lineCount;
        float centerY = centerY(height, totalHeight, position);
        float baselineFirst = centerY - totalHeight / 2f + baselineOffset(baseTextPx * scale);

        float width1 = dynamicLineWidth(cue.words, 0, firstCount - 1, active, baseTextPx, scale);
        float width2 = firstCount < wordCount
                ? dynamicLineWidth(cue.words, firstCount, wordCount - 1, active, baseTextPx, scale) : 0f;
        float shrink = 1f;
        float maxMeasured = Math.max(width1, width2);
        if (maxMeasured > safeWidth && maxMeasured > 0f) shrink = safeWidth / maxMeasured;

        float actualBase = baseTextPx * shrink;
        float actualScale = scale;
        width1 = dynamicLineWidth(cue.words, 0, firstCount - 1, active, actualBase, actualScale);
        width2 = firstCount < wordCount
                ? dynamicLineWidth(cue.words, firstCount, wordCount - 1, active, actualBase, actualScale) : 0f;
        lineHeight = fontHeight(actualBase * actualScale) * 1.06f;
        totalHeight = lineHeight * lineCount;
        centerY = centerY(height, totalHeight, position);
        baselineFirst = centerY - totalHeight / 2f + baselineOffset(actualBase * actualScale);

        if (drawBackground) {
            float maxLine = Math.max(width1, width2);
            float padX = Math.max(14f, actualBase * 0.28f);
            float padY = Math.max(8f, actualBase * 0.16f);
            RectF box = new RectF(
                    width / 2f - maxLine / 2f - padX,
                    centerY - totalHeight / 2f - padY,
                    width / 2f + maxLine / 2f + padX,
                    centerY + totalHeight / 2f + padY);
            // The box must obey the same safe horizontal area as the glyphs.
            float safeLeft = width * (1f - SubtitleLayoutEngine.SAFE_WIDTH_FRACTION) / 2f;
            float safeRight = width - safeLeft;
            box.left = Math.max(box.left, safeLeft);
            box.right = Math.min(box.right, safeRight);
            canvas.drawRoundRect(box, padY, padY, background);
        }

        drawWordLine(canvas, cue.words, 0, firstCount - 1, active, width / 2f,
                baselineFirst, actualBase, actualScale, highlightStrength);
        if (firstCount < wordCount) {
            drawWordLine(canvas, cue.words, firstCount, wordCount - 1, active, width / 2f,
                    baselineFirst + lineHeight, actualBase, actualScale, highlightStrength);
        }
    }

    private void drawWordLine(Canvas canvas, List<SubtitleWord> words, int from, int to, int active,
                              float centerX, float baseline, float baseTextPx, float activeScale,
                              int highlightStrength) {
        if (from > to) return;
        float total = dynamicLineWidth(words, from, to, active, baseTextPx, activeScale);
        float x = centerX - total / 2f;
        float space = measure(" ", baseTextPx);
        float activeCenter = 0f;
        boolean hasActive = false;

        // Draw the complete line at the normal size first. This keeps every word visible even
        // when the highlighted word is enlarged or when the overlay is composited by Media3.
        for (int i = from; i <= to; i++) {
            boolean isActive = i == active;
            String text = words.get(i).text;
            float reservedWidth = measure(text, baseTextPx * (isActive ? activeScale : 1f));
            fill.setTextSize(baseTextPx);
            stroke.setTextSize(baseTextPx);
            stroke.setStrokeWidth(Math.max(3f, baseTextPx * 0.075f));
            fill.setColor(Color.WHITE);
            canvas.drawText(text, x, baseline, stroke);
            canvas.drawText(text, x, baseline, fill);
            if (isActive) {
                activeCenter = x + reservedWidth / 2f;
                hasActive = true;
            }
            x += reservedWidth;
            if (i < to) x += space;
        }

        // Overlay only the active word in the accent color, centered over its reserved slot.
        if (hasActive) {
            fill.setTextAlign(Paint.Align.CENTER);
            stroke.setTextAlign(Paint.Align.CENTER);
            String text = words.get(active).text;
            float size = baseTextPx * activeScale;
            fill.setTextSize(size);
            stroke.setTextSize(size);
            stroke.setStrokeWidth(Math.max(3f, size * 0.075f));
            fill.setColor(activeColor(highlightStrength));
            canvas.drawText(text, activeCenter, baseline, stroke);
            canvas.drawText(text, activeCenter, baseline, fill);
            fill.setTextAlign(Paint.Align.LEFT);
            stroke.setTextAlign(Paint.Align.LEFT);
        }
    }

    private float dynamicLineWidth(List<SubtitleWord> words, int from, int to, int active,
                                   float baseTextPx, float activeScale) {
        if (from > to) return 0f;
        float total = 0f;
        float space = measure(" ", baseTextPx);
        for (int i = from; i <= to; i++) {
            float size = baseTextPx * (i == active ? activeScale : 1f);
            total += measure(words.get(i).text, size);
            if (i < to) total += space;
        }
        return total;
    }

    private float measure(String text, float px) {
        fill.setTextSize(px);
        return fill.measureText(text);
    }

    private int activeWord(List<SubtitleWord> words, long timeMs) {
        for (int i = 0; i < words.size(); i++) {
            SubtitleWord word = words.get(i);
            if (timeMs >= word.startMs && timeMs < word.endMs) return i;
        }
        return -1;
    }

    private int activeColor(int strength) {
        float t = Math.max(0, Math.min(100, strength)) / 100f;
        int r = 255;
        int g = Math.round(255 + (205 - 255) * t);
        int b = Math.round(255 + (45 - 255) * t);
        return Color.rgb(r, g, b);
    }

    private float centerY(int height, float totalHeight, int position) {
        float margin = height * 0.055f;
        if (position == SubtitleCanvasOverlay.POSITION_TOP) return margin + totalHeight / 2f;
        if (position == SubtitleCanvasOverlay.POSITION_CENTER) return height / 2f;
        return height - margin - totalHeight / 2f;
    }

    private float fontHeight(float px) {
        fill.setTextSize(px);
        Paint.FontMetrics fm = fill.getFontMetrics();
        return fm.descent - fm.ascent;
    }

    private float baselineOffset(float px) {
        fill.setTextSize(px);
        Paint.FontMetrics fm = fill.getFontMetrics();
        return -fm.ascent;
    }

    private void drawFallbackText(Canvas canvas, String text, int width, int height, int position,
                                  float baseTextPx, boolean drawBackground, float safeWidth) {
        fill.setTextSize(baseTextPx);
        stroke.setTextSize(baseTextPx);
        String[] lines = text.split("\\n", 2);
        float max = 0f;
        for (String line : lines) max = Math.max(max, fill.measureText(line));
        float shrink = max > safeWidth && max > 0 ? safeWidth / max : 1f;
        float px = baseTextPx * shrink;
        fill.setTextSize(px);
        stroke.setTextSize(px);
        Paint.FontMetrics fm = fill.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * 1.06f;
        float totalHeight = lineHeight * lines.length;
        float centerY = centerY(height, totalHeight, position);
        float baseline = centerY - totalHeight / 2f - fm.ascent;
        if (drawBackground) {
            max = 0f;
            for (String line : lines) max = Math.max(max, fill.measureText(line));
            float pad = px * 0.25f;
            canvas.drawRoundRect(new RectF(width / 2f - max / 2f - pad,
                    centerY - totalHeight / 2f - pad / 2f,
                    width / 2f + max / 2f + pad,
                    centerY + totalHeight / 2f + pad / 2f), pad / 2f, pad / 2f, background);
        }
        fill.setTextAlign(Paint.Align.CENTER);
        stroke.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < lines.length; i++) {
            float y = baseline + i * lineHeight;
            canvas.drawText(lines[i], width / 2f, y, stroke);
            canvas.drawText(lines[i], width / 2f, y, fill);
        }
        fill.setTextAlign(Paint.Align.LEFT);
        stroke.setTextAlign(Paint.Align.LEFT);
    }
}
