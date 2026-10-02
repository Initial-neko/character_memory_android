# 本地 Agent 验收协议（G0 / G1 / G2）

> 本文是执行协议，不代表 Windows 本地验收已实际通过。Android P1 的 main 是离线 Mock；P2–P4 不得因为工具检查成功就宣称已实现。

## 0. 原则、范围和状态

- 固定 Android Git SHA，建立单独 Worktree；验收 Agent 只读源码和测试产物，不改被测代码、不合并 PR。
- G0 验证 Git、JDK17、SDK、Gradle Wrapper、ADB 和唯一模拟器。
- G1 重新构建、JUnit、Compose instrumented、ADB 截图取证。
- G2 仅在 Core/Media 回环接口上读取健康和 OpenAPI Schema；不会执行写接口。
- G3 真实聊天和 SSE、G4 手机媒体、G5 发布/性能属于后续独立批次，必须重新获得相应权限。
- PASS / FAIL / BLOCKED / NOT_IMPLEMENTED / NOT_RUN / TEST_DEFECT 必须区分。出现 TEST_DEFECT 时不能将原测试记作 PASS。

本仓库验收工具仅依赖 Python 3 标准库，默认不会联网安装依赖、上传文件、发送聊天、启动媒体、重置数据库或打开公网端口。原始日志只存本地，公开 PR 只能上传**脱敏后的摘要**。

## 1. G0：本机环境与版本冻结

已有环境由用户提供，并非本项目本地运行证明：

| 项目 | 当前本地安装 | Android 本仓库要求 |
|---|---|---|
| JDK | C:\Users\cute\.jdks\liberica-17.0.20.1 | 17（系统默认可能是 8） |
| Android SDK | C:\Users\cute\AppData\Local\Android\Sdk | Platform 35 |
| Gradle | app_guard 的 9.3.0 | PR #9 固定官方 Wrapper 8.9，不能混用 |
| 模拟器 | Pixel_9 API 35 | 唯一在线 AVD，不允许同时连真机 |
| Build Tools | 34.0.0 / 36.0.0 | 是否可满足本项目构建，需实测 |

在 Git Bash 中使用两个独立 Worktree：**验收工具代码**与**被测 Android 代码**可以处于不同 PR 分支。这一点尤其重要，因为验收工具 PR 与尚未合并的 #9 并行开发；不能假设 #9 里已经有验收脚本。

~~~bash
git fetch origin
git worktree add --detach ../character_memory_android_acceptance origin/fix/p1-issue8-evidence-and-layout
git worktree add --detach ../character_memory_android_tools origin/test/local-agent-acceptance-gates
cd ../character_memory_android_tools

export JAVA_HOME="/c/Users/cute/.jdks/liberica-17.0.20.1"
export ANDROID_HOME="/c/Users/cute/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

java -version
adb devices -l
emulator -list-avds
git rev-parse HEAD
git status --short
~~~

若本地无 Pixel_9 已启动，人工运行 `emulator -avd Pixel_9`。**仅一个模拟器、没有其他设备**时才可以执行 G1，防止 Gradle 对真实手机运行自动化操作。

注意：此验收工具 PR 独立于 #9。从尚未包含 #9 的旧 main 执行 G1，因仓库缺少 Wrapper 应得到 BLOCKED；不能为了虚构 PASS 从 app_guard 复制 JAR。

## 2. G0/G1：运行与证据

本工具输出**必须位于被测 Git 工作区外面**。如果 Git Bash 的 Python 命令叫 `python` 而不是 `python3`，相应替换即可。

~~~bash
export ANDROID_TARGET="../character_memory_android_acceptance"
export ANDROID_SHA="$(git -C "$ANDROID_TARGET" rev-parse HEAD)"
export ACCEPTANCE_DIR="../acceptance-runs/p1-$ANDROID_SHA"

python3 scripts/local_acceptance.py env \
  --checkout "$ANDROID_TARGET" \
  --expected-sha "$ANDROID_SHA" \
  --output "$ACCEPTANCE_DIR/g0"

python3 scripts/local_acceptance.py p1 \
  --checkout "$ANDROID_TARGET" \
  --expected-sha "$ANDROID_SHA" \
  --output "$ACCEPTANCE_DIR/g1"
~~~

G1 使用当前仓库 Wrapper 执行带 `--rerun-tasks` 的 Lint、JUnit、Debug APK 与 instrumentation；要求构建退出码为 0、JUnit 无失败/错误/跳过。main P1 原始目标为 JVM ≥13、UI ≥3 和 9 张截图；#9 的增强版本要求 JVM ≥13、UI ≥5 和 12 张截图。目标根据被测源码而定，不把旧版本当成新版本。

