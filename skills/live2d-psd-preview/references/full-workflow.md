# 文字 / 原图 → Live2D 全流程

本 skill 是独立的助手工具包，不进入 Android App、语音 Session 或服务启动链。用户素材、PSD、CMO3、MOC3、密钥和截图存到 Git 之外的工作目录。在线步骤会发送用户素材，必须沿用用户已授权的输入和目的地；发布 skill 不代表获准重新推理。

## 1. 优化与生成角色图

已有角色时读取当前 Core 的 `visual_web.py` 或 OpenAPI。当前真实优化接口是 `POST /v1/characters/{character_id}/images/rewrite`；请求字段为 `instruction`、`provider`、`purpose`（`AVATAR` / `SELFIE` / `SCENE`）和 `use_avatar_reference`，返回 `prompt`、`aspect_ratio`、`used_avatar_reference`。例如：

```json
{"instruction":"保持这个角色的脸和服装，正面全身，透明背景，双手与身体留出间隙，头顶和脚底完整，眼睛嘴巴清晰，适合 Live2D 拆层", "purpose":"AVATAR", "use_avatar_reference":true}
```

先调用优化接口并保留返回的 Prompt，不自行代替服务优化。可使用 Core 的 `POST /v1/characters/{character_id}/images/generate`（内部已复用同一优化链），或把已优化 Prompt 交给会话中的 imagegen 能力生成 / 编辑图片；不要新造生成 API。会话 imagegen 不需要把它的认证凭据放进 skill。Core 图像服务的密钥由服务自己的 `.env` 管理，客户端不复制服务密钥。没有可用 Core 或 character_id 时先报告这一依赖，不能声称优化接口已执行。

生成接口默认 `persist_result=false`，仅返回草稿；不要自动将候选图发送聊天或覆盖头像。把返回的 `image.data_url` 或已验证媒体 URL 保存为原图，确认透明背景、脸型、服装、遮挡与完整轮廓。记录原始要求、实际优化 Prompt、生成参数和图片 SHA256；本地保存，公共仓库不包含用户原始提示与素材。用户选定图片后才进入拆层。

## 2. See-Through 拆层

将 `.env.example` 复制到用户工作目录的 `.env`，在本地填 `MSIMG_API_KEY`，或填 `MODELSCOPE_API_TOKEN`。脚本只读传入文件，前者非空时优先；不使用 YAML 兜底，不自动导入到进程环境，不将 Key 当作 CLI 参数。

```powershell
python <skill>/scripts/generate_psd.py --env-file <工作目录>/.env --image <原图.png> --output <新的PSD输出目录>
```

依赖已有 `requests`，不自动安装。默认使用历史实测的 ModelScope 专用 API；换实例时显式传 `--api-base`，必须是用户授权的 HTTPS ModelScope API origin。使用 `--resolution`、`--seed`、`--tblr-split`、`--wait-seconds` 调整参数。禁止跨主机下载时转发认证、跟随重定向或自动重复提交。输出 `generated.psd` 和不含密钥的 `report.json`；超时保留 event_id，远端状态未知。详见 [see-through-api.md](see-through-api.md)。

## 3. PSD 诊断

```powershell
python <skill>/scripts/inspect_flat_psd.py <generated.psd> <新的检查目录>
```

依赖已有 Pillow，仅支持明确范围内的平面 RGB8 PSD；不支持分组、特殊混合或复杂蒙版时使用上游解析器，不伪造检查通过。检查 face、eyewhite、irides、eyelash、mouth、头发和身体独立图层。原始 PSD 不覆盖；缺少张嘴素材、瞳孔或透明背景要先处理。AI 补绘图片使用会话的 imagegen，再检查和重拆层；程序局部修正则保留副本与对比证据。

## 4. 离线程序导出和显式局部修正

使用用户已检出的 Auto_Vtb_beta、JDK 21+ 与已有 Gradle 缓存；skill 不包含第三方源码或二进制。普通导出优先上游 CLI。上游 GUI 缺类阻塞时，使用包含的独立 headless 入口：

```powershell
& <skill>/scripts/export_model.ps1 -ToolDir <Auto_Vtb_beta目录> -PsdInput <working.psd> -OutputDir <新的模型目录> -JavaHome <JDK21目录> -GradleHome <已有Gradle缓存目录>
```

脚本默认 `--offline`，输出日志、退出码和输入 SHA256；不会安装或升级依赖。Kotlin helper 只调用上游 Pipeline，生成 CMO3、MOC3、引用资源、参数清单和七种软件姿态截图。软件截图不等于真实 Runtime 已通过。当前兼容基线详见 [export.md](export.md)；缓存缺失或接口不兼容就停止报告。

嘴部轮廓太浅时，在该角色原素材中选择并验证颜色，再在独立目录重新执行并显式添加 `-MouthColor '#RRGGBB'`。不自动套用旧角色颜色、百分位、眼修复或输出目录名称触发的隐式修正。眼睛 / 绑定修正按 [preview-debugging.md](preview-debugging.md) 单变量排查，必要时人工修整 CMO3；不把有限通用 helper 宣称为自动美术精修。

## 5. 校验、打包与真实预览

```powershell
python <skill>/scripts/validate_model.py <模型.model3.json> --report <验证.json> --pack <模型.zip>
node <skill>/scripts/check-mask-bridge.cjs
```

独立保留 CMO3；运行 ZIP 只放模型引用资源，不含 SDK/Core、PSD、环境文件。资源检查只证明引用 / 文件头 / 路径，Mock bridge 回归只证明脚本逻辑。用已安装的兼容 MOC5 Runtime 打开实际 MOC3，验证自动 Idle、眼睛 0/0.5/1、嘴巴 0/0.5/1 和转头；保存真实脸部截图。Web 预览与原生 Runtime、Android 证据分开。

## 6. 绑定与交付

用户要求绑定时，使用现有角色模型管理入口上传 ZIP，更换 / 解除绑定；保留旧版本，不重拨通话。参考 [integration.md](integration.md)。口型仅分析实际 TTS 播放音频；不把随机张嘴或用户麦克风当作 TTS 同步。交付模型 ZIP、可编辑 CMO3、资源报告、输入输出 SHA256、导出退出码和真实 Runtime 截图，并列明未验证的生成、GPU、硬件或 TTS 环节。

## 离线回归

```powershell
python -m unittest discover -s <skill>/scripts -p 'test_*.py'
node <skill>/scripts/check-mask-bridge.cjs
```

这些测试不使用真实 Key，不联网生成，不代表远端服务当前可用。

发布可复用 skill ZIP：

```powershell
python <skill>/scripts/package_skill.py --output <skill目录之外>/live2d-psd-preview.skill.zip --report <打包报告.json>
```

打包器只允许文档、脚本和空值模板，拒绝真实 `.env`、模型、PSD、SDK、未知文件，并检查常见凭据字面量。扫描是辅助检查，发布前还应对照获授权的真实密钥在内存中检查候选文件；不得把密钥输出到扫描日志。GitHub workflow 运行离线测试并提供相同 ZIP artifact。
