# P1：复用本机 Android 开发环境（Windows）

> P1 [PR #7](https://github.com/Initial-neko/character_memory_android/pull/7) 已合并。当前 P2 普通启动是真实模式，连接配置见 [P2_SETUP.md](P2_SETUP.md)；`p1_mock=true` 是独立离线演示入口。尚无录音/摄像头/录屏授权。最新验收须查看对应 commit 的 CI 产物。

## 已有环境（用户提供，非本次 CI 实测）

| 环境 | 已知安装 | 本仓库要求 |
|---|---|---|
| Java | `C:\Users\cute\.jdks\liberica-17.0.20.1` | **JDK 17**；终端默认 JDK8 必须覆盖 |
| Android SDK | `C:\Users\cute\AppData\Local\Android\Sdk` | compileSdk 35；Android SDK Platform 35 已有 |
| Gradle | app_guard Wrapper 9.3.0；`C:\Users\cute\.gradle` | 本仓库单独固定 **Wrapper 8.9**（AGP 8.7.2） |
| AVD | Pixel_9，API 35，x86_64 | 可直接用于 P1 视觉验证 |
| Build Tools | 已安装 34.0.0 / 36.0.0 | 本项目 CI 使用 35.0.0；本地离线构建需先核对插件实际选择的 Build Tools |

**复用现有 JDK、SDK 和模拟器**。不要混用 app_guard 的 Gradle9.3.0/AGP9.0.1 Wrapper。`--offline` 只在 Gradle8.9 分发包、项目依赖及 Build Tools 缓存完整时成功；缺失即停止并报告阻塞，不擅自联网、下载、升级或安装。已知本机缺 Gradle8.9，本次 Android 执行证据由既有 GitHub CI 产生。

## Git Bash（推荐，**只修改当前终端会话**）

```bash
export JAVA_HOME="/c/Users/cute/.jdks/liberica-17.0.20.1"
export ANDROID_HOME="/c/Users/cute/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

"$JAVA_HOME/bin/java" -version        # 必须显示 17.x
./gradlew --version                    # 本仓库应显示 Gradle 8.9
./gradlew --offline --no-daemon lintDebug testDebugUnitTest assembleDebug
```

若你使用 Windows CMD，则使用仓库的 `gradlew.bat`，并通过 Android Studio 的 Gradle JDK 设置或当前终端显式配置 JDK 17；不要让默认 JDK 8 接管构建。此仓库已带 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 及其 checksum-pinned properties。

## 使用 Pixel_9 模拟器

```bash
emulator -list-avds
emulator -avd Pixel_9                 # 首次启动需要稍等
adb devices                           # 确保 emulator-xxxx 显示为 device
./gradlew --no-daemon connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -d > android-p1-logcat.txt
```

CI 已将截图保存到 `p1-emulator-ui-evidence`，路径 `/sdcard/Pictures/CharacterMemoryP1/`。关键 UI 自动化包括聊天、建人、建群、群聊、Space、生图弹窗以及窄屏 / 横屏 / 输入法布局。**模拟器实际截图 ≠ 设计 PNG**。

## 不安装 Android Studio 也能验收

1. 打开 [GitHub P1 Workflow](https://github.com/Initial-neko/character_memory_android/actions/workflows/android-p1.yml)。
2. 选择**最新成功**的对应提交，下载 `p1-build-unit-evidence` 解压找到 `app-debug.apk`。
3. 查看 `p1-emulator-ui-evidence` 中真实截图与 JUnit 结果。
4. 安装 APK，明确区分 `MOCK` 页面与真实 API 连接；异常请附 Android 版本、机型、步骤、截图/日志。

## P2 与后续阶段

P2 已增加 INTERNET / HTTPS / SSE；设备认证属于 Core 拟议能力，不能称为已实现。P3/P4 才增加原生媒体能力及权限。Backend API [由 Core 维护](https://github.com/Initial-neko/character_memory/blob/508c6f0/docs/current/MOBILE_API_CONTRACT.md)。

**证据标签：PASS / FAIL / NOT RUN。** 不能把其它项目的离线构建成功直接记作这个仓库的本地验收通过。
