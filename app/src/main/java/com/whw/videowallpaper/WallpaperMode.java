package com.whw.videowallpaper;

import android.content.Context;
import android.content.res.Configuration;

public enum WallpaperMode {
    LIGHT,
    DARK;

    public static WallpaperMode from(Context context) {
        return fromUiMode(context.getResources().getConfiguration().uiMode);
    }

    static WallpaperMode fromUiMode(int uiMode) {
        int nightMode = uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }

    public WallpaperMode opposite() {
        return this == LIGHT ? DARK : LIGHT;
    }

    public String displayName() {
        return this == LIGHT ? "浅色" : "深色";
    }
}
