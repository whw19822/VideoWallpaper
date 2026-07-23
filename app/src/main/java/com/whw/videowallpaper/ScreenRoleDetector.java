package com.whw.videowallpaper;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.Set;

public final class ScreenRoleDetector {
    private ScreenRoleDetector() {
    }

    public static ScreenRole detect(Context displayContext, int surfaceWidth, int surfaceHeight) {
        ScreenRole role = detectUnswapped(displayContext, surfaceWidth, surfaceHeight);
        return VideoPreferences.areScreenRolesSwapped(displayContext) ? role.opposite() : role;
    }

    @SuppressWarnings("deprecation")
    private static ScreenRole detectUnswapped(
            Context displayContext,
            int surfaceWidth,
            int surfaceHeight
    ) {
        Display currentDisplay = null;
        WindowManager windowManager =
                (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            currentDisplay = windowManager.getDefaultDisplay();
        }

        DisplayManager displayManager =
                (DisplayManager) displayContext.getSystemService(Context.DISPLAY_SERVICE);
        if (currentDisplay != null && displayManager != null) {
            ScreenRole multiDisplayRole = detectFromActiveBuiltInDisplays(
                    displayManager,
                    currentDisplay.getDisplayId()
            );
            if (multiDisplayRole != null) {
                return multiDisplayRole;
            }
        }

        return ScreenRoleClassifier.fromDimensions(surfaceWidth, surfaceHeight);
    }

    private static ScreenRole detectFromActiveBuiltInDisplays(
            DisplayManager displayManager,
            int currentDisplayId
    ) {
        Set<Integer> presentationDisplayIds = new HashSet<>();
        for (Display display
                : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            presentationDisplayIds.add(display.getDisplayId());
        }

        Display smallest = null;
        Display largest = null;
        long smallestArea = Long.MAX_VALUE;
        long largestArea = Long.MIN_VALUE;
        int activeDisplayCount = 0;

        for (Display display : displayManager.getDisplays()) {
            if (display.getState() == Display.STATE_OFF
                    || presentationDisplayIds.contains(display.getDisplayId())) {
                continue;
            }
            Display.Mode mode = display.getMode();
            long area = (long) mode.getPhysicalWidth() * mode.getPhysicalHeight();
            if (area <= 0) {
                continue;
            }
            activeDisplayCount++;
            if (area < smallestArea) {
                smallestArea = area;
                smallest = display;
            }
            if (area > largestArea) {
                largestArea = area;
                largest = display;
            }
        }

        if (activeDisplayCount < 2 || smallest == null || largest == null
                || smallest.getDisplayId() == largest.getDisplayId()) {
            return null;
        }
        if (currentDisplayId == largest.getDisplayId()) {
            return ScreenRole.INNER;
        }
        if (currentDisplayId == smallest.getDisplayId()) {
            return ScreenRole.OUTER;
        }
        return null;
    }
}
