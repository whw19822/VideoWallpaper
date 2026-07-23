package com.whw.videowallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;

public final class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "DualScreenWallpaper";

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private final class VideoEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private SurfaceHolder surfaceHolder;
        private MediaPlayer mediaPlayer;
        private WallpaperGlRenderer renderer;
        private Surface rendererInputSurface;
        private boolean surfaceReady;
        private boolean visible;
        private boolean prepared;
        private int surfaceWidth;
        private int surfaceHeight;
        private int generation;
        private ScreenRole currentRole = ScreenRole.INNER;
        private String loadedUri = "";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setOffsetNotificationsEnabled(false);
            VideoPreferences.get(VideoWallpaperService.this)
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            surfaceReady = true;
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
            surfaceWidth = width;
            surfaceHeight = height;
            if (renderer == null) {
                createRenderer(holder.getSurface(), width, height);
            } else {
                renderer.resize(width, height);
                if (rendererInputSurface != null) {
                    reloadVideo(true);
                }
            }
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            if (mediaPlayer == null) {
                drawPlaceholder(null);
            }
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (mediaPlayer == null && isVisible && surfaceReady) {
                reloadVideo(false);
                return;
            }
            if (mediaPlayer == null || !prepared) {
                return;
            }
            try {
                if (isVisible) {
                    mediaPlayer.start();
                } else if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (IllegalStateException error) {
                Log.w(TAG, "Could not update playback visibility", error);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            surfaceHolder = null;
            releasePlayer();
            releaseRenderer();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            VideoPreferences.get(VideoWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(this);
            releasePlayer();
            releaseRenderer();
            super.onDestroy();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (VideoPreferences.KEY_INNER_URI.equals(key)
                    || VideoPreferences.KEY_OUTER_URI.equals(key)
                    || VideoPreferences.KEY_SWAP_SCREENS.equals(key)) {
                reloadVideo(true);
            }
        }

        private void reloadVideo(boolean force) {
            if (!surfaceReady
                    || surfaceHolder == null
                    || renderer == null
                    || rendererInputSurface == null
                    || surfaceWidth <= 0
                    || surfaceHeight <= 0) {
                return;
            }

            Context displayContext = getDisplayContext();
            WallpaperGlRenderer currentRenderer = renderer;
            ScreenRole nextRole =
                    ScreenRoleDetector.detect(displayContext, surfaceWidth, surfaceHeight);
            String nextUri = VideoPreferences.getUri(displayContext, nextRole);
            if (!force
                    && nextRole == currentRole
                    && nextUri.equals(loadedUri)
                    && mediaPlayer != null) {
                return;
            }

            currentRole = nextRole;
            loadedUri = nextUri;
            releasePlayer();

            if (nextUri.isEmpty()) {
                drawPlaceholder(null);
                return;
            }

            final int loadGeneration = ++generation;
            final MediaPlayer candidate = new MediaPlayer();
            final Uri videoUri = Uri.parse(nextUri);
            final VideoGeometry geometry = readVideoGeometry(displayContext, videoUri);
            mediaPlayer = candidate;
            prepared = false;
            if (geometry.hasSize()) {
                currentRenderer.setVideoSize(geometry.displayWidth(), geometry.displayHeight());
            }

            try {
                candidate.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build()
                );
                candidate.setVolume(0f, 0f);
                candidate.setLooping(true);
                candidate.setSurface(rendererInputSurface);
                candidate.setDataSource(displayContext, videoUri);
                candidate.setOnVideoSizeChangedListener((player, width, height) -> {
                    if (mediaPlayer != player || renderer != currentRenderer) {
                        return;
                    }
                    if (geometry.hasSize()) {
                        currentRenderer.setVideoSize(
                                geometry.displayWidth(),
                                geometry.displayHeight()
                        );
                    } else {
                        currentRenderer.setVideoSize(width, height);
                    }
                });
                candidate.setOnPreparedListener(player -> {
                    if (mediaPlayer != player || generation != loadGeneration || !surfaceReady) {
                        safelyRelease(player);
                        return;
                    }
                    prepared = true;
                    try {
                        if (visible) {
                            player.start();
                        }
                    } catch (IllegalStateException error) {
                        handlePlaybackError(player, "视频准备失败", error);
                    }
                });
                candidate.setOnErrorListener((player, what, extra) -> {
                    handlePlaybackError(
                            player,
                            "视频无法播放",
                            new IllegalStateException("MediaPlayer error " + what + "/" + extra)
                    );
                    return true;
                });
                candidate.prepareAsync();
            } catch (IOException | RuntimeException error) {
                handlePlaybackError(candidate, "无法读取视频", error);
            }
        }

        private void createRenderer(Surface outputSurface, int width, int height) {
            final WallpaperGlRenderer newRenderer = new WallpaperGlRenderer(
                    outputSurface,
                    width,
                    height,
                    new WallpaperGlRenderer.Callback() {
                        @Override
                        public void onInputSurfaceReady(
                                WallpaperGlRenderer readyRenderer,
                                Surface inputSurface
                        ) {
                            if (renderer != readyRenderer || !surfaceReady) {
                                return;
                            }
                            rendererInputSurface = inputSurface;
                            reloadVideo(true);
                        }

                        @Override
                        public void onRendererError(
                                WallpaperGlRenderer failedRenderer,
                                Throwable error
                        ) {
                            if (renderer != failedRenderer) {
                                return;
                            }
                            Log.e(TAG, "Falling back to an error placeholder", error);
                            releasePlayer();
                            renderer = null;
                            rendererInputSurface = null;
                            failedRenderer.release();
                            drawPlaceholder("视频渲染失败");
                        }
                    }
            );
            renderer = newRenderer;
        }

        private VideoGeometry readVideoGeometry(Context context, Uri uri) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                int width = parseMetadata(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                );
                int height = parseMetadata(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                );
                int rotation = parseMetadata(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                );
                return new VideoGeometry(width, height, rotation);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not read video geometry", error);
                return VideoGeometry.UNKNOWN;
            } finally {
                try {
                    retriever.release();
                } catch (IOException | RuntimeException ignored) {
                    // Metadata failure does not prevent MediaPlayer from trying the URI.
                }
            }
        }

        private int parseMetadata(String value) {
            if (value == null) {
                return 0;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private void handlePlaybackError(
                MediaPlayer failedPlayer,
                String message,
                Throwable error
        ) {
            Log.e(TAG, message + " for " + currentRole, error);
            if (failedPlayer == mediaPlayer) {
                mediaPlayer = null;
                prepared = false;
                safelyRelease(failedPlayer);
                drawPlaceholder(message);
            } else {
                safelyRelease(failedPlayer);
            }
        }

        private void releasePlayer() {
            generation++;
            MediaPlayer player = mediaPlayer;
            mediaPlayer = null;
            prepared = false;
            if (player != null) {
                try {
                    player.setSurface(null);
                } catch (RuntimeException ignored) {
                    // The player may already be in the error state.
                }
                safelyRelease(player);
            }
        }

        private void releaseRenderer() {
            WallpaperGlRenderer activeRenderer = renderer;
            renderer = null;
            rendererInputSurface = null;
            if (activeRenderer != null) {
                activeRenderer.release();
            }
        }

        private void safelyRelease(MediaPlayer player) {
            try {
                player.reset();
            } catch (RuntimeException ignored) {
                // Release is still safe after a MediaPlayer state error.
            }
            player.release();
        }

        private void drawPlaceholder(String errorMessage) {
            if (!surfaceReady || surfaceHolder == null) {
                return;
            }
            if (renderer != null) {
                renderer.showPlaceholder();
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
                canvas.drawText(currentRole.displayName() + "视频壁纸", centerX, centerY, titlePaint);
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

    private static final class VideoGeometry {
        static final VideoGeometry UNKNOWN = new VideoGeometry(0, 0, 0);

        final int width;
        final int height;
        final int rotation;

        VideoGeometry(int width, int height, int rotation) {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }

        boolean hasSize() {
            return width > 0 && height > 0;
        }

        int displayWidth() {
            return rotation == 90 || rotation == 270 ? height : width;
        }

        int displayHeight() {
            return rotation == 90 || rotation == 270 ? width : height;
        }
    }
}
