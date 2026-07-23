package com.whw.videowallpaper;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int REQUEST_INNER_VIDEO = 100;
    private static final int REQUEST_OUTER_VIDEO = 101;

    private TextView innerVideoStatus;
    private TextView outerVideoStatus;
    private Button clearInnerButton;
    private Button clearOuterButton;
    private TextView wallpaperStatus;
    private TextView displayStatus;
    private Switch swapSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(color(R.color.app_background));
        getWindow().setNavigationBarColor(color(R.color.app_background));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(color(R.color.app_background));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dp(isExpanded() ? 40 : 20);
        int contentTopPadding = dp(24);
        int contentBottomPadding = dp(40);
        content.setPadding(
                horizontalPadding,
                contentTopPadding,
                horizontalPadding,
                contentBottomPadding
        );
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            applySystemBarInsets(
                    view,
                    insets,
                    horizontalPadding,
                    contentTopPadding,
                    contentBottomPadding
            );
            return insets;
        });
        scrollView.addView(
                content,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        wallpaperStatus = label("", 13, color(R.color.app_primary_dark));
        wallpaperStatus.setGravity(Gravity.CENTER);
        wallpaperStatus.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams statusParams = wrapParams();
        statusParams.gravity = Gravity.START;
        content.addView(wallpaperStatus, statusParams);

        TextView title = label("双屏动态壁纸", 32, color(R.color.app_text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        addWithTopMargin(content, title, 18);

        TextView subtitle = label(
                "内屏和外屏，各自播放喜欢的视频。",
                17,
                color(R.color.app_text_secondary)
        );
        subtitle.setLineSpacing(0, 1.2f);
        addWithTopMargin(content, subtitle, 8);

        LinearLayout infoBanner = new LinearLayout(this);
        infoBanner.setOrientation(LinearLayout.VERTICAL);
        infoBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        infoBanner.setBackground(roundedBackground(color(R.color.app_info), 18, 0, 0));
        TextView infoTitle = label("已启用 Android 多显示屏壁纸模式", 14, color(R.color.app_primary_dark));
        infoTitle.setTypeface(infoTitle.getTypeface(), android.graphics.Typeface.BOLD);
        infoBanner.addView(infoTitle);
        TextView infoText = label(
                "双 Display 机型可同时运行两个视频；复用单 Display 的机型会在折叠与展开时自动切换。",
                13,
                color(R.color.app_primary_dark)
        );
        infoText.setLineSpacing(0, 1.18f);
        addWithTopMargin(infoBanner, infoText, 5);
        addWithTopMargin(content, infoBanner, 24);

        TextView sectionTitle = label("分别选择视频", 20, color(R.color.app_text));
        sectionTitle.setTypeface(sectionTitle.getTypeface(), android.graphics.Typeface.BOLD);
        addWithTopMargin(content, sectionTitle, 30);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(isExpanded() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        addWithTopMargin(content, cards, 14);

        View innerCard = createVideoCard(ScreenRole.INNER);
        View outerCard = createVideoCard(ScreenRole.OUTER);
        if (isExpanded()) {
            cards.addView(innerCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Space gap = new Space(this);
            cards.addView(gap, new LinearLayout.LayoutParams(dp(16), 1));
            cards.addView(outerCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            cards.addView(innerCard, matchWrapParams());
            Space gap = new Space(this);
            cards.addView(gap, new LinearLayout.LayoutParams(1, dp(14)));
            cards.addView(outerCard, matchWrapParams());
        }

        LinearLayout mappingCard = new LinearLayout(this);
        mappingCard.setOrientation(LinearLayout.VERTICAL);
        mappingCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        mappingCard.setBackground(roundedBackground(color(R.color.app_surface), 22, 0, 0));
        mappingCard.setElevation(dp(1));

        swapSwitch = new Switch(this);
        swapSwitch.setText("交换内屏与外屏识别");
        swapSwitch.setTextSize(16);
        swapSwitch.setTextColor(color(R.color.app_text));
        swapSwitch.setShowText(false);
        swapSwitch.setGravity(Gravity.CENTER_VERTICAL);
        swapSwitch.setPadding(0, 0, 0, 0);
        swapSwitch.setOnCheckedChangeListener((button, checked) -> {
            VideoPreferences.setScreenRolesSwapped(this, checked);
            refreshDisplayStatus();
        });
        mappingCard.addView(swapSwitch, matchWrapParams());

        TextView mappingHint = label(
                "如果实际播放与标注相反，打开这个开关即可立即纠正。",
                13,
                color(R.color.app_text_secondary)
        );
        mappingHint.setLineSpacing(0, 1.18f);
        addWithTopMargin(mappingCard, mappingHint, 7);

        displayStatus = label("", 13, color(R.color.app_text_secondary));
        displayStatus.setLineSpacing(0, 1.2f);
        addWithTopMargin(mappingCard, displayStatus, 14);
        addWithTopMargin(content, mappingCard, 18);

        Button applyButton = primaryButton("预览并设为动态壁纸");
        applyButton.setOnClickListener(view -> openWallpaperPreview());
        LinearLayout.LayoutParams applyParams = matchWrapParams();
        applyParams.topMargin = dp(22);
        applyButton.setLayoutParams(applyParams);
        content.addView(applyButton);

        TextView footer = label(
                "视频将静音循环播放，并仅在壁纸可见时占用播放资源。建议选用与屏幕方向一致的短视频。",
                12,
                color(R.color.app_text_secondary)
        );
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(0, 1.18f);
        addWithTopMargin(content, footer, 14);

        return scrollView;
    }

    private View createVideoCard(ScreenRole role) {
        boolean inner = role == ScreenRole.INNER;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundedBackground(color(R.color.app_surface), 24, 0, 0));
        card.setElevation(dp(1));

        FrameLayout iconContainer = new FrameLayout(this);
        iconContainer.setBackground(roundedBackground(color(R.color.app_info), 16, 0, 0));
        ImageView icon = new ImageView(this);
        icon.setImageResource(inner ? R.drawable.ic_inner_screen : R.drawable.ic_outer_screen);
        icon.setContentDescription(inner ? "内屏图标" : "外屏图标");
        int iconPadding = dp(10);
        icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        iconContainer.addView(
                icon,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
        card.addView(iconContainer, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView roleLabel = label(
                inner ? "展开后" : "折叠后",
                12,
                color(R.color.app_primary)
        );
        roleLabel.setTypeface(roleLabel.getTypeface(), android.graphics.Typeface.BOLD);
        roleLabel.setLetterSpacing(0.08f);
        addWithTopMargin(card, roleLabel, 16);

        TextView cardTitle = label(
                inner ? "内屏视频" : "外屏视频",
                22,
                color(R.color.app_text)
        );
        cardTitle.setTypeface(cardTitle.getTypeface(), android.graphics.Typeface.BOLD);
        addWithTopMargin(card, cardTitle, 3);

        TextView videoStatus = label("尚未选择", 14, color(R.color.app_text_secondary));
        videoStatus.setMaxLines(2);
        videoStatus.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        videoStatus.setMinHeight(dp(45));
        addWithTopMargin(card, videoStatus, 10);

        Button chooseButton = secondaryButton(inner ? "选择内屏视频" : "选择外屏视频");
        chooseButton.setOnClickListener(view -> chooseVideo(role));
        addWithTopMargin(card, chooseButton, 14);

        Button clearButton = textButton("清除已选视频");
        clearButton.setOnClickListener(view -> {
            VideoPreferences.clearVideo(this, role);
            refreshUi();
        });
        addWithTopMargin(card, clearButton, 4);

        if (inner) {
            innerVideoStatus = videoStatus;
            clearInnerButton = clearButton;
        } else {
            outerVideoStatus = videoStatus;
            clearOuterButton = clearButton;
        }
        return card;
    }

    private void chooseVideo(ScreenRole role) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(
                intent,
                role == ScreenRole.INNER ? REQUEST_INNER_VIDEO : REQUEST_OUTER_VIDEO
        );
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode != REQUEST_INNER_VIDEO && requestCode != REQUEST_OUTER_VIDEO) {
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException error) {
            Toast.makeText(
                    this,
                    "这个文件来源无法长期授权，请从“文件”中重新选择",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        ScreenRole role =
                requestCode == REQUEST_INNER_VIDEO ? ScreenRole.INNER : ScreenRole.OUTER;
        VideoPreferences.setVideo(this, role, uri, queryDisplayName(uri));
        Toast.makeText(this, role.displayName() + "视频已更新", Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // URI is still valid even if its provider does not expose a display name.
        }
        return "已选择视频";
    }

    private void openWallpaperPreview() {
        if (!VideoPreferences.hasVideo(this, ScreenRole.INNER)
                && !VideoPreferences.hasVideo(this, ScreenRole.OUTER)) {
            Toast.makeText(this, "请先至少选择一个视频", Toast.LENGTH_SHORT).show();
            return;
        }

        ComponentName component = new ComponentName(this, VideoWallpaperService.class);
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        preview.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(preview);
        } catch (ActivityNotFoundException error) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException secondError) {
                Toast.makeText(this, "当前系统没有可用的动态壁纸选择器", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshUi() {
        updateVideoStatus(ScreenRole.INNER, innerVideoStatus, clearInnerButton);
        updateVideoStatus(ScreenRole.OUTER, outerVideoStatus, clearOuterButton);

        boolean active = isWallpaperActive();
        wallpaperStatus.setText(active ? "●  当前正在使用" : "○  尚未启用");
        wallpaperStatus.setTextColor(
                color(active ? R.color.app_primary_dark : R.color.app_text_secondary)
        );
        wallpaperStatus.setBackground(
                roundedBackground(
                        color(active ? R.color.app_info : R.color.app_divider),
                        14,
                        0,
                        0
                )
        );

        boolean swapped = VideoPreferences.areScreenRolesSwapped(this);
        if (swapSwitch.isChecked() != swapped) {
            swapSwitch.setOnCheckedChangeListener(null);
            swapSwitch.setChecked(swapped);
            swapSwitch.setOnCheckedChangeListener((button, checked) -> {
                VideoPreferences.setScreenRolesSwapped(this, checked);
                refreshDisplayStatus();
            });
        }
        refreshDisplayStatus();
    }

    private void updateVideoStatus(ScreenRole role, TextView status, Button clearButton) {
        boolean configured = VideoPreferences.hasVideo(this, role);
        status.setText(configured ? VideoPreferences.getName(this, role) : "尚未选择视频");
        status.setTextColor(
                color(configured ? R.color.app_primary_dark : R.color.app_text_secondary)
        );
        clearButton.setVisibility(configured ? View.VISIBLE : View.INVISIBLE);
    }

    private boolean isWallpaperActive() {
        WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        return info != null
                && new ComponentName(this, VideoWallpaperService.class).equals(info.getComponent());
    }

    @SuppressWarnings("deprecation")
    private void refreshDisplayStatus() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        ScreenRole role = ScreenRoleDetector.detect(this, size.x, size.y);

        DisplayManager displayManager =
                (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        int activeDisplayCount = 0;
        if (displayManager != null) {
            for (Display display : displayManager.getDisplays()) {
                if (display.getState() != Display.STATE_OFF) {
                    activeDisplayCount++;
                }
            }
        }

        String displayCountText = activeDisplayCount > 1
                ? getString(R.string.display_count_multiple, activeDisplayCount)
                : getString(R.string.display_count_single);
        displayStatus.setText(getString(
                R.string.display_status,
                role.displayName(),
                size.x,
                size.y,
                displayCountText
        ));
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets(
            View view,
            WindowInsets insets,
            int horizontalPadding,
            int contentTopPadding,
            int contentBottomPadding
    ) {
        view.setPadding(
                horizontalPadding + insets.getSystemWindowInsetLeft(),
                contentTopPadding + insets.getSystemWindowInsetTop(),
                horizontalPadding + insets.getSystemWindowInsetRight(),
                contentBottomPadding + insets.getSystemWindowInsetBottom()
        );
    }

    private boolean isExpanded() {
        return getResources().getConfiguration().screenWidthDp >= 720;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(color(R.color.app_on_primary));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(56));
        button.setBackground(
                rippleBackground(
                        color(R.color.app_primary),
                        Color.argb(45, 255, 255, 255),
                        18,
                        0,
                        0
                )
        );
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(color(R.color.app_primary_dark));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setBackground(
                rippleBackground(
                        Color.TRANSPARENT,
                        Color.argb(26, 23, 107, 81),
                        15,
                        dp(1),
                        color(R.color.app_divider)
                )
        );
        return button;
    }

    private Button textButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(color(R.color.app_text_secondary));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        return button;
    }

    private TextView label(String text, float sizeSp, int textColor) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(sizeSp);
        textView.setTextColor(textColor);
        return textView;
    }

    private void addWithTopMargin(LinearLayout parent, View child, int marginDp) {
        LinearLayout.LayoutParams params = matchWrapParams();
        params.topMargin = dp(marginDp);
        parent.addView(child, params);
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private RippleDrawable rippleBackground(
            int fillColor,
            int rippleColor,
            int radiusDp,
            int strokeWidth,
            int strokeColor
    ) {
        return new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                roundedBackground(fillColor, radiusDp, strokeWidth, strokeColor),
                null
        );
    }

    private GradientDrawable roundedBackground(
            int fillColor,
            int radiusDp,
            int strokeWidth,
            int strokeColor
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private int color(int resourceId) {
        return getResources().getColor(resourceId, getTheme());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