截图由 `adb pull -a` 拉取并保留**设备文件的修改时间**，验收工具核对本轮时间、文件名、PNG 的 CRC/尺寸及 SHA-256。丢失、陈旧或同名后缀自动重命名（如 ` (1).png`）会失败或标记 TEST_DEFECT。避免复用前次截图造成假阳性。

**MediaStore 注意：** 模拟器多次保存同名图片，可能生成 `p1-01-chat-list (1).png`。Agent 不会主动删除旧文件。用户可先备份后**只清理模拟器测试专用截图目录** `/sdcard/Pictures/CharacterMemoryP1/`，再重跑完整 G1；禁止清理整个手机存储。

当前 P1 只支持离线 Mock；G1 PASS **不**代表真实 Core、真实音视频、媒体权限或后台录屏已通过。新的 UI 探索性问题（大字号、群聊输入法、横竖屏状态）还需由本地 Agent 单独记录截图/复现步骤。

## 3. G2：Core 只读预检查

**用户先启动** Core 和 Media Runtime。默认只允许本机 HTTP loopback，不允许该脚本通过公网或 Tailnet 地址请求。

~~~bash
python3 scripts/local_acceptance.py core-readonly \
  --checkout "$ANDROID_TARGET" \
  --expected-sha "$ANDROID_SHA" \
  --output "$ACCEPTANCE_DIR/g2" \
  --core-url "http://127.0.0.1:8000" \
  --media-url "http://127.0.0.1:8001"
~~~

G2 只发送 4 个 GET：两个服务各自 `/health`、`/openapi.json`。检查已有关键方法和路径，**不会读取真实人物、聊天记录，不会发送消息、生成图像、启动模型或订阅 SSE**。不跟随 HTTP 重定向；不记录 HTTP 响应正文；Core 未启动写 BLOCKED，Schema 与实现不符写 FAIL。

若希望额外检查正式 Tailscale Serve 路径，请由用户决定是否在 Core 仓库单独运行只读命令：

~~~bash
bash scripts/mobile-check.sh
~~~

未经允许不得运行可能启动或改写运行环境的 `mobile-start.sh`、不得修改 Serve、开 Funnel、关闭 HTTPS 校验、暴露 PC Settings/Dev 或收集密钥。Core 的设备授权和统一 Direct Conversation ID 尚属独立的 [Core #213](https://github.com/Initial-neko/character_memory/issues/213)，并不能凭 G2 成功宣布已经解决。

## 4. G3–G5 何时进行

- G3：等 Android P2 客户端落地、用户同意**隔离测试数据写入**后，执行角色 → 202 Accepted → SSE → durable history → 重连去重 → Web/Android 一致性完整链路。
- G4：在真实 Android 手机上独立测 ASR/TTS、CameraX 和用户授权 MediaProjection、锁屏/前后台、资源释放。模拟器结果不可代替真机。
- G5：稳定性、crash/ANR、内存、网络切换、发布签名。任何关键项未运行则记录 NOT_RUN。
- 重要：模型沉默可能是合法行为，不应该强制每条 202 请求立即返回 AI 消息。

如果会产生正式人物记忆、群聊、评论或模型费用的写入，Agent 必须先取得明确授权，并使用隔离数据库/测试角色；拒绝擅自清理或覆盖正式数据。

## 5. 本地报告和回溯

~~~text
acceptance-runs/<git_sha>/
  g0/acceptance.json
  g0/acceptance.md
  g1/acceptance.json
  g1/acceptance.md
  g1/logs/build.log
  g1/logs/emulator.log
  g1/logs/screenshot-pull.log
  g1/screenshots/CharacterMemoryP1/*.png
  g2/acceptance.json
  g2/acceptance.md
~~~

报告包含 Git SHA、时间、每关状态、JUnit 通过/失败数、APK 和实际截图 SHA256、日志路径。日志可能含电脑私有文件路径，必须人工脱敏后再粘贴到 GitHub。

验收 Agent 应把明确可复现问题写到独立 Issue，注明 Commit、机型、期望/实际、复现步骤和证据。修复 Agent 在独立 PR 中完成改动，最终由另一个干净 Worktree 复验，不允许在原验收目录悄悄修改后宣称“原版本通过”。

本工具的 CI 自测只验证**工具逻辑不会把缺失测试、旧截图或错误路由判为 PASS**；它不能代替真实 Windows 本地环境、真实 Core 或 Android 真机验收。
