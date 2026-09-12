package pl.whisperfiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import androidx.media3.common.util.UnstableApi;

@UnstableApi
final class SubtitlePreviewView extends View {
    private final SubtitlePainter painter = new SubtitlePainter();
    private VideoInfo videoInfo = new VideoInfo(1080, 1920);
    private SubtitleCue cue;
    private int position = SubtitleCanvasOverlay.POSITION_BOTTOM;
    private float relativeSize = 0.048f;
    private boolean background;
    private boolean trackWord = true;
    private float activeScale = 1.12f;
    private int highlightStrength = 55;
    private long previewTimeMs;

    SubtitlePreviewView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(20, 20, 20));
    }

    void setPreview(VideoInfo info, SubtitleCue cue, int position, float relativeSize,
                    boolean background, boolean trackWord, float activeScale, int highlightStrength) {
        if (info != null) this.videoInfo = info;
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cue == null) return;
        float sx = getWidth() / (float) videoInfo.width;
        float sy = getHeight() / (float) videoInfo.height;
        float scale = Math.min(sx, sy);
        float drawW = videoInfo.width * scale;
        float drawH = videoInfo.height * scale;
        float dx = (getWidth() - drawW) / 2f;
        float dy = (getHeight() - drawH) / 2f;
        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        painter.draw(canvas, cue, previewTimeMs, videoInfo.width, videoInfo.height, position,
                SubtitleLayoutEngine.textSizePx(videoInfo.height, relativeSize), background,
                trackWord, activeScale, highlightStrength);
        canvas.restore();
    }
}
