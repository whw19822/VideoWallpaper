package com.whw.videowallpaper;

/**
 * Maps video presentation timestamps to {@link System#nanoTime()} while keeping
 * pause/resume and loop transitions independent from wall-clock time.
 */
final class VideoPlaybackClock {
    private static final long UNSET = Long.MIN_VALUE;

    private final double playbackSpeed;

    private long anchorMediaTimeUs = UNSET;
    private long anchorRealtimeNs = UNSET;
    private long nextLoopRealtimeNs = UNSET;

    VideoPlaybackClock() {
        this(1.0);
    }

    VideoPlaybackClock(double playbackSpeed) {
        if (!Double.isFinite(playbackSpeed) || playbackSpeed <= 0.0) {
            throw new IllegalArgumentException("Playback speed must be finite and positive");
        }
        this.playbackSpeed = playbackSpeed;
    }

    long targetRealtimeNs(long presentationTimeUs, long nowNs) {
        if (anchorMediaTimeUs == UNSET) {
            anchorMediaTimeUs = presentationTimeUs;
            anchorRealtimeNs = nextLoopRealtimeNs == UNSET
                    ? nowNs
                    : Math.max(nowNs, nextLoopRealtimeNs);
            nextLoopRealtimeNs = UNSET;
        }

        long deltaUs = Math.max(0L, presentationTimeUs - anchorMediaTimeUs);
        return saturatedAdd(anchorRealtimeNs, scaledDurationNs(deltaUs));
    }

    void prepareNextLoop(long durationUs, long nowNs) {
        if (anchorMediaTimeUs != UNSET && durationUs > anchorMediaTimeUs) {
            long loopDurationNs = scaledDurationNs(
                    durationUs - anchorMediaTimeUs
            );
            nextLoopRealtimeNs = Math.max(
                    nowNs,
                    saturatedAdd(anchorRealtimeNs, loopDurationNs)
            );
        } else {
            nextLoopRealtimeNs = nowNs;
        }
        anchorMediaTimeUs = UNSET;
        anchorRealtimeNs = UNSET;
    }

    void reanchorOnNextFrame() {
        anchorMediaTimeUs = UNSET;
        anchorRealtimeNs = UNSET;
        nextLoopRealtimeNs = UNSET;
    }

    private long scaledDurationNs(long durationUs) {
        double durationNs = durationUs * (1_000.0 / playbackSpeed);
        return durationNs >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(durationNs);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
