package pl.whisperfiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.media3.common.util.UnstableApi;

import java.util.Collections;
import java.util.List;

@UnstableApi
public final class SubtitlePreviewView extends View {
    private static final String TAG = "SubtitlePreview";
    private final SubtitlePainter painter = new SubtitlePainter();
    private VideoInfo videoInfo = new VideoInfo(1080, 1920);
    private SubtitleCue cue;
    private List<SubtitleCue> cues = Collections.emptyList();
    private int position = SubtitleCanvasOverlay.POSITION_BOTTOM;
    private float relativeSize = 0.048f;
    private boolean background;
    private boolean trackWord = true;
    private float activeScale = 1.12f;
    private int highlightStrength = 55;
    private long previewTimeMs;

    public SubtitlePreviewView(Context context) {
        this(context, null);
    }

    public SubtitlePreviewView(Context context, AttributeSet attrs) {
        super(context);
        // The view is placed above PlayerView; an opaque background would hide the video.
        setBackgroundColor(Color.TRANSPARENT);
    }

    void setPreview(VideoInfo info, SubtitleCue cue, int position, float relativeSize,
                    boolean background, boolean trackWord, float activeScale, int highlightStrength) {
        if (info != null) this.videoInfo = info;
        this.cues = Collections.emptyList();
        this.cue = cue;
        this.position = position;
        this.relativeSize = relativeSize;
        this.background = background;
        this.trackWord = trackWord;
        this.activeScale = activeScale;
        this.highlightStrength = highlightStrength;
        if (cue != null) {
            if (!cue.words.isEmpty()) {
                SubtitleWord mid = cue.words.get(cue.words.size() / 2);
                previewTimeMs = (mid.startMs + mid.endMs) / 2L;
            } else {
                previewTimeMs = (cue.startMs + cue.endMs) / 2L;
            }
        }
        invalidate();
    }

    void setCues(VideoInfo info, List<SubtitleCue> cues, int position, float relativeSize,
                 boolean background, boolean trackWord, float activeScale, int highlightStrength) {
        if (info != null) this.videoInfo = info;
        this.cues = cues == null ? Collections.emptyList() : cues;
        this.cue = null;
        this.position = position;
        this.relativeSize = relativeSize;
        this.background = background;
        this.trackWord = trackWord;
        this.activeScale = activeScale;
        this.highlightStrength = highlightStrength;
        invalidate();
    }

    void setPlaybackTimeMs(long timeMs) {
        previewTimeMs = Math.max(0L, timeMs);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int saveCount = canvas.save();
        try {
            SubtitleCue visibleCue = cues.isEmpty() ? cue : cueAt(previewTimeMs);
            if (visibleCue == null) return;
            float sx = getWidth() / (float) videoInfo.width;
            float sy = getHeight() / (float) videoInfo.height;
            float scale = Math.min(sx, sy);
            float drawW = videoInfo.width * scale;
            float drawH = videoInfo.height * scale;
            float dx = (getWidth() - drawW) / 2f;
            float dy = (getHeight() - drawH) / 2f;
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            painter.draw(canvas, visibleCue, previewTimeMs, videoInfo.width, videoInfo.height, position,
                    SubtitleLayoutEngine.textSizePx(videoInfo.height, relativeSize), background,
                    trackWord, activeScale, highlightStrength);
        } catch (RuntimeException e) {
            Log.e(TAG, "Subtitle preview rendering failed", e);
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }

    private SubtitleCue cueAt(long timeMs) {
        for (SubtitleCue item : cues) {
            if (item == null) continue;
            if (timeMs >= item.startMs && timeMs < item.endMs) return item;
        }
        return null;
    }
}
