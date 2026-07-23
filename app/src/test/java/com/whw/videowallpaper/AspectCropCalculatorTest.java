package com.whw.videowallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AspectCropCalculatorTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void wideVideoOnPortraitScreenCropsHorizontally() {
        AspectCropCalculator.CropScale scale =
                AspectCropCalculator.calculate(1920, 1080, 1080, 1920);

        assertEquals(0.31640625f, scale.x, EPSILON);
        assertEquals(1f, scale.y, EPSILON);
    }

    @Test
    public void portraitVideoOnWideScreenCropsVertically() {
        AspectCropCalculator.CropScale scale =
                AspectCropCalculator.calculate(720, 1280, 2208, 1840);

        assertEquals(1f, scale.x, EPSILON);
        assertEquals(0.46875f, scale.y, EPSILON);
    }

    @Test
    public void matchingAspectRatioDoesNotCrop() {
        AspectCropCalculator.CropScale scale =
                AspectCropCalculator.calculate(1920, 1080, 1280, 720);

        assertEquals(1f, scale.x, EPSILON);
        assertEquals(1f, scale.y, EPSILON);
    }
}
