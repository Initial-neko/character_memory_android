# 预览与眼睛嘴巴故障

## 实测预览组合

[ImDuck42/Live2D-Viewer](https://github.com/ImDuck42/Live2D-Viewer)，提交 `c573887ed4f118d3e235c1ca8df7e681fc6fb910`，PIXI 6.5.10 / pixi-live2d-display 0.4.0；[SakuraMotion/PurismCore](https://github.com/SakuraMotion/PurismCore) v1.1.0 Web 包中 Core-v5/purismcore.js（MIT，内嵌 WASM）。第三方 Core 实际加载当前 MOC5，不等同官方 Core。上游 viewer 附带专有 Core 不作为默认可提交资产。

本地部署 JS 和 ZIP 依赖，避免 CDN。需要自动展示时，加载选项设 `idleMotionGroup:'Idle'`，成功后启动 `model.motion('Idle',0,1)`；`autoInteract:false` 可排除鼠标干扰。仅可手调参数的 viewer 不等于自动动作展示。

```text
python -m http.server <端口> --bind 127.0.0.1 --directory <viewer目录>
http://127.0.0.1:<端口>/?model=<模型.zip>
```

Windows 后台服务用隐藏窗口；记录自建进程 PID、端口、目录，停止前查证归属。model ZIP 完整保留相对目录。页面可能去掉 URL query；重新打开仍需 model 参数。

## 白眼：渲染兼容问题

实测瞳孔 drawable 存在、opacity=1、坐标在眼白内，但眼白 flags=1，瞳孔仍完全消失。

Pixi 的 setupClippingContext 每帧清空蒙版 framebuffer，只画带 VertexPositionsDidChange（bit 32）的蒙版源。Purism 对静止 geometry 不保留该标记；Pixi 又在 core.update 后立即 resetDynamicFlags，导致空蒙版裁掉瞳孔。

准确匹配上述组合时，桥接脚本：

- 将实际 model.renderOrders 数组兼容到 drawables.renderOrders。
- 在 **resetDynamicFlags 之后** 只为有真实 mask 引用的源补 bit 32。只在 update 后补会被 reset 清掉。
- 不关裁切，不标记全部 drawables。script 在 Core 后、模型创建前加载，修改后加版本查询参数避免缓存旧代码。

跑 skill/scripts/check-mask-bridge.cjs，然后用实际网页观察两侧瞳孔、连续眨眼；保存 flags 1→33 与截图。Mock 不代替 GPU。不同 SDK 先检查当前实现，不默认注入这个适配。

## 闭眼断线：素材与绑定

EYELASH 文档要求仅上睫毛，但本次当前代码按完整眼眶压缩至 15%，与文档不一致。约 3px 的原睫毛压成不足 1px 后断线。把上/下眼眶拆开仍失败；必须通过对照确认，不能凭文档宣称修好。

本例工作副本：按眼白列中点拆分已有 Alpha，下眼眶分侧作为 facedetail 随对应 EyeOpen 淡出；仅对修正后的上睫毛保持 70% 厚度。原像素分区 Alpha 和等于原图，原 PSD 哈希不变。这个系数、分割规则和 ROI 仅对本角色验证，新角色应重新检查。

对比原 PSD 合成图与参数 0/0.5/1、头部姿态，使用真实 `.moc3`。Purism 原生 viewer 可执行 `viewer.exe --shot closed.png --setparam ParamEyeLOpen 0 --setparam ParamEyeROpen 0 model.model3.json`。v1.1.0 --shot 用相对路径曾避免绝对路径拼接错误；其原生示例物理支持不完整，不证明网页物理通过。

SourceArt 工作副本可导出可编辑 CMO3，但不是修改后的 PSD，交付时明确。

## 嘴巴：已验证的局部修正

本例张嘴素材和最大张嘴截图存在，闭嘴描线很淡。待机 Motion 没有 MouthOpen 动作时不自动张嘴正常，但闭嘴必须可辨。检查 MouthOpenY=0/0.5/1、MouthForm、mouth/lip opacity、蒙版和轮廓颜色。

MouthLipLayers 自动取外轮廓的深色分位值，女仆案例实际生成 #fbd9c7 接近肤色，单变量实验只把 mouthColor 改成原图高 Alpha 像素亮度第 3 百分位 #705148，thickness 保持 1.5，闭嘴可见且 0/0.5/1 实际 MOC3 姿态通过。该案例口部之外软件图像完全不变。

2026-10-06 红莉栖案例再次出现浅色轮廓：#fae2d3。由该角色 mouth 图层 alpha>=192 的55个像素，按亮度第3百分位选择 #917971，仅设置 PipelineConfig.mouthColor，保持厚度1.5，单独输出 model-mouth-repaired。真实 MOC3 的闭嘴与张嘴轮廓更清楚，网页实际加载成功；没有对本例验证半张嘴和“口部之外逐像素不变”，不能借用女仆案例证据。

这两次表明可将高 Alpha 原嘴部深色作为候选，但固定百分位、阈值和颜色不是所有画风的默认答案。先检查该角色的实际像素，保留默认导出并做单变量对比。眼睛正常时不要顺带套用旧 eyeRepairConfig；本例让 mouthRepairConfig 接受显式基础 PipelineConfig，仅改嘴色。若没有可靠深色像素，记录素材缺失，不无条件取极端颜色。

颜色修正提高可见性，不补出自然的最大张口、牙齿或口腔美术。闭眼线条机械、嘴巴只具基本形变仍应记为质量限制。原 PSD 保持哈希不变，SourceArt 或导出配置修正不声称 PSD 已被精修。

Pixi 在渲染后 loadParameters 恢复保存的 Motion 参数，此时读取 0 可能误判表情不生效。诊断应在 native Core update 边界或对应 beforeModelUpdate 钩子读实际值。新版网页实测普通=0、惊讶=0.75，不能把随机张嘴当作 TTS。

不要通过随机循环张嘴掩盖质量，也不能称其为 TTS 同步。自动绑定不足时可补专用闭嘴/闭眼素材或精修 CMO3；能导出、能动不代表可上线效果。
