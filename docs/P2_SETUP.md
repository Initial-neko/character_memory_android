# P2 连接与验收

P2 使用现有 Core API，实现配置、角色与群聊列表、私聊/群聊、人物草稿/确认、ensemble 草稿生命周期、Space 评论/回复和图片草稿/明确发送。普通启动进入真实模式；没有自动降级到 Mock。客户端代码、模拟器测试、真实后端验收是不同证据层次。

## 配置真实连接

1. 手机和 PC 登录同一个 Tailscale 网络，PC Core 与 Media 按 Core 的 [MOBILE_ACCESS.md](https://github.com/Initial-neko/character_memory/blob/508c6f0/docs/current/MOBILE_ACCESS.md) 运行。
2. 首次启动在设置填写 Core：`https://<node>.<tailnet>.ts.net`。仓库没有预置私人服务器地址，未配置时保持设置页。
3. Media 通常为同一域名的 `https://<node>.<tailnet>.ts.net:8443`；可以独立填写 HTTPS origin。Core 是业务/SSE 服务，Media 是 ASR/TTS 服务，不能互换。
4. 保存后打开角色列表，再进入私聊。连接错误在真实页面展示，不生成 Mock 列表或假回复。证书校验保持开启，不能用手机的 `127.0.0.1` 代替 PC。

URL 与私聊 conversation ID 存在本地，按服务器和角色区分。切换服务器需要重新加载会话状态。设置中的高级 conversation ID 手动覆盖用于用户有意选择已有会话；这不代表自动发现、同步 Web 会话或统一跨端未读。

Debug APK 可通过显式 activity extra `initial_core_url` / `initial_media_url` 在用户设备本地初始化地址；正常使用也可直接填写设置。不要把私人地址放进公共源码、PR 截图或 CI fixture。`p1_mock=true` 单独选择原有离线演示，P1 UI 测试必须显式携带该 extra。

## 当前行为边界

- `202 Accepted` 表示服务器接收了用户事件；界面区分等待、角色事件、silence 和 reaction_error，不能把 202 当作模型已回复。
- 历史与 SSE 按持久事件 ID 对账；只读流可以重连，写请求不会因结果不明确而自动重发。切换目标/退后台会关闭旧流并防止旧响应更新新会话。
- 人物、群组和图片草稿需要用户确认。409 soft-limit 需要显式再确认。网络取消或错误后，应先检查历史/服务器状态，避免重复创建或发送。
- P2 展示服务器返回的头像、图片和表情；Media 不可用须独立展示错误。尚未实现手机 ASR/TTS、麦克风、摄像头、MediaProjection、设备配对或 bearer-token 授权。
- Core #213 的 canonical Direct ID、跨端 read state 与设备权限迁移在范围外。Tailnet HTTPS 是网络传输边界，不能称为 Android 设备认证。

## Fixture 来源

事实源固定为 [Core `508c6f0` 的 MOBILE_API_CONTRACT.md](https://github.com/Initial-neko/character_memory/blob/508c6f0/docs/current/MOBILE_API_CONTRACT.md)，并核对同 revision 的实际路由、模型和 Web 消费代码。源文件定义的 CURRENT 路由/事件为准；PROPOSED 设备配对、canonical Direct ID 等不得放进可调用 fixture。

开发期间本地 Core 已更新至 `3aa7a00f902e91b5616fbd108fc6f05a352b90ac`。已核对 `async_web.py` 的变更为服务端 channel lease 生命周期管理，SSE 路径和事件帧格式保持一致；此源码核对不代表该版本的真实服务已通过业务验收。

JVM `CoreApiTest` / `ConversationProjectionTest` 与 `LiveApiUiTest` 的 JSON/SSE fixture 位于测试源码，使用合成角色、事件和图片。持久消息、异步事件与 history fixture 对照同 revision 的 `message_projection.py`、`async_web.py`、`application/async_conversation.py`、`history_web.py`。它们覆盖 HTTP 错误、202、可选/null 字段、群组事件、重连和持久 ID 去重；不是线上抓包，也不能证明当前用户服务器兼容。没有另建一份 Android 私有 API 契约。

## 前端自动化与用户后端验收

CI 分开记录 P1/P2 JVM 和模拟器证据；两次 JVM 执行均使用 `--rerun-tasks`，分别保留 XML/JSON。P1 保持 ≥13 JVM、≥5 UI、准确的 12 个截图文件名；P2 保持 ≥25 JVM、≥6 UI 和 9 个独立截图。任何跳过、错误/缺失 XML、重复测试、缺截图都不记为 PASS。详见 [TESTING.md](TESTING.md)。

模拟器 `LiveApiUiTest` 使用测试注入的本机 HTTPS MockWebServer，检查真实页面调用协议与状态；不访问用户 PC。TLS localhost fixture 和测试证书只用于测试。模拟器截图需在当前提交人工/AI 审查，特别是输入法与窄屏。

真实 Core 的验收由用户在已安装 APK 上执行；以下每项记 PASS / FAIL / NOT RUN，并记录 Android 提交、Core revision、机型/系统、操作与可观察结果：

| 操作 | 核对结果 |
|---|---|
| 设置 Core/Media、刷新列表 | 实际角色/群组正常；地址错误清楚提示 |
| 私聊发送一次文本 | 202 后持久用户事件存在；SSE/历史一致、无重复；silence/error 不伪造回复 |
| 断线后恢复或切换目标 | 历史补齐；旧目标的迟到响应不污染当前会话 |
| 人物草稿→预览→确认 | 未确认不创建；409 明确确认；新角色由服务器返回 |
| ensemble prepare/恢复/研究/成员重试→确认或取消 | 草稿由服务器维护；不重复创建；群聊目标正确 |
| 群聊发送 | 202、成员事件、历史顺序/去重一致 |
| Space 分页、评论和回复 | 使用服务端 cursor；评论/回复确实持久化 |
| 图片 rewrite/generate→预览→发送 | 未确认草稿不进入历史；发送后附件可读 |
| Media 停止而 Core 仍在线 | 业务文字请求仍可用，媒体错误单独呈现 |

这些操作可能产生真实持久写入，应由用户选择测试角色/群组执行。仓库 CI 不自动向真实后端发写请求。安装成功、Mock PASS 或浏览器可达都不能代替上述后端验收。语音、相机、录屏及长期后台/功耗验收留到 P3–P5，当前均 NOT RUN。
