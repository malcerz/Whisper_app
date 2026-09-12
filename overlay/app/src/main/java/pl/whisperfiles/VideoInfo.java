package pl.whisperfiles;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

final class VideoInfo {
    final int width;
    final int height;

    VideoInfo(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    static VideoInfo read(Context context, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            int w = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 1080);
            int h = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 1920);
            int rotation = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rotation == 90 || rotation == 270) {
                int t = w; w = h; h = t;
            }
            return new VideoInfo(w, h);
        } catch (Throwable ignored) {
            return new VideoInfo(1080, 1920);
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static int parse(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Exception e) { return fallback; }
    }
}
