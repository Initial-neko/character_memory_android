# P1：在自己的电脑运行 Android 原型

> 适用范围：`feature/p1-compose-mock-prototype` / [Draft PR #7](https://github.com/Initial-neko/character_memory_android/pull/7)。**此原型不连 Core/Tailscale、不访问互联网，也不申请麦克风/摄像头/录屏权限。**

## 最省事的方式：先下载 CI 构建的 APK

1. 打开 [P1 GitHub Actions](https://github.com/Initial-neko/character_memory_android/actions/workflows/android-p1.yml)。
2. 选择**最新的成功**工作流，确认是你要验收的 PR/Commit。
3. 下载 `p1-build-unit-evidence`，解压，找到 `app/build/outputs/apk/debug/app-debug.apk`。
4. 在 Android 真机或模拟器安装这个 **Debug** 包；界面上有 MOCK 标志，**不是真实 Character Memory 客户端**。
5. 如果该 CI 失败或没有 APK，**不要使用上一个提交的 APK 当作本次验证结果**。

## Android Studio 本地开发环境

- 安装 Android Studio（带 Android SDK Manager / Device Manager）。
- SDK Manager 中安装 Android SDK Platform 35、Android SDK Build-Tools 35 和 Android Emulator。
- 项目构建使用 **JDK 17**、**Gradle 8.9**、**AGP 8.7.2**、**Kotlin 2.1.21**。不要自行混用不兼容版本。
- 此 P1 bootstrap 暂未提交 `gradle-wrapper.jar` / `gradlew`；需先安装 Gradle 8.9 并运行 `gradle`，或由项目下一阶段提交完整且经过校验的官方 Wrapper 文件。**不要复制来源不明的 Wrapper JAR。**

在终端执行：

```bash
gradle --version
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
```

成功时 Debug APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 模拟器（P1 最有价值）

1. Android Studio → Tools → Device Manager → Create Device。
2. 推荐 Pixel 6 + Google APIs API 35 x86_64（主机架构不同可选择可用设备镜像）。
3. 启动 AVD，在 Android Studio 选择 app 并运行。
4. 依次检查：聊天列表 → 人物创建 → 群聊创建 → 聊天 → 通话布局 → Space → 设置。
5. 通过 `gradle --no-daemon connectedDebugAndroidTest` 执行仪器化测试；需要先保证模拟器运行。
6. 截图由 UI Test 保存至应用外部文件目录 `Pictures/p1/`，CI 自动尝试导出。报告按 `docs/P1_ACCEPTANCE.md` 检查。

ADB（已在 `platform-tools` 中）也能安装 APK：

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -d > android-p1-logcat.txt
```

如果 `adb devices` 没列出设备，优先检查模拟器是否完成启动。不要将构建成功等同于模拟器通过；更不能将模拟器通过等同于真实 Camera/Mic/Screen 验收。

## 后端何时需要启动？

P1：**不需要 PC Core，也不需要 Tailscale。** 全部固定 Mock，专注布局和交互。

P2：开始接真实 API 前，先在 Core 仓库完成身份、Direct 会话一致性与 API 契约；再运行 PC `mobile-start.sh` 和 `mobile-check.sh`，最后做真实 202→SSE→历史对账。

P3/P4：才使用真机音频、摄像头、MediaProjection 授权及完整系统生命周期测试。

## 提交验收结果

每次验收请保存五个字段：APK 对应 commit、Android API/机型、场景、PASS/FAIL/NOT RUN、截图或 logcat 证据路径。**没有运行就填 NOT RUN，不要主观补 PASS。**
