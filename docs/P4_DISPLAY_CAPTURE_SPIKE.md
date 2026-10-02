# P4 — Direct DISPLAY / MediaProjection 技术切片（待真机验收）

## 范围与证据

本分支基于 Android P2 PR #10（**不是** `main`）。只实现已存在的 Core `GET /v1/visual/periodic/config` + `POST /v1/visual/direct/observations` 的 **Direct** 屏幕变化观察；没有摄像头、语音、群聊自动观察，也不保证用户未解锁时采集继续。

用户点击私聊「共享手机屏幕」→ Android 系统选择整个屏幕或单个 App 并授权 → mediaProjection 类型前台服务 + 通知的「停止共享」动作 → ImageReader 获取 RGBA → 32×32 灰度差分 → 上限 1080 像素 JPEG → 仅变化明显时上传一个 DISPLAY 帧；Core 的 DISABLED/BUSY/INTERVAL/HOURLY_LIMIT/DUPLICATE_FRAME 与真实 Vision 预算仍由 Core 守门。客户端不会定时无条件调用 Vision；服务启动后首次可发送一帧，之后尊重 Core 的最小间隔。失败连续三次主动停用。

状态限定为当前 character_id + conversation_id。离开聊天去首页/Space/设置、换人物、改服务器地址会请求终止服务；切到其他 Android App 时保持已授权采集，直到用户/系统明确结束。帧只在内存转码并发往 Core，不保存在相册/磁盘；仅记录计数与状态，不打印图像内容。服务使用 START_NOT_STICKY；重启需重新授权，不复用 OS token。真机必须验证 Android 14/15/16 的单应用/全屏选择、后台、旋转、锁屏、系统状态栏停止、权限取消、网络中断和前台服务通知。

**现阶段仅为技术切片：** 核心 P0 设备认证/跨端 canonical direct ID 尚未完成；屏幕共享设备认证上线前不可视为完成。没有本机 Android SDK/ADB，本地编译和真实手机均 NOT RUN；待本分支 PR 的 Actions 提供编译/测试证据，不将静态阅读代替硬件验收。

## 预期手动验证

1. Core 用 `scripts/mobile-start.sh` 运行，手机 Tailscale 在线，先验证 P2 真实文字聊天。
2. 选定一位人物，点击「共享手机屏幕」并同意系统授权；查看前台通知中有「停止共享」。
3. 切换到其他 App，修改可见页面；回到 Character Memory 检查接受帧计数和当前人物自然回复／保持沉默。
4. 重复静止画面、快速频繁变化、Core 禁用周期观察；确认不频繁产生收费 Vision。
5. 测试系统停止投屏、屏幕锁定、拒绝授权、旋转/尺寸变化、网络断开、切换人物、从聊天进入 Space；确保资源释放和不再发送旧目标。
6. 分别记录 Android build SHA、Core SHA、机型/系统、logcat、实际接受事件以及关闭后的采集状态，填写 PASS/FAIL/NOT RUN。

来源：Core `docs/current/MOBILE_API_CONTRACT.md` §6 与 `docs/current/VISUAL.md` §Periodic screen observation。Android 开发官方 MediaProjection 指引： https://developer.android.com/media/grow/media-projection

## 手动询问当前画面

共享期间可以点击「询问当前画面」；这条请求使用 Core 已存在的 `POST /v1/visual/direct/messages`，仅附带一张 DISPLAY 帧并生成普通的可见用户消息。自动观察是否启用不影响主动询问，二者使用不同的 Core 接口。Core 返回 202 后刷新历史；模型仍可以选择不回复。此通路仍需真机/真实 Core 联调，暂不宣称通过。
