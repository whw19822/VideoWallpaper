package com.whw.videowallpaper;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal local-video player for a wallpaper surface.
 *
 * <p>Only the first video track is selected. Audio, subtitles, player UI,
 * playlists, and software decoder extensions are deliberately absent. The
 * decoder renders directly into the supplied wallpaper Surface and is
 * synchronously driven on the Engine's dedicated worker.</p>
 */
final class VideoCodecPlayer {
    private static final String TAG = "VideoCodecPlayer";
    // These format keys existed in codec output before their public constants.
    private static final String FORMAT_KEY_CROP_LEFT = "crop-left";
    private static final String FORMAT_KEY_CROP_RIGHT = "crop-right";
    private static final String FORMAT_KEY_CROP_TOP = "crop-top";
    private static final String FORMAT_KEY_CROP_BOTTOM = "crop-bottom";
    private static final String FORMAT_KEY_LOW_LATENCY = "low-latency";
    private static final long OUTPUT_POLL_TIMEOUT_US = 2_000L;
    private static final long IDLE_POLL_DELAY_MS = 4L;
    private static final long RELEASE_EARLY_NS = 16_000_000L;
    private static final long DROP_LATE_FRAME_NS = 100_000_000L;
    private static final int MAX_INPUTS_PER_PUMP = 4;
    private static final int MAX_OUTPUTS_PER_PUMP = 4;

    interface Callback {
        void onPlaybackError(VideoCodecPlayer player, Throwable error);
    }

    private static final class DecoderCandidate {
        final String name;
        final boolean hardwareAccelerated;
        final boolean lowLatencySupported;

        DecoderCandidate(
                String name,
                boolean hardwareAccelerated,
                boolean lowLatencySupported
        ) {
            this.name = name;
            this.hardwareAccelerated = hardwareAccelerated;
            this.lowLatencySupported = lowLatencySupported;
        }
    }

    private static final class PendingOutput {
        final int index;
        final long targetRealtimeNs;
        final boolean endOfStream;

        PendingOutput(
                int index,
                long targetRealtimeNs,
                boolean endOfStream
        ) {
            this.index = index;
            this.targetRealtimeNs = targetRealtimeNs;
            this.endOfStream = endOfStream;
        }
    }

    private final Context context;
    private final Uri uri;
    private final Surface outputSurface;
    private final Handler workerHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final VideoPlaybackClock playbackClock = new VideoPlaybackClock();
    private final MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
    private final Runnable pumpRunnable = this::pump;

    private volatile boolean releaseRequested;
    private MediaExtractor extractor;
    private MediaCodec codec;
    private PendingOutput pendingOutput;
    private boolean initialized;
    private boolean playing;
    private boolean inputEnded;
    private boolean firstFrameRendered;
    private boolean errorReported;
    private long durationUs;

