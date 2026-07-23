package com.whw.videowallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ScreenRoleClassifierTest {
    @Test
    public void nearSquareUnfoldedDisplayIsInnerScreen() {
        assertEquals(ScreenRole.INNER, ScreenRoleClassifier.fromDimensions(2208, 1840));
    }

    @Test
    public void narrowCoverDisplayIsOuterScreen() {
        assertEquals(ScreenRole.OUTER, ScreenRoleClassifier.fromDimensions(904, 2316));
    }

    @Test
    public void rotationDoesNotChangeRole() {
        assertEquals(
                ScreenRoleClassifier.fromDimensions(1080, 2376),
                ScreenRoleClassifier.fromDimensions(2376, 1080)
        );
    }

    @Test
    public void invalidSurfaceDefaultsToInnerScreen() {
        assertEquals(ScreenRole.INNER, ScreenRoleClassifier.fromDimensions(0, 0));
    }
}
