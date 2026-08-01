package com.whw.videowallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PosterCropCalculatorTest {
    @Test
    public void widePosterOnPortraitSurfaceCropsSides() {
        PosterCropCalculator.Crop crop =
                PosterCropCalculator.calculate(1920, 1080, 1080, 1920);

        assertEquals(656, crop.left);
        assertEquals(0, crop.top);
        assertEquals(1264, crop.right);
        assertEquals(1080, crop.bottom);
    }

    @Test
    public void tallPosterOnWideSurfaceCropsTopAndBottom() {
        PosterCropCalculator.Crop crop =
                PosterCropCalculator.calculate(720, 1280, 2208, 1840);

        assertEquals(0, crop.left);
        assertEquals(340, crop.top);
        assertEquals(720, crop.right);
        assertEquals(940, crop.bottom);
    }

    @Test
    public void matchingAspectUsesWholePoster() {
        PosterCropCalculator.Crop crop =
                PosterCropCalculator.calculate(1920, 1080, 1280, 720);

        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals(1920, crop.right);
        assertEquals(1080, crop.bottom);
    }
}
