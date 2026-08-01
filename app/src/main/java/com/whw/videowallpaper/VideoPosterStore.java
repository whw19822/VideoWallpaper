package com.whw.videowallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Stores a small representative frame for each configured video.
 *
 * <p>The poster is generated off the main thread and decoded as RGB565 only
 * while a newly-created wallpaper Surface is being primed. No poster bitmap is
 * retained after it is drawn.</p>
 */
final class VideoPosterStore {
    private static final String TAG = "VideoPosterStore";
    private static final String DIRECTORY_NAME = "video-posters";
    private static final int MAX_POSTER_EDGE = 1_024;
    private static final int JPEG_QUALITY = 88;
    private static final Object QUEUE_LOCK = new Object();
    private static final ArrayDeque<String> PENDING_URIS = new ArrayDeque<>();
    private static final Set<String> QUEUED_URIS = new HashSet<>();
    private static boolean workerRunning;

    private VideoPosterStore() {
    }

    static void prewarm(Context context) {
        Context appContext = context.getApplicationContext();
        pruneUnused(appContext);
        for (ScreenRole role : ScreenRole.values()) {
            for (WallpaperMode mode : WallpaperMode.values()) {
                enqueue(appContext, VideoPreferences.getUri(appContext, role, mode));
            }
        }
    }

    static void prepare(Context context, String uriString) {
        enqueue(context.getApplicationContext(), uriString);
    }

    static Bitmap load(Context context, String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        File posterFile = posterFile(context, uriString);
        if (!posterFile.isFile() || posterFile.length() == 0L) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(posterFile.getAbsolutePath(), options);
        if (bitmap == null) {
            if (!posterFile.delete()) {
                Log.d(TAG, "Could not delete an invalid poster " + posterFile.getName());
            }
        }
        return bitmap;
    }

    static void delete(Context context, String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            return;
        }
        File posterFile = posterFile(context, uriString);
        if (posterFile.isFile() && !posterFile.delete()) {
            Log.d(TAG, "Could not delete unused poster " + posterFile.getName());
        }
    }

    private static void enqueue(Context context, String uriString) {
        if (uriString == null || uriString.isEmpty() || posterFile(context, uriString).isFile()) {
            return;
        }
        synchronized (QUEUE_LOCK) {
            if (!QUEUED_URIS.add(uriString)) {
                return;
            }
            PENDING_URIS.addLast(uriString);
            if (workerRunning) {
                return;
            }
            workerRunning = true;
        }

        Thread worker = new Thread(() -> runQueue(context), "VideoPosterGenerator");
        worker.start();
    }

    private static void runQueue(Context context) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            String uriString;
            synchronized (QUEUE_LOCK) {
                uriString = PENDING_URIS.pollFirst();
                if (uriString == null) {
                    workerRunning = false;
                    return;
                }
            }
            try {
                if (isConfigured(context, uriString)) {
                    generate(context, uriString);
                }
            } finally {
                synchronized (QUEUE_LOCK) {
                    QUEUED_URIS.remove(uriString);
                }
            }
        }
    }

    private static void generate(Context context, String uriString) {
        File outputFile = posterFile(context, uriString);
        if (outputFile.isFile()) {
            return;
        }
        File directory = outputFile.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            Log.w(TAG, "Could not create the poster cache directory");
            return;
        }

        long startedNs = System.nanoTime();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        File temporaryFile = null;
        try {
            retriever.setDataSource(context, Uri.parse(uriString));
            frame = retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MAX_POSTER_EDGE,
                    MAX_POSTER_EDGE
            );
            if (frame == null) {
                frame = retriever.getScaledFrameAtTime(
                        -1L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        MAX_POSTER_EDGE,
                        MAX_POSTER_EDGE
                );
            }
            if (frame == null) {
                Log.w(TAG, "No poster frame available for " + uriString);
                return;
            }

            temporaryFile = File.createTempFile("poster-", ".tmp", directory);
            try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                if (!frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw new IOException("Bitmap compression returned false");
                }
            }
            if (!isConfigured(context, uriString)) {
                return;
            }
            if (!temporaryFile.renameTo(outputFile)) {
                throw new IOException("Could not publish poster " + outputFile.getName());
            }
            long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
            Log.i(
                    TAG,
                    "Prepared " + frame.getWidth() + "x" + frame.getHeight()
                            + " poster in " + elapsedMs + "ms"
            );
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Could not prepare video poster", error);
        } finally {
            if (temporaryFile != null && temporaryFile.exists() && !temporaryFile.delete()) {
                Log.d(TAG, "Could not delete temporary poster " + temporaryFile.getName());
            }
            if (frame != null) {
                frame.recycle();
            }
            try {
                retriever.release();
            } catch (IOException | RuntimeException error) {
                Log.d(TAG, "Could not release poster retriever cleanly", error);
            }
        }
    }

    private static boolean isConfigured(Context context, String uriString) {
        for (ScreenRole role : ScreenRole.values()) {
            for (WallpaperMode mode : WallpaperMode.values()) {
                if (uriString.equals(VideoPreferences.getUri(context, role, mode))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void pruneUnused(Context context) {
        File directory = posterDirectory(context);
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        Set<String> activeNames = new HashSet<>();
        for (ScreenRole role : ScreenRole.values()) {
            for (WallpaperMode mode : WallpaperMode.values()) {
                String uriString = VideoPreferences.getUri(context, role, mode);
                if (!uriString.isEmpty()) {
                    activeNames.add(fileName(uriString));
                }
            }
        }
        for (File file : files) {
            if (!activeNames.contains(file.getName()) && !file.delete()) {
                Log.d(TAG, "Could not prune poster " + file.getName());
            }
        }
    }

    private static File posterFile(Context context, String uriString) {
        return new File(posterDirectory(context), fileName(uriString));
    }

    private static File posterDirectory(Context context) {
        return new File(context.getNoBackupFilesDir(), DIRECTORY_NAME);
    }

    private static String fileName(String uriString) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(uriString.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(hash.length * 2 + 7);
            name.append("v2-");
            for (byte value : hash) {
                name.append(Character.forDigit((value >>> 4) & 0xf, 16));
                name.append(Character.forDigit(value & 0xf, 16));
            }
            return name.append(".jpg").toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }
}
