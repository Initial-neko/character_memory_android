# APK 集成交付规则

验收 APK 从**已合并 main 的对应成功 CI**获取。PR APK 仅供分支测试，不作为已集成版本交付。交付前记录 source SHA、main 合并状态、CI run、APK SHA-256、包名、签名、versionName/versionCode。

1. 梳理 PR 依赖/冲突及行为验证；未就绪的语音、摄像头、录屏草稿不为凑功能而混入。
2. 整合分支后执行 lint、两轮 JVM、当前提交模拟器测试、真实截图。测试失败不以旧提交证据替代。
3. 按用户授权合并通过的集成变更；从 main 合并提交的 CI 重新取包。`artifacts/delivery-version.json` 必须与该 SHA 一致，`source_ref=refs/heads/main`，`main_delivery_eligible=true`。
4. 打包前读目标手机实际版本。新包 `versionCode` **严格高于手机已安装版本**，也不得低于 main。versionName 供人阅读，Android 是否允许升级以 versionCode 和签名为准。
5. 保留数据安装 `adb -s <明确手机序列号> install -r <apk>`，核对返回值、安装版本、启动结果。失败不得自动 `-d` 降级、卸载或清空数据。

CI 版本门槛：`python3 scripts/verify_delivery_version.py --base-ref <本次集成基线SHA> --output artifacts/delivery-version.json`。它禁止版本码退后并区分 PR/main 来源；不把版本检查当作完整功能通过。

目标设备门槛：`python3 scripts/verify_delivery_version.py --installed-code <手机实际versionCode> --output <工作区外证据JSON>`。本地结果 `source_ref=LOCAL_UNVERIFIED`，不可冒充 main CI 来源。

0.2.3-p2 的本轮候选集成 #11/#16：聊天气泡和原型安全区、角色列表/Space 视觉、确认评论后的前台有界刷新；修正输入法/异步预览测试、生成图片 fixture 校验和、图片预览失败时禁止发送。语音 #13/#14、视觉 #12 与新增 P2.5 功能不包含在这一轮。是否最终通过以对应 CI 与手机证据为准，真实 Core E2E 未跑则记 NOT_RUN。

验收固定签名：CI 将 Secret 解码到 runner 临时目录，通过 ANDROID_ACCEPTANCE_KEYSTORE_PATH 显式指定 debug signingConfig；导出证书和实际 APK 均须匹配 SHA-256 4b9f3735586a0d99b6af9a5ad7afaf792a6bced2095dc6430a392c21c212800a。只存公开指纹与 APK 校验报告，不提交密钥。首次换签名需用户明确授权卸载；随后使用同一密钥覆盖安装。
