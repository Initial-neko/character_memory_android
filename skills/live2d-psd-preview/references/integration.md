# 通话集成边界与当前实现

只有用户要求产品集成时进入。先读真实 AGENTS.md、VOICE_AND_TTS/TECH_DEBT 文档、Voice Runtime、头像资源/静态服务与相关测试，检查 open PR 重叠；接口以检出代码为准。

每个角色可选关联独立模型，原头像不受影响；完整保存 model3 引用资源，拒绝越权路径和任意远程模型下载。替换后旧异步加载不能恢复旧角色。

独立 Renderer 管理 Canvas/WebGL、resize、motion/expression、参数、pause/resume/destroy。复用原通话系统，LLM/ASR/TTS/媒体调度不进 renderer。切换 Live2D 不重拨；失败回退头像、通话继续。收起暂停不可见绘制但语音继续，挂断释放资源；群聊以实际 TTS characterId 选模型，P0 可回退，不串角色。

正式 TTS 播放音频 → Web Audio Analyser RMS → 门限/平滑/衰减 → ParamMouthOpenY。每个 HTMLAudioElement 的 MediaElementSource 只能创建一次，节点只连一条到输出的链。结束、失败、停止、换角色、挂断时归零；AudioContext 失败不能破坏语音。麦克风音量和随机嘴动画都不是正式 TTS 口型。

Mock 测试覆盖资源路径、回退、不重拨、反复开关/收起不泄漏、结束/失败嘴归零、异步加载过期、连续 TTS/角色切换、媒体回归。真实 MOC3 的 Chrome 和 Android 验收另行记录。

模型制作工具独立，不把 Gradle 加进 Python 服务启动。官方 Core 与开源 Framework 许可不同；本地 Purism 预览通过不能推导产品 Runtime 和许可兼容性已通过。

当前产品 main 已接入 Purism、通话开关、生命周期和自动表现。模型管理分支新增 live2d_import.py、live2d_models.js 与 POST/DELETE /v1/characters/{id}/live2d；部署前以最新合并状态为准。完整过程见 image-to-live2d.md。真实网页通过不代表正式语音链路或 Android 已通过。
