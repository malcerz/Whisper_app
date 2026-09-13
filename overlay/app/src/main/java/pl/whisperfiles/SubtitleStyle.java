package pl.whisperfiles;

import android.graphics.Color;

/**
 * Single source of truth for subtitle style values shared by the main screen,
 * the subtitle editor, the Media3 preview and the final MP4 burn.
 *
 * Keeping the conversions here guarantees that the preview and the exported
 * film always use exactly the same text size, position and highlight colour.
 */
final class SubtitleStyle {
    private SubtitleStyle() {}

    /** Text size expressed in tenths of a percent of the video height (48 = 4.8%). */
    static final int SIZE_X10_MIN = 20;
    static final int SIZE_X10_MAX = 100;
    static final int SIZE_X10_DEFAULT = 48;

    /** Vertical position of the subtitle block in percent of the video height from the top. */
    static final int POSITION_PERCENT_MIN = 0;
    static final int POSITION_PERCENT_MAX = 100;
    static final int POSITION_PERCENT_DEFAULT = 88;

    /** Colours offered for the currently spoken word. */
    static final int[] HIGHLIGHT_COLORS = {
            0xFFFFCD2D, // żółty
            0xFFFF8C28, // pomarańczowy
            0xFFFF5050, // czerwony
            0xFF5AC85A, // zielony
            0xFF50D2FF, // cyjan
            0xFFFF78C8, // różowy
    };
    static final String[] HIGHLIGHT_COLOR_NAMES = {
            "Żółty", "Pomarańczowy", "Czerwony", "Zielony", "Cyjan", "Różowy",
    };
    static final int HIGHLIGHT_COLOR_DEFAULT_INDEX = 0;

    static float relativeTextSize(int sizeX10) {
        int clamped = Math.max(SIZE_X10_MIN, Math.min(SIZE_X10_MAX, sizeX10));
        return clamped / 1000f;
    }

    static float positionFraction(int positionPercent) {
        int clamped = Math.max(POSITION_PERCENT_MIN, Math.min(POSITION_PERCENT_MAX, positionPercent));
        return clamped / 100f;
    }

    static int highlightColor(int index) {
        int safe = Math.max(0, Math.min(HIGHLIGHT_COLORS.length - 1, index));
        return HIGHLIGHT_COLORS[safe];
    }

    static int highlightColorIndex(int color) {
        for (int i = 0; i < HIGHLIGHT_COLORS.length; i++) {
            if (HIGHLIGHT_COLORS[i] == color) return i;
        }
        // Any custom colour that is not on the list is shown as the default one.
        return HIGHLIGHT_COLOR_DEFAULT_INDEX;
    }

    static int defaultHighlightColor() {
        return HIGHLIGHT_COLORS[HIGHLIGHT_COLOR_DEFAULT_INDEX];
    }

    static boolean isBright(int color) {
        return Color.luminance(color) > 0.5f;
    }
}
