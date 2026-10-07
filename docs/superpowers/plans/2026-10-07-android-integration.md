# Android 昨日与今日改动集成计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一个可重现分支和 APK 同时保留动态 Live2D、通话状态界面、RSS 页面以及已有聊天功能。

**Architecture:** 在现有 RSS 隔离工作树创建集成分支，以已提交 RSS 为基础，应用原开发目录相对 main 的最终代码快照；原目录的暂存和未暂存状态保留。通话分支已由 #25 squash 合并进 main，经文件差异核对无独有产品改动。

**Tech Stack:** Kotlin/Compose、WebView、既有 Core API、JDK17、离线 Gradle 8.9。

**Spec:** 用户本轮要求“把昨天和今天的改动都搞到一个分支”，以及此前已验收 Live2D 与 RSS 的功能约定。

## Global Constraints

- 保留用户原工作区与手机数据；相同验收签名，覆盖安装。
- 不默认新增、下载或升级依赖，不改正式 Core 数据库。
- 同时验收真实页面入口、动态渲染器、通话状态与 RSS，而非单一新增功能。
- 安装前核对 Git 状态、APK 内容、签名和哈希；机器可读记录来源。

## Review Focus

- 合并 LiveApp 冲突时，RSS 入口与通话状态栏都必须存在。
- 动态渲染器代码、HTML asset 与 debug probe 的组合不得遗漏。
- 在 RSS 页面中收起的通话应保持同一会话与状态。
- 缺真实 Core 参数的 GPU 测试不得计为成功。
- 最终安装包必须有动态 Live2D 与 RSS；原工作区快照不能被修改或丢失。

### Task 1: 汇总来源及解决合并冲突

**Files:** 原目录 git diff HEAD 的全部产品、文档、技能脚本改动，以及 `src/debug`、`LiveCallStatus.kt`；排除本地诊断 `mask-bridge-test.json`。

- [ ] 保存暂存/未暂存补丁、文件快照与 SHA256 清单。
- [ ] 三方应用原目录最终快照，局部解决冲突，同时保留 RSS 与通话状态。
- [ ] 对原目录与集成分支逐文件核对，记录必要差异；追加 RSS 页面保持通话的既有回归用例。
- [ ] 提交统一代码和来源记录。

### Task 2: 构建与组合验收

**Files:** 既有 JVM/UI/GPU 测试、`scripts/run_emulator_acceptance.sh`、APK 输出与 delivery 证据。

- [ ] 离线执行 JVM、Lint、app/test APK 构建；既有脚本与 Live2D 技能脚本测试。
- [ ] 模拟器验证 RSS、通话状态及动态 renderer 容器；真实模型 GPU 验收与 fixture 分别记录。
- [ ] 核对 APK 中 Live2D HTML、renderer、RSS 类和功能入口，进行独立只读审查。

### Task 3: 安装和交接

- [ ] 核对唯一手机、验收签名和 APK SHA256，保留数据覆盖安装并复制到 Download。
- [ ] 核实安装后包名、版本和设备 APK 哈希；可行时验证真实 Live2D，不把缺环境计为通过。
- [ ] 关闭本次验收服务，记录统一分支、提交、验证结果和边界；按用户已有授权提交 PR、检查通过后合并。

## 执行结果（用户调整验收范围）

源快照已保存并应用；原工作区保留。离线构建、199 个 JVM 用例、37 个脚本用例、24 个 Live2D 技能脚本用例通过，Lint 0 errors / 9 warnings。APK 已核对包含动态 Live2D、通话状态及 RSS，并保留数据覆盖安装到唯一手机，安装包与设备包 SHA256 一致。用户明确要求停止追加测试、直接安装并提交 PR，故后续 UI、真实模型 GPU、真机音视频验收未执行；本次模拟器已关闭。

