package com.whw.videowallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VideoPlaybackClockTest {
    @Test
    public void firstFrameAnchorsToCurrentRealtime() {
        VideoPlaybackClock clock = new VideoPlaybackClock();

        assertEquals(5_000_000L, clock.targetRealtimeNs(700_000L, 5_000_000L));
    }

    @Test
    public void laterFramesPreservePresentationTimestampDelta() {
        VideoPlaybackClock clock = new VideoPlaybackClock();
        clock.targetRealtimeNs(1_000_000L, 10_000_000L);

        assertEquals(
                510_000_000L,
                clock.targetRealtimeNs(1_500_000L, 20_000_000L)
        );
    }

    @Test
    public void resumeReanchorsWithoutIncludingHiddenTime() {
        VideoPlaybackClock clock = new VideoPlaybackClock();
        clock.targetRealtimeNs(1_000_000L, 10_000_000L);
        clock.reanchorOnNextFrame();

        assertEquals(
                9_000_000_000L,
                clock.targetRealtimeNs(1_500_000L, 9_000_000_000L)
        );
    }

    @Test
    public void loopStartsAtOriginalDurationBoundary() {
        VideoPlaybackClock clock = new VideoPlaybackClock();
        clock.targetRealtimeNs(0L, 1_000_000_000L);
        clock.prepareNextLoop(10_000_000L, 10_900_000_000L);

        assertEquals(
                11_000_000_000L,
                clock.targetRealtimeNs(0L, 10_950_000_000L)
        );
    }

    @Test
    public void lateLoopRestartsImmediatelyInsteadOfCatchingUp() {
        VideoPlaybackClock clock = new VideoPlaybackClock();
        clock.targetRealtimeNs(0L, 1_000_000_000L);
        clock.prepareNextLoop(2_000_000L, 5_000_000_000L);

        assertEquals(
                5_000_000_000L,
                clock.targetRealtimeNs(0L, 5_000_000_000L)
        );
    }
}
