package com.whw.videowallpaper;

/**
 * Calculates the centered texture crop required to fill a surface while
 * preserving the video's aspect ratio.
 */
public final class AspectCropCalculator {
    private AspectCropCalculator() {
    }

    public static CropScale calculate(
            int videoWidth,
            int videoHeight,
            int surfaceWidth,
            int surfaceHeight
    ) {
        if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return new CropScale(1f, 1f);
        }

        float videoAspect = videoWidth / (float) videoHeight;
        float surfaceAspect = surfaceWidth / (float) surfaceHeight;
        if (videoAspect > surfaceAspect) {
            return new CropScale(surfaceAspect / videoAspect, 1f);
        }
        if (videoAspect < surfaceAspect) {
            return new CropScale(1f, videoAspect / surfaceAspect);
        }
        return new CropScale(1f, 1f);
    }

    public static final class CropScale {
        public final float x;
        public final float y;

        CropScale(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
