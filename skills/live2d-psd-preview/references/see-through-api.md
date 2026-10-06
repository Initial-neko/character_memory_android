# See-Through 调用与认证排障

本参考基于 2026-10-06 的实际生成结果；远端状态仍需当次验证。不要把公开 API 文档、MCP 工具列表或 HTTP200 当作生成权限与生成成功的证据。

## 两条地址，分别判断错误

- 公开页面：`https://ljsabc-see-through.ms.show/`。
- 公开 MCP：`https://ljsabc-see-through.ms.show/gradio_api/mcp/`，不加 `/sse`；工具 `inference`。
- 本实例返回的专用 API：`https://studio-ljsabc-see-through.api-inference.modelscope.net/`。其他实例不能机械套用此子域名。

此次匿名公开页面配置、上传、MCP initialize 与 tools/list 均能成功，但 `/gradio_api/queue/join` 返回 HTTP403，正文是 `请登录后再使用xGPU创空间。`，平台错误码 `10011402026`。这是平台运行推理的登录限制，不是 inference 函数缺少 Key 参数。

SDK 标识的请求公开 `/config` 还曾返回 HTTP403、错误码 `10010101007`，提示改用上述专用地址。专用地址匿名请求返回401，正文要求有效 ModelScope token。这两种403和专用地址401不能混成同一个错误。

此前匿名 MCP 推理与普通异步任务只有心跳、未返回最终结果；其远端结局仍未知，不能宣称认证成功或据此断言超时根因。`event_id` 表示收到提交，`heartbeat/null` 表示流仍活动，不代表 GPU 已开始执行。

## 已验证的认证调用

历史实测使用获授权的 ModelScope Key，作为专用 API 的 `Authorization: Bearer ...`。可发布 helper 现在统一使用 `scripts/live2d_env.py` 从用户显式指定的 `.env` 读取；不打印值、不写入报告、URL 或命令行，不向其他主机转发认证。

skill 的读取规则：传入 `.env` 内非空 `MSIMG_API_KEY` 优先，其次 `MODELSCOPE_API_TOKEN`。没有 YAML、系统环境、浏览器或其他凭据兜底，缺失即失败。该规则只针对本工具包，不声称已修改 Core 全局配置解析。未获授权时不能因配置文件可读就擅自使用密钥。

Gradio 服务实际为6.2.0，对应本次独立工具环境 gradio_client2.0.2；MCP serverInfo.version1.26.0 不是 Gradio 版本。检查现有依赖，不默认升级或将客户端加入产品启动链。

以下示例假定 image_path 为已确认上传的图片，scripts 目录在 Python 导入路径上；优先使用带主机检查与有限等待的 `generate_psd.py`：

```python
from gradio_client import Client, handle_file
from live2d_env import load_token

token = load_token(env_file)

client = Client(
    "https://studio-ljsabc-see-through.api-inference.modelscope.net/",
    headers={"Authorization": f"Bearer {token}"},
    download_files=output_dir,
    analytics_enabled=False,
    verbose=False,
)
try:
    job = client.submit(
        image=handle_file(image_path), resolution=1024,
        seed=42, tblr_split=False, api_name="/inference",
    )
    # 有限等待，记录 job.status() 的 code/rank/queue_size/eta/success。
    # job.done() 后 job.result() 返回 (psd_filepath, layers_gallery)。
finally:
    client.close()
```

实际 helper 还设置 `HF_HUB_DISABLE_IMPLICIT_TOKEN=1`（在导入客户端前），避免顺带使用本机不相关 Hugging Face 凭据。SDK User-Agent 的大小写覆写曾影响元数据探测：自定义时用单一小写 `user-agent` 替换默认值，避免重复字段；这不是认证方案，遇到明确登录拒绝必须走合法认证。

真实结果：专用普通异步 API `/gradio_api/upload` → `/gradio_api/call/inference` → SSE结果及PSD下载完成约124秒；官方 Python 客户端完成约145秒。相同原图、1024/42/false，两次生成文件5699614字节且SHA256一致。时间和一致性是本次观测，不是服务 SLA 或确定性保证。

## MCP 与结果保存

MCP 使用 initialize → notifications/initialized；后续按协商协议传 MCP-Protocol-Version。需要排队进度时在 tools/call params._meta.progressToken 设置标识，并保存 notifications/progress；SSE读取用较小 chunk，避免把客户端缓冲误认成服务无响应。

MCP image 接受可访问的 HTTP/HTTPS 图片地址；本地文件先走服务支持的上传。Gradio6.2.0的非图片文件输出可能是 TextContent 中的**纯 PSD URL**，并非 JSON 对象；解析同时支持结构化 FileData 与纯链接。只允许已验证输出主机，下载需要改主机时移除认证；不能把 Key 发给任意返回 URL。

保存最终结果、PSD的8BPS文件头/大小/哈希、调用参数、退出码和脱敏错误正文。失败报告应定位具体 URL、HTTP状态、平台码；只记录连接成功不够。等待超过明确预算时记录远端状态未知，保留 event_id 优先续查，不盲目重复提交。本次为核验两种客户端进行了两次授权推理，之后无需再重复。

## 可移植执行

命令、输入输出位置和 `.env` 模板见 [full-workflow.md](full-workflow.md)。本包不含用户案例目录、旧 helper 的本机路径、历史报告或真实密钥。远端调用的历史成功不等于本次已经在线生成；运行后以新的报告和真实 PSD 为准。
