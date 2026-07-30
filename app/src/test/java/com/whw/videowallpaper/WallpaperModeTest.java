package com.whw.videowallpaper;

import android.content.res.Configuration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class WallpaperModeTest {
    @Test
    public void mapsNightConfigurationToDarkMode() {
        assertEquals(
                WallpaperMode.DARK,
                WallpaperMode.fromUiMode(Configuration.UI_MODE_NIGHT_YES)
        );
    }

    @Test
    public void mapsDayAndUndefinedConfigurationToLightMode() {
        assertEquals(
                WallpaperMode.LIGHT,
                WallpaperMode.fromUiMode(Configuration.UI_MODE_NIGHT_NO)
        );
        assertEquals(
                WallpaperMode.LIGHT,
                WallpaperMode.fromUiMode(Configuration.UI_MODE_NIGHT_UNDEFINED)
        );
    }

    @Test
    public void configuredModeFallsBackToTheOtherVideo() {
        assertEquals(
                "light.mp4",
                VideoPreferences.selectForMode(
                        "light.mp4",
                        "",
                        WallpaperMode.DARK
                )
        );
        assertEquals(
                "dark.mp4",
                VideoPreferences.selectForMode(
                        "",
                        "dark.mp4",
                        WallpaperMode.LIGHT
                )
        );
        assertEquals(
                "dark.mp4",
                VideoPreferences.selectForMode(
                        "light.mp4",
                        "dark.mp4",
                        WallpaperMode.DARK
                )
        );
    }
}
