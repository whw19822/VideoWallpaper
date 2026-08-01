package com.whw.videowallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.HashSet;
import java.util.Set;

public final class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "DualScreenWallpaper";
    private static final long INVISIBLE_RELEASE_DELAY_MS = 3_000L;
    private static final long POSTER_PREWARM_DELAY_MS = 2_000L;
    private final Set<VideoEngine> activeEngines = new HashSet<>();
    private Handler serviceHandler;
    private Runnable prewarmPosters;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceHandler = new Handler(Looper.getMainLooper());
        prewarmPosters = () -> VideoPosterStore.prewarm(this);
        serviceHandler.postDelayed(prewarmPosters, POSTER_PREWARM_DELAY_MS);
    }

    @Override
    public void onDestroy() {
        if (serviceHandler != null && prewarmPosters != null) {
            serviceHandler.removeCallbacks(prewarmPosters);
        }
        super.onDestroy();
    }

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        for (VideoEngine engine : new HashSet<>(activeEngines)) {
            engine.onSystemModeChanged();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        for (VideoEngine engine : new HashSet<>(activeEngines)) {
            engine.releaseInvisibleResourcesNow();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        for (VideoEngine engine : new HashSet<>(activeEngines)) {
            engine.releaseInvisibleResourcesNow();
        }
    }

    private final class VideoEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Runnable releaseHiddenDecoder = this::releaseDecoderIfInvisible;
        private SurfaceHolder surfaceHolder;
        private VideoCodecPlayer videoPlayer;
        private HandlerThread playbackThread;
        private Handler playbackHandler;
        private boolean surfaceReady;
        private boolean surfaceNeedsPoster = true;
        private boolean visible;
        private boolean reloadWhenVisible;
        private int surfaceWidth;
        private int surfaceHeight;
        private ScreenRole currentRole = ScreenRole.INNER;
        private WallpaperMode currentMode = WallpaperMode.LIGHT;
        private String loadedUri = "";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setOffsetNotificationsEnabled(false);
            holder.setFormat(PixelFormat.RGB_565);
            activeEngines.add(this);
            VideoPreferences.get(VideoWallpaperService.this)
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            surfaceReady = true;
            surfaceNeedsPoster = true;
        }

        @Override
        public void onSurfaceChanged(
                SurfaceHolder holder,
                int format,
                int width,
                int height
        ) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceHolder = holder;
            surfaceReady = true;
            if (width != surfaceWidth || height != surfaceHeight) {
                surfaceNeedsPoster = true;
            }
            surfaceWidth = width;
            surfaceHeight = height;
            if (!visible) {
                reloadWhenVisible = true;
                return;
            }
            ensurePlaybackWorker();
            reloadVideo(true);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            if (visible && videoPlayer == null) {
                if (loadedUri.isEmpty() || !drawCachedPoster(loadedUri)) {
                    drawPlaceholder(null);
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            mainHandler.removeCallbacks(releaseHiddenDecoder);
            if (isVisible) {
                if (!surfaceReady) {
                    return;
                }
                ensurePlaybackWorker();
                if (videoPlayer == null || reloadWhenVisible) {
                    reloadWhenVisible = false;
                    reloadVideo(false);
                }
                if (videoPlayer != null) {
                    videoPlayer.play();
                }
                return;
            }

            pausePlayer();
            mainHandler.postDelayed(releaseHiddenDecoder, INVISIBLE_RELEASE_DELAY_MS);
        }

        private void releaseInvisibleResourcesNow() {
            if (!visible) {
                mainHandler.removeCallbacks(releaseHiddenDecoder);
                releasePlayer();
                releasePlaybackWorker();
                Log.d(TAG, "Released all hidden wallpaper resources for " + currentRole);
            }
        }

        private void releaseDecoderIfInvisible() {
            if (!visible) {
                releasePlayer();
                releasePlaybackWorker();
                Log.d(TAG, "Released hidden video decoder for " + currentRole);
            }
        }

        private void pausePlayer() {
            if (videoPlayer != null) {
                videoPlayer.pause();
            }
        }

        private void onSystemModeChanged() {
            if (visible) {
                reloadVideo(false);
            } else {
                reloadWhenVisible = true;
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            surfaceNeedsPoster = true;
            surfaceHolder = null;
            mainHandler.removeCallbacks(releaseHiddenDecoder);
            releasePlayer();
            releasePlaybackWorker();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            mainHandler.removeCallbacks(releaseHiddenDecoder);
            activeEngines.remove(this);
            VideoPreferences.get(VideoWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(this);
            releasePlayer();
            releasePlaybackWorker();
            super.onDestroy();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (!VideoPreferences.isVideoUriKey(key)
                    && !VideoPreferences.KEY_SWAP_SCREENS.equals(key)) {
                return;
            }
            if (visible) {
                reloadVideo(true);
            } else {
                reloadWhenVisible = true;
            }
        }

        private void reloadVideo(boolean force) {
            if (!surfaceReady
                    || surfaceHolder == null
                    || !visible
                    || surfaceWidth <= 0
                    || surfaceHeight <= 0
                    || !surfaceHolder.getSurface().isValid()) {
                return;
            }

            Context displayContext = getDisplayContext();
            ScreenRole nextRole =
                    ScreenRoleDetector.detect(displayContext, surfaceWidth, surfaceHeight);
            WallpaperMode nextMode = WallpaperMode.from(displayContext);
            String nextUri =
                    VideoPreferences.getEffectiveUri(displayContext, nextRole, nextMode);
            currentMode = nextMode;
            if (!force
                    && nextRole == currentRole
                    && nextUri.equals(loadedUri)
                    && videoPlayer != null) {
                return;
            }

            currentRole = nextRole;
            releasePlayer();
            loadedUri = nextUri;

            if (nextUri.isEmpty()) {
                drawPlaceholder(null);
                return;
            }

            if (surfaceNeedsPoster) {
                if (drawCachedPoster(nextUri)) {
                    surfaceNeedsPoster = false;
                }
            }

            Handler activePlaybackHandler = playbackHandler;
            if (activePlaybackHandler == null) {
                drawPlaceholder("视频渲染未就绪");
                return;
            }

            final Uri videoUri = Uri.parse(nextUri);
            final long loadStartedNs = SystemClock.elapsedRealtimeNanos();
            final VideoCodecPlayer candidate = new VideoCodecPlayer(
                    displayContext,
                    videoUri,
                    surfaceHolder.getSurface(),
                    activePlaybackHandler,
                    visible,
                    new VideoCodecPlayer.Callback() {
                        @Override
                        public void onFirstFrameRendered(VideoCodecPlayer player) {
                            if (videoPlayer != player) {
                                return;
                            }
                            surfaceNeedsPoster = false;
                            long elapsedMs = (SystemClock.elapsedRealtimeNanos()
                                    - loadStartedNs) / 1_000_000L;
                            Log.i(
                                    TAG,
                                    "First frame for " + currentRole
                                            + " rendered in " + elapsedMs + "ms"
                            );
                            VideoPosterStore.prepare(displayContext, nextUri);
                        }

                        @Override
                        public void onPlaybackError(
                                VideoCodecPlayer player,
                                Throwable error
                        ) {
                            handlePlaybackError(player, "视频无法播放", error);
                        }
                    }
            );
            videoPlayer = candidate;
        }

        private boolean drawCachedPoster(String uriString) {
            Bitmap poster = VideoPosterStore.load(VideoWallpaperService.this, uriString);
            if (poster == null || !surfaceReady || surfaceHolder == null) {
                if (poster != null) {
                    poster.recycle();
                }
                return false;
            }

            long startedNs = SystemClock.elapsedRealtimeNanos();
            try {
                boolean drawn = WallpaperPosterRenderer.draw(
                        surfaceHolder.getSurface(),
                        poster,
                        surfaceWidth,
                        surfaceHeight
                );
                if (drawn) {
                    long elapsedMs = (SystemClock.elapsedRealtimeNanos()
                            - startedNs) / 1_000_000L;
                    Log.i(
                            TAG,
                            "Drew cached poster for " + currentRole + " in " + elapsedMs + "ms"
                    );
                }
                return drawn;
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not draw cached video poster", error);
                return false;
            } finally {
                poster.recycle();
            }
        }

        private void ensurePlaybackWorker() {
            if (playbackThread != null && playbackHandler != null) {
                return;
            }
            HandlerThread newThread = new HandlerThread(
                    "VideoWallpaperEngine",
                    Process.THREAD_PRIORITY_DISPLAY
            );
            newThread.start();
            playbackThread = newThread;
            playbackHandler = new Handler(newThread.getLooper());
        }

        private void handlePlaybackError(
                VideoCodecPlayer failedPlayer,
                String message,
                Throwable error
        ) {
            Log.e(
                    TAG,
                    message + " for " + currentRole + " in " + currentMode + " mode",
                    error
            );
            if (failedPlayer == videoPlayer) {
                videoPlayer = null;
                failedPlayer.release();
                drawPlaceholder(message);
            } else {
                failedPlayer.release();
            }
        }

        private void releasePlayer() {
            VideoCodecPlayer player = videoPlayer;
            videoPlayer = null;
            if (player != null) {
                player.release();
            }
        }

        private void releasePlaybackWorker() {
            HandlerThread activeThread = playbackThread;
            Handler activeHandler = playbackHandler;
            playbackThread = null;
            playbackHandler = null;
            if (activeThread != null && activeHandler != null) {
                activeHandler.post(activeThread::quitSafely);
            }
        }

        private void drawPlaceholder(String errorMessage) {
            if (!surfaceReady || surfaceHolder == null) {
                return;
            }
            Canvas canvas = null;
            try {
                canvas = surfaceHolder.lockCanvas();
                if (canvas == null) {
                    return;
                }
                canvas.drawColor(Color.rgb(16, 35, 28));

                Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                titlePaint.setColor(Color.WHITE);
                titlePaint.setTextAlign(Paint.Align.CENTER);
                titlePaint.setTextSize(Math.max(32f, surfaceWidth * 0.045f));

                Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                detailPaint.setColor(Color.rgb(185, 222, 208));
                detailPaint.setTextAlign(Paint.Align.CENTER);
                detailPaint.setTextSize(Math.max(20f, surfaceWidth * 0.026f));

                float centerX = canvas.getWidth() / 2f;
                float centerY = canvas.getHeight() / 2f;
                canvas.drawText(
                        currentRole.displayName() + currentMode.displayName() + "视频壁纸",
                        centerX,
                        centerY,
                        titlePaint
                );
                canvas.drawText(
                        errorMessage == null ? "请在应用中选择视频" : errorMessage,
                        centerX,
                        centerY + titlePaint.getTextSize() * 1.5f,
                        detailPaint
                );
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not draw wallpaper placeholder", error);
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas);
                    } catch (RuntimeException ignored) {
                        // Surface may have been destroyed while drawing.
                    }
                }
            }
        }
    }

}
