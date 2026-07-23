package com.whw.videowallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class VideoPreferences {
    public static final String FILE_NAME = "video_wallpaper";
    public static final String KEY_INNER_URI = "inner_video_uri";
    public static final String KEY_INNER_NAME = "inner_video_name";
    public static final String KEY_OUTER_URI = "outer_video_uri";
    public static final String KEY_OUTER_NAME = "outer_video_name";
    public static final String KEY_SWAP_SCREENS = "swap_screen_roles";

    private VideoPreferences() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static String uriKey(ScreenRole role) {
        return role == ScreenRole.INNER ? KEY_INNER_URI : KEY_OUTER_URI;
    }

    private static String nameKey(ScreenRole role) {
        return role == ScreenRole.INNER ? KEY_INNER_NAME : KEY_OUTER_NAME;
    }

    public static String getUri(Context context, ScreenRole role) {
        return get(context).getString(uriKey(role), "");
    }

    public static String getName(Context context, ScreenRole role) {
        return get(context).getString(nameKey(role), "");
    }

    public static boolean hasVideo(Context context, ScreenRole role) {
        return !getUri(context, role).isEmpty();
    }

    public static void setVideo(Context context, ScreenRole role, Uri uri, String name) {
        get(context)
                .edit()
                .putString(uriKey(role), uri.toString())
                .putString(nameKey(role), name)
                .apply();
    }

    public static void clearVideo(Context context, ScreenRole role) {
        String oldUri = getUri(context, role);
        ScreenRole otherRole = role.opposite();
        get(context)
                .edit()
                .remove(uriKey(role))
                .remove(nameKey(role))
                .apply();

        if (!oldUri.isEmpty() && !oldUri.equals(getUri(context, otherRole))) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(oldUri),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (SecurityException ignored) {
                // The provider may not expose a persisted grant; the preference is still cleared.
            }
        }
    }

    public static boolean areScreenRolesSwapped(Context context) {
        return get(context).getBoolean(KEY_SWAP_SCREENS, false);
    }

    public static void setScreenRolesSwapped(Context context, boolean swapped) {
        get(context).edit().putBoolean(KEY_SWAP_SCREENS, swapped).apply();
    }
}
