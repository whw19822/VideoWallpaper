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
    @Test
    public void supportedSpeedsScaleFrameTimingAndLoopDurationTogether() {
        for (PlaybackSpeed speed : PlaybackSpeed.values()) {
            VideoPlaybackClock clock = new VideoPlaybackClock(speed.multiplier);
            long startNs = 1_000_000_000L;
            assertEquals(startNs, clock.targetRealtimeNs(0L, startNs));
            assertEquals(startNs + Math.round(1_000_000_000L / speed.multiplier),
                    clock.targetRealtimeNs(1_000_000L, startNs));
            clock.prepareNextLoop(8_000_000L, startNs);
            assertEquals(startNs + Math.round(8_000_000_000L / speed.multiplier),
                    clock.targetRealtimeNs(0L, startNs));
        }
    }

    @Test
    public void slowPlaybackResumeDoesNotIncludeHiddenTime() {
        VideoPlaybackClock clock = new VideoPlaybackClock(0.25);
        clock.targetRealtimeNs(0L, 1_000_000_000L);
        clock.reanchorOnNextFrame();
        assertEquals(20_000_000_000L,
                clock.targetRealtimeNs(500_000L, 20_000_000_000L));
        assertEquals(22_000_000_000L,
                clock.targetRealtimeNs(1_000_000L, 20_000_000_000L));
    }

    @Test
    public void fastPlaybackLateLoopDoesNotCatchUp() {
        VideoPlaybackClock clock = new VideoPlaybackClock(2.0);
        clock.targetRealtimeNs(0L, 1_000_000_000L);
        clock.prepareNextLoop(8_000_000L, 7_000_000_000L);
        assertEquals(7_000_000_000L, clock.targetRealtimeNs(0L, 7_000_000_000L));
    }

    @Test
    public void veryLongSlowVideoDoesNotOverflow() {
        VideoPlaybackClock clock = new VideoPlaybackClock(0.25);
        clock.targetRealtimeNs(0L, 1L);
        assertEquals(Long.MAX_VALUE, clock.targetRealtimeNs(Long.MAX_VALUE, 1L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroSpeedIsRejected() {
        new VideoPlaybackClock(0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nanSpeedIsRejected() {
        new VideoPlaybackClock(Double.NaN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void infiniteSpeedIsRejected() {
        new VideoPlaybackClock(Double.POSITIVE_INFINITY);
    }

    @Test
    public void missingOrUnknownSpeedUsesNormalPlayback() {
        assertEquals(PlaybackSpeed.NORMAL, PlaybackSpeed.fromStoredValue(null));
        assertEquals(PlaybackSpeed.NORMAL, PlaybackSpeed.fromStoredValue("invalid"));
        assertEquals(PlaybackSpeed.HALF, PlaybackSpeed.fromStoredValue("HALF"));
    }
}
