# 双屏视频动态壁纸

面向 Android 折叠屏的原生视频动态壁纸 App。内屏和外屏可以各自选择一个视频；视频静音、循环播放，并在壁纸不可见时暂停。

## 功能

- 内屏、外屏分别选择视频，使用 SAF 持久化文件访问权限
- OpenGL 等比填充并居中裁切，保持视频原始比例、不拉伸
- 屏幕隐藏后释放硬件解码器并保留最后一帧，返回桌面时无黑屏重载
- 使用不带透明通道的 RGB565 壁纸缓冲，降低常驻图形内存
- 声明 `android:supportsMultipleDisplays="true"`，系统允许时为每块屏幕创建独立的壁纸引擎
- 同时兼容两种折叠屏实现：
  - 内外屏是两个逻辑 Display：两个引擎可同时播放不同视频
  - 内外屏复用 Display 0：根据 Surface 比例在折叠/展开时自动切换视频
- 内外屏识别相反时可一键交换
- 设置界面会在展开宽度下自动使用双栏布局

## Debug 构建

需要 JDK 17 和 Android SDK 37：

```bash
export ANDROID_HOME=/Users/whw/Library/Android/sdk
export GRADLE_USER_HOME=/tmp/video-wallpaper-gradle-home
./gradlew :app:assembleDebug
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release 构建

首次构建先生成本机正式签名密钥：

```bash
./scripts/generate-release-keystore.sh
```

脚本会创建被 Git 忽略的 `release-keystore.jks` 和
`keystore.properties`。请安全备份这两个文件；丢失签名密钥后将无法发布
同一应用的后续更新。

生成已签名 APK 和 Android App Bundle：

```bash
export ANDROID_HOME=/Users/whw/Library/Android/sdk
export GRADLE_USER_HOME=/tmp/video-wallpaper-gradle-home
./gradlew :app:assembleRelease :app:bundleRelease
```

产物：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

构建版本可通过 Gradle 参数覆盖：

```bash
./gradlew :app:bundleRelease \
  -PappVersionCode=2 \
  -PappVersionName=1.1.0
```

CI 环境也可以不使用 `keystore.properties`，改为提供
`VIDEO_WALLPAPER_STORE_FILE`、`VIDEO_WALLPAPER_STORE_PASSWORD`、
`VIDEO_WALLPAPER_KEY_ALIAS` 和 `VIDEO_WALLPAPER_KEY_PASSWORD`。

## 使用

1. 打开 App，分别选择内屏和外屏视频。
2. 点击“预览并设为动态壁纸”。
3. 在系统预览页确认应用。
4. 如果实际屏幕与视频对应关系相反，回到 App 打开“交换内屏与外屏识别”。

## 系统兼容边界

Android 10 起提供多显示屏壁纸框架，但副显示屏是否允许动态壁纸仍由设备厂商的系统配置决定。App 已实现 AOSP 要求的多显示屏声明和逐 Display 引擎；若厂商系统禁用了副屏动态壁纸，普通应用无法绕过这一限制。

建议使用设备硬件解码支持的 MP4/H.264 视频，并让视频方向接近对应屏幕比例，以降低裁切和功耗。
