# BA Recorder

A lightweight Android screen recorder app. It captures only the screen (no microphone) and saves MP4 videos to the public Movies/BA_Recorder folder so they appear in your Gallery.

- Min Android version: 7.0 (API 24)
- Target Android version: 14/15
- Video location: Movies/BA_Recorder (public)

## Install directly (GitHub Releases)
- Go to the repository’s Releases page and download the latest APK: [Releases](../../releases)
- On your phone, open the APK and allow “Install unknown apps” for your browser/file manager if prompted.
- If installation fails due to signature mismatch, uninstall older debug builds before installing this one.

## Install on your phone (source build)

You can install via Android Studio (recommended) or ADB.

### Option A: Android Studio
- Enable Developer options and USB debugging on your phone
  - Settings > About phone > Software information > tap "Build number" 7 times
  - Settings > Developer options > enable "USB debugging"
- Connect the phone via USB. Pick "File Transfer" if prompted.
- In Android Studio: select your device in the device chooser, then click Run.

### Option B: ADB (command line)
```bash
# In the project root
./gradlew :app:assembleDebug   # Windows: gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run & permissions
- Android 13+ (Tiramisu): Allow Notifications so the foreground service notification can show.
- Android 14+ (Upside Down Cake): The app starts a mediaProjection foreground service before recording. Do not block notifications; you should see a persistent "Recording" notification when recording.
- Android 9 and below: You will be asked for Storage permission to save to the public Movies folder.
- Screen capture consent: When you tap "Start Recording", accept the system screen capture dialogs (including "Start now").

## How to use
- Open the app. You’ll see a centered "Start Recording" button.
- Tap "Start Recording" and allow the system capture prompts.
- While recording, the app shows only "Stop & Save". Tap it to finish.
- Where are videos?
  - Android 10+ (API 29+): Gallery (Videos) automatically; file path is Internal storage/Movies/BA_Recorder/Screen_yyyyMMdd_HHmmss.mp4
  - Android 9 and below: Same folder; the app triggers a media scan so it appears in Gallery.

## Troubleshooting
- Run button is disabled in Android Studio: Select a connected device (or install drivers), then Run.
- "Stop & Save" not visible: It appears only after recording actually starts (after authorizing screen capture).
- Error: "MediaProjection requires a foreground service"
  - Ensure notifications are enabled for this app.
  - Use this updated build; you should see a persistent notification when recording.
- Error: "Must register a callback before start capturing": Use this build; the callback is registered before capturing.
- UI looks blank / buttons missing: A system consent dialog may be in front; check recent apps or notifications and approve.

### Maintainers: CI to auto-build APK
- GitHub Actions workflow is provided at `.github/workflows/build-apk.yml`.
- Triggers:
  - Push a tag like `v1.0.0` (recommended) — it builds and attaches the APK to the Release automatically.
  - Or run it manually via “Run workflow” to get an artifact (Debug APK).
- Output files:
  - Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`.
  - For tag builds, the APK is uploaded to the corresponding GitHub Release.

---

# 屏幕录制应用（BA Recorder）

一个轻量的安卓屏幕录制应用，只录屏幕（不采集麦克风），将 MP4 视频保存到公共的 Movies/BA_Recorder 文件夹，便于在相册中查看。

- 最低系统版本：Android 7.0（API 24）
- 目标系统版本：Android 14/15
- 视频位置：Movies/BA_Recorder（公共目录）

## 直接下载安装（GitHub Releases）
- 前往本仓库的 Releases 页面下载最新 APK：[Releases](../../releases)
- 在手机上打开 APK 安装，如提示“未知来源/从此来源安装”，请为浏览器或文件管理器授予安装权限。
- 如果提示签名不一致导致安装失败，请先卸载旧的调试版再安装此版本。

## 在手机上安装（本地构建）

可通过 Android Studio（推荐）或 ADB 安装。

### 方式 A：Android Studio
- 启用开发者选项与 USB 调试：
  - 设置 > 关于手机 > 软件信息 > 连续点“版本号”7次
  - 设置 > 开发者选项 > 打开“USB 调试”
- 用数据线连接手机（如提示选择“文件传输”）。
- 在 Android Studio 选择你的设备，点击运行（Run）。

### 方式 B：ADB 命令行
```bash
# 进入项目根目录
./gradlew :app:assembleDebug   # Windows 用 gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 首次运行与权限
- Android 13+：请允许“通知”权限，以显示前台服务通知。
- Android 14+：应用会在录屏前启动 mediaProjection 前台服务，录制时通知栏会有“正在录屏”的常驻通知，请不要关闭通知。
- Android 9 及以下：会请求“存储”权限以写入公共 Movies 目录。
- 录屏授权：点击“开始录屏”后，系统会弹出录屏授权与“立即开始”，请允许。

## 使用方法
- 打开应用，界面中央是“开始录屏”。
- 点击“开始录屏”，依提示同意系统授权。
- 录制中界面只显示“停止并保存”，点击即可结束并保存。
- 视频保存位置：
  - Android 10+：相册（视频/影片）可直接看到；实际路径为 内部存储/Movies/BA_Recorder/Screen_yyyyMMdd_HHmmss.mp4。
  - Android 9 及以下：同一路径；应用会触发媒体扫描以便出现在相册。

## 常见问题
- Android Studio 运行按钮灰色：需先选中已连接的真机或模拟器（或安装驱动），再运行。
- 看不到“停止并保存”：只有在真正开始录制后才显示，未授权或启动失败会保持“开始录屏”。
- 报错“需要前台服务”：请确保已允许“通知”，录制时应看到“正在录屏”的常驻通知。
- 报错“Must register a callback…”：本版本已提前注册回调，请使用当前构建。
- 界面空白或按钮不见：可能是系统授权弹窗遮挡，请在最近任务或通知栏找到并允许。

### 维护者：使用 CI 自动出包
- 仓库已提供 GitHub Actions 工作流：`.github/workflows/build-apk.yml`
- 触发方式：
  - 推送形如 `v1.0.0` 的 Tag（推荐）：自动构建并将 APK 附加到对应 Release。
  - 或在 Actions 页面点击 “Run workflow” 手动触发，生成调试 APK 工件。
- 产物：
  - 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`
  - 推 Tag 的构建会把 APK 上传到该 Tag 的 Release。