    VideoCodecPlayer(
            Context context,
            Uri uri,
            Surface outputSurface,
            Handler workerHandler,
            boolean playWhenReady,
            Callback callback
    ) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.outputSurface = outputSurface;
        this.workerHandler = workerHandler;
        this.playing = playWhenReady;
        this.callback = callback;
        workerHandler.post(this::initialize);
    }

    void play() {
        workerHandler.post(() -> {
            if (releaseRequested || playing) {
                return;
            }
            playing = true;
            playbackClock.reanchorOnNextFrame();
            if (initialized) {
                schedulePump(0L);
            }
        });
    }

    void pause() {
        workerHandler.removeCallbacks(pumpRunnable);
        workerHandler.post(() -> {
            if (releaseRequested) {
                return;
            }
            playing = false;
            workerHandler.removeCallbacks(pumpRunnable);
            discardPendingOutput();
            playbackClock.reanchorOnNextFrame();
        });
    }

    void release() {
        if (releaseRequested) {
            return;
        }
        releaseRequested = true;
        workerHandler.removeCallbacks(pumpRunnable);
        workerHandler.post(this::releaseInternal);
    }

    private void initialize() {
        if (outputSurfaceUnavailable()) {
            return;
        }
        try {
            MediaExtractor newExtractor = new MediaExtractor();
            extractor = newExtractor;
            newExtractor.setDataSource(context, uri, null);

            int videoTrackIndex = findVideoTrack(newExtractor);
            if (videoTrackIndex < 0) {
                throw new IOException("The selected file has no video track");
            }
            newExtractor.selectTrack(videoTrackIndex);
            MediaFormat videoFormat = newExtractor.getTrackFormat(videoTrackIndex);
            durationUs = getLong(videoFormat, MediaFormat.KEY_DURATION, 0L);
            logVideoSize(videoFormat);

            configureDecoder(videoFormat);
            if (codec == null) {
                releaseInternal();
                return;
            }
            initialized = true;
            if (playing) {
                schedulePump(0L);
            }
        } catch (IOException | RuntimeException error) {
            fail(error);
        }
    }

    private int findVideoTrack(MediaExtractor mediaExtractor) {
        for (int index = 0; index < mediaExtractor.getTrackCount(); index++) {
            MediaFormat format = mediaExtractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return index;
            }
        }
        return -1;
    }

    private void logVideoSize(MediaFormat format) {
        int width = getInteger(format, MediaFormat.KEY_WIDTH, 0);
        int height = getInteger(format, MediaFormat.KEY_HEIGHT, 0);
        if (format.containsKey(FORMAT_KEY_CROP_LEFT)
                && format.containsKey(FORMAT_KEY_CROP_RIGHT)) {
            width = format.getInteger(FORMAT_KEY_CROP_RIGHT)
                    - format.getInteger(FORMAT_KEY_CROP_LEFT) + 1;
        }
        if (format.containsKey(FORMAT_KEY_CROP_TOP)
                && format.containsKey(FORMAT_KEY_CROP_BOTTOM)) {
            height = format.getInteger(FORMAT_KEY_CROP_BOTTOM)
                    - format.getInteger(FORMAT_KEY_CROP_TOP) + 1;
        }

        int rotation = getInteger(format, MediaFormat.KEY_ROTATION, 0);
        final int displayWidth = rotation == 90 || rotation == 270 ? height : width;
        final int displayHeight = rotation == 90 || rotation == 270 ? width : height;
        if (displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        Log.i(
                TAG,
                "Video track " + width + "x" + height
                        + " rotation=" + rotation
                        + " display=" + displayWidth + "x" + displayHeight
        );
    }

    private void configureDecoder(MediaFormat format) throws IOException {
        if (outputSurfaceUnavailable()) {
            return;
        }
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) {
            throw new IOException("Video track is missing its MIME type");
        }

        List<DecoderCandidate> candidates = findDecoderCandidates(format, mime);
        if (candidates.isEmpty()) {
            throw new IOException("No decoder supports " + mime);
        }

        Throwable lastError = null;
        for (DecoderCandidate candidate : candidates) {
            if (outputSurfaceUnavailable()) {
                return;
            }
            if (candidate.lowLatencySupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    startDecoder(candidate, format, true);
                    return;
                } catch (IOException | RuntimeException error) {
                    if (cancelStaleSurface()) {
                        return;
                    }
                    lastError = error;
                    Log.w(TAG, "Low-latency decoder setup failed for " + candidate.name, error);
                    releaseCodec();
                }
            }
            try {
                startDecoder(candidate, format, false);
                return;
            } catch (IOException | RuntimeException error) {
                if (cancelStaleSurface()) {
                    return;
                }
                lastError = error;
                Log.w(TAG, "Decoder setup failed for " + candidate.name, error);
                releaseCodec();
            }
        }

        IOException failure = new IOException("No usable decoder for " + mime);
        if (lastError != null) {
            failure.initCause(lastError);
        }
        throw failure;
    }

    private boolean cancelStaleSurface() {
        if (!outputSurfaceUnavailable()) {
            return false;
        }
        releaseCodec();
        return true;
    }

    private boolean outputSurfaceUnavailable() {
        return releaseRequested || !outputSurface.isValid();
    }

    private List<DecoderCandidate> findDecoderCandidates(
            MediaFormat format,
            String mime
    ) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String preferredName = codecList.findDecoderForFormat(format);
        List<DecoderCandidate> candidates = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (info.isEncoder() || info.isAlias() || !supportsType(info, mime)) {
                continue;
            }
            try {
                MediaCodecInfo.CodecCapabilities capabilities =
                        info.getCapabilitiesForType(mime);
                if (!capabilities.isFormatSupported(format)) {
                    continue;
                }
                boolean lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && capabilities.isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                );
                candidates.add(new DecoderCandidate(
                        info.getName(),
                        info.isHardwareAccelerated(),
                        lowLatency
                ));
                names.add(info.getName());
            } catch (IllegalArgumentException error) {
                Log.d(TAG, "Ignoring incompatible decoder " + info.getName(), error);
            }
        }

        candidates.sort(Comparator
                .comparing((DecoderCandidate candidate) -> !candidate.hardwareAccelerated)
                .thenComparing(candidate -> !candidate.name.equals(preferredName))
                .thenComparing(candidate -> candidate.name));

        if (preferredName != null && !names.contains(preferredName)) {
            candidates.add(new DecoderCandidate(preferredName, false, false));
        }
        return candidates;
    }

    private boolean supportsType(MediaCodecInfo info, String mime) {
        for (String supportedType : info.getSupportedTypes()) {
            if (supportedType.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    private void startDecoder(
            DecoderCandidate candidate,
            MediaFormat sourceFormat,
            boolean enableLowLatency
    ) throws IOException {
        MediaFormat decoderFormat = new MediaFormat(sourceFormat);
        if (enableLowLatency) {
            decoderFormat.setInteger(FORMAT_KEY_LOW_LATENCY, 1);
        } else {
            decoderFormat.removeKey(FORMAT_KEY_LOW_LATENCY);
        }

        MediaCodec newCodec = MediaCodec.createByCodecName(candidate.name);
        codec = newCodec;
        newCodec.configure(decoderFormat, outputSurface, null, 0);
        newCodec.start();
        applyScalingMode(newCodec);
        Log.i(
                TAG,
                "Using " + candidate.name
                        + " (hardware=" + candidate.hardwareAccelerated
                        + ", lowLatency=" + enableLowLatency + ")"
        );
    }

    private void pump() {
        if (releaseRequested || !playing || !initialized || codec == null) {
            return;
        }
        try {
            if (pendingOutput != null && !releasePendingOutputIfDue()) {
                return;
            }
            feedInputBuffers();
            drainOutputBuffers();
            if (playing && pendingOutput == null) {
                schedulePump(IDLE_POLL_DELAY_MS);
            }
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private void feedInputBuffers() {
        if (inputEnded || codec == null || extractor == null) {
            return;
        }
        for (int count = 0; count < MAX_INPUTS_PER_PUMP && !inputEnded; count++) {
            int inputIndex = codec.dequeueInputBuffer(0L);
            if (inputIndex < 0) {
                return;
            }
            ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                throw new IllegalStateException("Decoder returned a null input buffer");
            }
            inputBuffer.clear();
            int sampleSize = extractor.readSampleData(inputBuffer, 0);
            if (sampleSize < 0) {
                codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                );
                inputEnded = true;
                return;
            }

            int sampleFlags = extractor.getSampleFlags();
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                throw new IllegalArgumentException("Encrypted video is not supported");
            }
            int codecFlags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
                    ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    : 0;
            codec.queueInputBuffer(
                    inputIndex,
                    0,
                    sampleSize,
                    extractor.getSampleTime(),
                    codecFlags
            );
            extractor.advance();
        }
    }

    private void drainOutputBuffers() {
        if (codec == null || pendingOutput != null) {
            return;
        }
        for (int count = 0; count < MAX_OUTPUTS_PER_PUMP; count++) {
            int outputIndex = codec.dequeueOutputBuffer(
                    outputInfo,
                    count == 0 ? OUTPUT_POLL_TIMEOUT_US : 0L
            );
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                applyScalingMode(codec);
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            boolean endOfStream = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            boolean codecConfig = (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean hasFrame = outputInfo.size > 0 && !codecConfig;
            if (!hasFrame) {
                codec.releaseOutputBuffer(outputIndex, false);
                if (endOfStream) {
                    restartLoop();
                    return;
                }
                continue;
            }

            long nowNs = System.nanoTime();
            long targetNs = playbackClock.targetRealtimeNs(
                    outputInfo.presentationTimeUs,
                    nowNs
            );
            pendingOutput = new PendingOutput(
                    outputIndex,
                    targetNs,
                    endOfStream
            );
            if (!releasePendingOutputIfDue()) {
                return;
            }
            if (endOfStream) {
                return;
            }
        }
    }

    private void applyScalingMode(MediaCodec targetCodec) {
        targetCodec.setVideoScalingMode(
                MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        );
    }

    private boolean releasePendingOutputIfDue() {
        PendingOutput output = pendingOutput;
        if (output == null || codec == null) {
            return true;
        }

        long nowNs = System.nanoTime();
        long waitNs = output.targetRealtimeNs - nowNs - RELEASE_EARLY_NS;
        if (waitNs > 0L) {
            long delayMs = Math.max(1L, (waitNs + 999_999L) / 1_000_000L);
            schedulePump(delayMs);
            return false;
        }

        boolean drop = firstFrameRendered
                && nowNs - output.targetRealtimeNs > DROP_LATE_FRAME_NS;
        if (drop) {
            codec.releaseOutputBuffer(output.index, false);
        } else if (output.targetRealtimeNs > nowNs) {
            codec.releaseOutputBuffer(output.index, output.targetRealtimeNs);
            firstFrameRendered = true;
        } else {
            codec.releaseOutputBuffer(output.index, true);
            firstFrameRendered = true;
        }
        pendingOutput = null;

        if (output.endOfStream) {
            restartLoop();
        }
        return true;
    }

    private void restartLoop() {
        if (codec == null || extractor == null) {
            return;
        }
        playbackClock.prepareNextLoop(durationUs, System.nanoTime());
        codec.flush();
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        inputEnded = false;
    }

    private void schedulePump(long delayMs) {
        workerHandler.removeCallbacks(pumpRunnable);
        if (delayMs <= 0L) {
            workerHandler.post(pumpRunnable);
        } else {
            workerHandler.postDelayed(pumpRunnable, delayMs);
        }
    }

    private void discardPendingOutput() {
        PendingOutput output = pendingOutput;
        pendingOutput = null;
        if (output != null && codec != null) {
            try {
                codec.releaseOutputBuffer(output.index, false);
            } catch (RuntimeException error) {
                Log.d(TAG, "Could not discard a pending decoder buffer", error);
            }
        }
    }

    private void fail(Throwable error) {
        if (releaseRequested || errorReported) {
            releaseInternal();
            return;
        }
        errorReported = true;
        Log.e(TAG, "Video decoding failed", error);
        releaseInternal();
        mainHandler.post(() -> callback.onPlaybackError(this, error));
    }

    private void releaseInternal() {
        playing = false;
        initialized = false;
        workerHandler.removeCallbacks(pumpRunnable);
        discardPendingOutput();
        releaseCodec();
        MediaExtractor activeExtractor = extractor;
        extractor = null;
        if (activeExtractor != null) {
            activeExtractor.release();
        }
    }

    private void releaseCodec() {
        MediaCodec activeCodec = codec;
        codec = null;
        if (activeCodec != null) {
            try {
                activeCodec.release();
            } catch (RuntimeException error) {
                Log.d(TAG, "Could not release decoder cleanly", error);
            }
        }
    }

    private static int getInteger(MediaFormat format, String key, int fallback) {
        return format.containsKey(key) ? format.getInteger(key) : fallback;
    }

    private static long getLong(MediaFormat format, String key, long fallback) {
        return format.containsKey(key) ? format.getLong(key) : fallback;
    }
}
