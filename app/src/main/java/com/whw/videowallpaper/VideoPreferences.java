package com.whw.videowallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class VideoPreferences {
    public static final String FILE_NAME = "video_wallpaper";
    // Keep the original keys as the light-mode slots so existing selections survive upgrades.
    public static final String KEY_INNER_URI = "inner_video_uri";
    public static final String KEY_INNER_NAME = "inner_video_name";
    public static final String KEY_OUTER_URI = "outer_video_uri";
    public static final String KEY_OUTER_NAME = "outer_video_name";
    public static final String KEY_INNER_DARK_URI = "inner_dark_video_uri";
    public static final String KEY_INNER_DARK_NAME = "inner_dark_video_name";
    public static final String KEY_OUTER_DARK_URI = "outer_dark_video_uri";
    public static final String KEY_OUTER_DARK_NAME = "outer_dark_video_name";
    public static final String KEY_SWAP_SCREENS = "swap_screen_roles";

    private VideoPreferences() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static String uriKey(ScreenRole role, WallpaperMode mode) {
        if (mode == WallpaperMode.DARK) {
            return role == ScreenRole.INNER ? KEY_INNER_DARK_URI : KEY_OUTER_DARK_URI;
        }
        return role == ScreenRole.INNER ? KEY_INNER_URI : KEY_OUTER_URI;
    }

    private static String nameKey(ScreenRole role, WallpaperMode mode) {
        if (mode == WallpaperMode.DARK) {
            return role == ScreenRole.INNER ? KEY_INNER_DARK_NAME : KEY_OUTER_DARK_NAME;
        }
        return role == ScreenRole.INNER ? KEY_INNER_NAME : KEY_OUTER_NAME;
    }

    public static String getUri(Context context, ScreenRole role, WallpaperMode mode) {
        return get(context).getString(uriKey(role, mode), "");
    }

    public static String getName(Context context, ScreenRole role, WallpaperMode mode) {
        return get(context).getString(nameKey(role, mode), "");
    }

    public static String getEffectiveUri(
            Context context,
            ScreenRole role,
            WallpaperMode mode
    ) {
        return selectForMode(
                getUri(context, role, WallpaperMode.LIGHT),
                getUri(context, role, WallpaperMode.DARK),
                mode
        );
    }

    static String selectForMode(String lightValue, String darkValue, WallpaperMode mode) {
        String preferred = mode == WallpaperMode.DARK ? darkValue : lightValue;
        return preferred.isEmpty()
                ? (mode == WallpaperMode.DARK ? lightValue : darkValue)
                : preferred;
    }

    public static boolean hasVideo(
            Context context,
            ScreenRole role,
            WallpaperMode mode
    ) {
        return !getUri(context, role, mode).isEmpty();
    }

    public static boolean hasAnyVideo(Context context, ScreenRole role) {
        return hasVideo(context, role, WallpaperMode.LIGHT)
                || hasVideo(context, role, WallpaperMode.DARK);
    }

    public static boolean hasAnyVideo(Context context) {
        return hasAnyVideo(context, ScreenRole.INNER)
                || hasAnyVideo(context, ScreenRole.OUTER);
    }

    public static void setVideo(
            Context context,
            ScreenRole role,
            WallpaperMode mode,
            Uri uri,
            String name
    ) {
        String oldUri = getUri(context, role, mode);
        get(context)
                .edit()
                .putString(uriKey(role, mode), uri.toString())
                .putString(nameKey(role, mode), name)
                .apply();
        if (!oldUri.equals(uri.toString())) {
            releasePermissionIfUnused(context, oldUri);
        }
    }

    public static void clearVideo(
            Context context,
            ScreenRole role,
            WallpaperMode mode
    ) {
        String oldUri = getUri(context, role, mode);
        get(context)
                .edit()
                .remove(uriKey(role, mode))
                .remove(nameKey(role, mode))
                .apply();
        releasePermissionIfUnused(context, oldUri);
    }

    public static boolean isVideoUriKey(String key) {
        return KEY_INNER_URI.equals(key)
                || KEY_OUTER_URI.equals(key)
                || KEY_INNER_DARK_URI.equals(key)
                || KEY_OUTER_DARK_URI.equals(key);
    }

    private static void releasePermissionIfUnused(Context context, String uri) {
        if (uri.isEmpty()) {
            return;
        }
        for (ScreenRole role : ScreenRole.values()) {
            for (WallpaperMode mode : WallpaperMode.values()) {
                if (uri.equals(getUri(context, role, mode))) {
                    return;
                }
            }
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The provider may not expose a persisted grant; the preference is still updated.
        }
    }

    public static boolean areScreenRolesSwapped(Context context) {
        return get(context).getBoolean(KEY_SWAP_SCREENS, false);
    }

    public static void setScreenRolesSwapped(Context context, boolean swapped) {
        get(context).edit().putBoolean(KEY_SWAP_SCREENS, swapped).apply();
    }
}
