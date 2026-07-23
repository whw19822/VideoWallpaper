package com.whw.videowallpaper;

public enum ScreenRole {
    INNER,
    OUTER;

    public ScreenRole opposite() {
        return this == INNER ? OUTER : INNER;
    }

    public String displayName() {
        return this == INNER ? "内屏" : "外屏";
    }
}
