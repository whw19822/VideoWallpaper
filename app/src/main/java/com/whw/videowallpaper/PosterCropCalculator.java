package com.whw.videowallpaper;

/** Calculates a centered source crop that fills a destination without stretching. */
final class PosterCropCalculator {
    private PosterCropCalculator() {
    }

    static Crop calculate(
            int sourceWidth,
            int sourceHeight,
            int destinationWidth,
            int destinationHeight
    ) {
        if (sourceWidth <= 0
                || sourceHeight <= 0
                || destinationWidth <= 0
                || destinationHeight <= 0) {
            return new Crop(0, 0, Math.max(0, sourceWidth), Math.max(0, sourceHeight));
        }

        long sourceScaled = (long) sourceWidth * destinationHeight;
        long destinationScaled = (long) destinationWidth * sourceHeight;
        if (sourceScaled > destinationScaled) {
            int cropWidth = Math.max(
                    1,
                    Math.round(sourceHeight * destinationWidth / (float) destinationHeight)
            );
            int left = (sourceWidth - cropWidth) / 2;
            return new Crop(left, 0, left + cropWidth, sourceHeight);
        }
        if (sourceScaled < destinationScaled) {
            int cropHeight = Math.max(
                    1,
                    Math.round(sourceWidth * destinationHeight / (float) destinationWidth)
            );
            int top = (sourceHeight - cropHeight) / 2;
            return new Crop(0, top, sourceWidth, top + cropHeight);
        }
        return new Crop(0, 0, sourceWidth, sourceHeight);
    }

    static final class Crop {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Crop(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
