package pl.whisperfiles;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;

import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

import java.util.Collections;
import java.util.List;

@UnstableApi
final class SubtitleCanvasOverlay extends CanvasOverlay {
    static final int POSITION_BOTTOM = 0;
    static final int POSITION_CENTER = 1;
    static final int POSITION_TOP = 2;

    private final List<SubtitleWord> words;
    private final int position;
    private final float relativeTextSize;
    private final boolean drawBackground;
    private final boolean trackWord;
    private final float activeScale;
    private final int highlightStrength;
    private final SubtitlePainter painter = new SubtitlePainter();

    private List<SubtitleCue> cues = Collections.emptyList();
    private int width;
    private int height;
    private int lastCueIndex;
    private float baseTextPx;

    SubtitleCanvasOverlay(List<SubtitleWord> words, int position, float relativeTextSize,
                          boolean drawBackground, boolean trackWord, float activeScale,
                          int highlightStrength) {
        super(true);
        this.words = words;
        this.position = position;
        this.relativeTextSize = relativeTextSize;
        this.drawBackground = drawBackground;
        this.trackWord = trackWord;
        this.activeScale = activeScale;
        this.highlightStrength = highlightStrength;
    }

    @Override
    public void configure(Size videoSize) {
        super.configure(videoSize);
        width = videoSize.getWidth();
        height = videoSize.getHeight();
        baseTextPx = SubtitleLayoutEngine.textSizePx(height, relativeTextSize);
        cues = SubtitleLayoutEngine.layout(words, width, height, relativeTextSize, trackWord ? activeScale : 1f);
        lastCueIndex = 0;
    }

    @Override
    public void onDraw(Canvas canvas, long presentationTimeUs) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (cues.isEmpty() || width <= 0 || height <= 0) return;
        long timeMs = presentationTimeUs / 1000L;
        SubtitleCue cue = cueAt(timeMs);
        if (cue == null) return;
        painter.draw(canvas, cue, timeMs, width, height, position, baseTextPx,
                drawBackground, trackWord, activeScale, highlightStrength);
    }

    private SubtitleCue cueAt(long timeMs) {
        if (cues.isEmpty()) return null;
        if (lastCueIndex >= cues.size()) lastCueIndex = cues.size() - 1;
        if (lastCueIndex < 0) lastCueIndex = 0;
        SubtitleCue current = cues.get(lastCueIndex);
        if (timeMs >= current.startMs && timeMs < current.endMs) return current;
        if (timeMs >= current.endMs) {
            while (lastCueIndex + 1 < cues.size() && timeMs >= cues.get(lastCueIndex).endMs) {
                lastCueIndex++;
                current = cues.get(lastCueIndex);
                if (timeMs >= current.startMs && timeMs < current.endMs) return current;
            }
        } else {
            while (lastCueIndex > 0 && timeMs < cues.get(lastCueIndex).startMs) lastCueIndex--;
            current = cues.get(lastCueIndex);
            if (timeMs >= current.startMs && timeMs < current.endMs) return current;
        }
        return null;
    }
}
