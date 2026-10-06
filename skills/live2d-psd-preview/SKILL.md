---
name: live2d-psd-preview
description: 用于角色图像生成与优化、See-Through 分层 PSD、Auto_Vtb_beta 制作 Live2D、MOC3 动态预览，以及白眼、瞳孔消失、闭眼断线和嘴巴不可见排障。适合 Windows 全流程工具链；通话与 TTS 产品集成需另行审查项目代码。
---

# Live2D 建模与预览

交付真实模型、可打开的动态预览和机器可读证据；区分素材、绑定、导出格式、网页 Runtime 问题。只要求总结或排障时不扩展为产品开发。

## 图片到模型完整流程

从文字或原图开始，先读 [references/full-workflow.md](references/full-workflow.md)：图像优化必须复用 Character Memory 的 AI 优化接口；生成素材后由用户确定输入，再执行在线拆层、离线处理、修正和预览。程序命令均显式接收工作路径；不依赖作者机器目录。Android 原生 Renderer 当前仍是静态占位，不能把 Web 预览当作原生 Live2D 已接入。

认证只读显式指定的 `.env`，使用 `scripts/live2d_env.py`；不读取系统环境、旧 YAML、浏览器或其他本机凭据。包中 `.env.example` 只有空值。不得打印 Key、把 Key 写进命令行/日志/报告或上传真实 `.env`。缺少密钥先停止，不联网试探。

从原图开始或需要复用全流程时，先读 [references/image-to-live2d.md](references/image-to-live2d.md)。涵盖在线 See-Through API/MCP、PSD 检查、局部修正、真实模型验收与角色绑定。调用或认证失败时读 [references/see-through-api.md](references/see-through-api.md)，区分公共登录限制、专用 API Token 和排队心跳。远端服务状态必须当次验证；未指定图片不执行推理上传。

## 开始

检查用户 PSD、已有输出、本地服务、JDK 与缓存，复用真实环境。原 PSD 和原模型保留，局部修正使用工作副本和独立输出。续接用户项目时读取该项目自己的证据，不将旧案例路径或历史状态当作当前环境。

## 导出

读 [references/export.md](references/export.md)。检查 RGB8、透明背景、重要独立图层、实际语义识别、最大张嘴素材。`scripts/inspect_flat_psd.py` 是平面 RGB8 PSD 诊断器，依赖已有 Pillow，不是通用 PSD 编辑器。

导出后验证引用并可选打包：

```powershell
python <skill>/scripts/validate_model.py <模型.model3.json> --report <验证.json> --pack <预览.zip>
```

脚本仅验证路径、文件、MOC 头与打包，不代表参数、渲染或美术质量通过。CMO3 单独保留；运行资源按 model3 相对路径完整保存。

## 动态预览和脸部排障

读 [references/preview-debugging.md](references/preview-debugging.md)。需要自动动作时显式启动 Idle 并验证；软件内存模型截图不等于实际 MOC3 渲染。

对比原 PSD 合成图、睁眼/半闭眼/闭眼、闭嘴/半张嘴/最大张嘴。瞳孔消失先检查 drawable、透明度、位置、蒙版和状态标记，不先重画素材或关闭所有物理。根据定位做单变量实验。

`scripts/purism-pixi-bridge.js` 只适用于已验证的 Purism v5 JS + pixi-live2d-display 0.4 组合。它适配渲染顺序字段与蒙版重画，其他 SDK 不默认套用。`node <skill>/scripts/check-mask-bridge.cjs` 是 120 帧 Mock 回归，不能代替真实网页验收。

## 产品集成

仅在用户要求接通话时读 [references/integration.md](references/integration.md)。复用 Voice Runtime，Live2D 仅负责视觉。口型分析正式 TTS 播放音频；不能分析用户麦克风，也不能把随机张嘴说成 TTS 同步。

不把工具 GPL 源码、未经许可的专有 Core、用户 PSD、巨大模型和本机绝对路径提交到产品仓库。当前 skill 不含模型或第三方二进制。

## 验收交付

保存命令退出码、输入哈希、引用检查、参数信息、真实运行截图。Mock 与浏览器证据分开。放大检查眼睛嘴巴，不能因全身远景“能动”就接受脸部表现。

中文简洁说明做了什么、URL 或入口、实际验证范围和未验证项。localhost 仅本机访问且服务需保持运行。保留用户可继续使用的浏览器页。
