package com.whw.videowallpaper;

/** Stable preference values and labels for the supported playback speeds. */
enum PlaybackSpeed {
    QUARTER(0.25, "0.25×"),
    HALF(0.5, "0.5×"),
    THREE_QUARTERS(0.75, "0.75×"),
    NORMAL(1.0, "1×"),
    ONE_AND_QUARTER(1.25, "1.25×"),
    ONE_AND_HALF(1.5, "1.5×"),
    DOUBLE(2.0, "2×");

    final double multiplier;
    final String label;

    PlaybackSpeed(double multiplier, String label) {
        this.multiplier = multiplier;
        this.label = label;
    }

    static PlaybackSpeed fromStoredValue(String value) {
        for (PlaybackSpeed speed : values()) {
            if (speed.name().equals(value)) {
                return speed;
            }
        }
        return NORMAL;
    }
}
