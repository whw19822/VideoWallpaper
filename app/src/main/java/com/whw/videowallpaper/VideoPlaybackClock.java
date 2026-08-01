package com.whw.videowallpaper;

/**
 * Maps video presentation timestamps to {@link System#nanoTime()} while keeping
 * pause/resume and loop transitions independent from wall-clock time.
 */
final class VideoPlaybackClock {
    private static final long UNSET = Long.MIN_VALUE;

    private long anchorMediaTimeUs = UNSET;
    private long anchorRealtimeNs = UNSET;
    private long nextLoopRealtimeNs = UNSET;

    long targetRealtimeNs(long presentationTimeUs, long nowNs) {
        if (anchorMediaTimeUs == UNSET) {
            anchorMediaTimeUs = presentationTimeUs;
            anchorRealtimeNs = nextLoopRealtimeNs == UNSET
                    ? nowNs
                    : Math.max(nowNs, nextLoopRealtimeNs);
            nextLoopRealtimeNs = UNSET;
        }

        long deltaUs = Math.max(0L, presentationTimeUs - anchorMediaTimeUs);
        return saturatedAdd(anchorRealtimeNs, saturatedMultiplyByOneThousand(deltaUs));
    }

    void prepareNextLoop(long durationUs, long nowNs) {
        if (anchorMediaTimeUs != UNSET && durationUs > anchorMediaTimeUs) {
            long loopDurationNs = saturatedMultiplyByOneThousand(
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

    private static long saturatedMultiplyByOneThousand(long value) {
        if (value > Long.MAX_VALUE / 1_000L) {
            return Long.MAX_VALUE;
        }
        return value * 1_000L;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
