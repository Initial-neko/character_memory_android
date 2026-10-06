# 图片 → 可用 Live2D：可重复执行流程

这条流程将远端切分、独立模型制作、缺陷修正和产品绑定分开验收。每次保留原图、原始 PSD、工具版本、配置、输入输出 SHA256、命令退出码、资源校验 JSON 和实际脸部截图。不得把 Mock 通过或 API 可连接写成模型生成成功。

## 1. 准备角色原图

图像优化先走现有 Core 的 AI 优化接口，生成可使用 Core 图像服务或会话 imagegen；完整参数与步骤见 [full-workflow.md](full-workflow.md)。使用透明背景角色图并记录文件哈希。眼睛、嘴巴应清晰，肢体避免大面积遮挡。原始输入保留，不覆盖。只有用户指定输入图片及在线目的地后，才上传到 See-Through。

## 2. 在线 See-Through：图片 → 分层 PSD

默认服务：https://ljsabc-see-through.ms.show
MCP：https://ljsabc-see-through.ms.show/gradio_api/mcp/ （不加 /sse）。工具 inference。

2026-10-06 已实测生成成功：公开页面匿名 `/queue/join` 要求登录，专用 API 使用获授权的现有 `msimg_api_key` Bearer 认证成功，普通异步 API 和官方 Python 客户端均返回实际 PSD。MCP metadata成功不代表推理获授权，心跳不代表生成完成。认证、客户端版本、MCP协议和结果解析详见 [see-through-api.md](see-through-api.md)。访问状态随服务变化，保留具体错误，不绕过权限。

项目批处理优先普通 Gradio API，助手按需调用可使用 MCP。使用独立工具环境中已经安装的 gradio_client；不把它加入 Character Memory 服务启动链。调用形式：

```python
from gradio_client import Client, handle_file
from live2d_env import load_token
token = load_token(env_file)
client = Client(
    "https://studio-ljsabc-see-through.api-inference.modelscope.net/",
    headers={"Authorization": f"Bearer {token}"},
    verbose=False, analytics_enabled=False,
)  # token 只从显式 .env 读入内存，不打印、不硬编码。
psd, layers = client.predict(
    image=handle_file("character.png"), resolution=1024,
    seed=42, tblr_split=False, api_name="/inference",
)
```

resolution 768–1536、步长64；seed 0–9999。tblr_split 是否拆左右手臂与腿按输入需求设置并记录。MCP image 使用 HTTP/HTTPS 图片地址；本地图片需先按服务支持方式上传。保存实际返回 PSD、图层预览和调用配置，检查文件非空；不要只保存临时远端链接。

## 3. 本地 PSD 检查和素材修正

保留 raw.psd；以副本 working.psd 工作。检查 RGB / 8-bit、透明背景、合成图，以及 face、eyewhite、irides、eyelash、mouth、头发和身体的独立图层与 Auto_Vtb 的真实识别结果。先用工具已有图层映射解决命名；不要盲目重命名所有图层。

输出图层清单和问题清单。虹膜缺失、眼白遮瞳孔、嘴巴缺少最大张口素材分别记录。缺少素材可补绘，但不能靠随机动作或错误蒙版伪装成功。平面 PSD 检查器不是所有 PSD 都能处理的通用编辑器。

## 4. Auto_Vtb_beta：PSD → 模型

独立工具仓库：https://github.com/lTwTlol/Auto_Vtb_beta
以实际版本的 README、PSD_LAYER_SPEC、USER_GUIDE、Pipeline/RigBuilder 为准，准备已有 JDK 21+ 和依赖缓存。

```text
./gradlew.bat run --args="--input ./working.psd --output ./output --lang zh"
```

本例另有离线 headless helper；如 CLI 与实际版本不符，先核对入口，不在 Python 服务里每次启动 Gradle。保留可编辑 CMO3；运行包包含 MOC3、model3.json 及其全部纹理、动作、表情和物理引用。

用 skill/scripts/validate_model.py 验证路径、文件存在/非空、MOC 文件头并生成 JSON 报告与 ZIP。仅验证文件头不能证明二进制可渲染或参数绑定正确。模型目录不得包含机器绝对路径、任意远端引用或第三方专有 Core。

## 5. 扫描 → 定位 → 局部修复 → 重导出

用兼容 MOC5 的真实 Runtime 加载导出的 MOC3，启动 Idle，检查正面和小幅转头；分别截图睁眼、半闭眼、闭眼、闭嘴、半张嘴、最大张嘴。脸部放大检查，不能只看全身能动。

白眼：检查虹膜 drawable、透明度、顺序、蒙版和 Runtime 标志；闭眼断线：检查眼睑素材与变形绑定；嘴巴不可见：检查素材、轮廓颜色、尺寸、遮挡及 ParamMouthOpenY。一次改变一个因素，保留前后截图和 ROI 指标，原图/PSD 哈希不变。已有眼部坐标、颜色和阈值只适用于本例，不能套用新角色。

模型绑定问题在模型制作副本中修正后重导出；Runtime 蒙版问题在渲染适配中修正。不得把两个层次混在一起。Purism bridge 仅用于已验证版本组合，换 Runtime 必须重新验收。

## 6. Character Memory 绑定和验收

模型管理 PR #234 已合入 main：角色列表“···” → “Live2D 模型” → 上传 ZIP → 导入并绑定；同一入口可更换或解除绑定。部署必须包含对应合并版本；本机测试页面可用不等于正式环境已部署。

ZIP 最多64 MiB；清单仅一个入口，保留相对路径。服务端检查越权、重名、文件目录冲突、符号链接、容量、引用、JSON、MOC头及纹理实际像素解码，全部通过后原子切换绑定；失败保留旧模型。旧版本资源保留，避免已加载通话失效；不自动清理历史版本。

通话页面开启 Live2D：真实模型可见，自动动作和表情同时启用；再次点击回到头像。更换模型不重拨，解绑回退头像，挂断/收起释放或暂停相应视觉资源。TTS 音频驱动单独验收，当前流程不以随机张嘴冒充 TTS 同步。

最终检查：真实 MOC3 显示、眨眼/嘴巴/小幅转头、多个开关循环、替换/解绑/失败回退、无旧角色串用；分别记录桌面 Chrome 和 Android WebView 的实测结果。只有内置浏览器实测时明确标注，不能推导 Android 或正式 ASR/LLM/TTS 已通过。

## 交付边界

用户模型和素材保存在本地资源目录；源仓库只提交实现、测试和维护文档，不提交 PSD、巨大模型、临时资源或专有 SDK 二进制。模型制作不是无人值守的美术保证：缺少素材和严重变形应报告具体部位，修正后重新验收。
