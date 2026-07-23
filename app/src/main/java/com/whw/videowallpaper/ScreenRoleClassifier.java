package com.whw.videowallpaper;

/**
 * Pure geometry fallback for foldables that expose the inner and outer panels
 * as the same logical Android display. The cover display is normally much
 * narrower than the near-square unfolded display.
 */
public final class ScreenRoleClassifier {
    static final float OUTER_SCREEN_ASPECT_RATIO = 1.55f;

    private ScreenRoleClassifier() {
    }

    public static ScreenRole fromDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            return ScreenRole.INNER;
        }
        float longEdge = Math.max(width, height);
        float shortEdge = Math.min(width, height);
        return longEdge / shortEdge >= OUTER_SCREEN_ASPECT_RATIO
                ? ScreenRole.OUTER
                : ScreenRole.INNER;
    }
}
