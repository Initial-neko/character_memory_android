# PSD 导出工具链

[Auto_Vtb_beta](https://github.com/lTwTlol/Auto_Vtb_beta)，当前实测提交 `dd1342d19cdf497aaf2ffff6cd8df25e6ad3b670`。新版本以实际代码为准。

先读 README、docs/zh/PSD_LAYER_SPEC.md、USER_GUIDE.md、core/PSD2LivePipeline.kt、RigBuilder.kt。检查 RGB/8-bit、Alpha、face/eyewhite/irides/eyelash/mouth、独立头发身体；语义由工具报告确认。嘴巴需有可变形的张口素材；缺素材如实记录。

JDK 21+。Windows 默认 Java 可能是 8，任务级指定 JAVA_HOME、GRADLE_USER_HOME，执行后恢复；复用缓存，默认不升级或安装。CLI 优先：

```text
./gradlew.bat run --args="--input <PSD> --output <目录> --lang zh"
```

PowerShell 中 Gradle 参数用完整字符串数组传递，特别是 `'-Pkotlin.compiler.execution.strategy=in-process'`；否则点号/等号曾被错误解析，产生 `No such property: sourceSets for class: java.lang.String`。

## 本次 CLI 编译阻塞

GUI 引用 CubismSdkFrame/CubismSdkPreviewSession，公开检出没有对应类，.gitignore 还排除了 `*CubismSdk*`。本次没有伪造类或绕过校验：外部 headless.init.gradle 仅编译核心、格式读写、国际化、RigCanvasSupport 与自己写的 Kotlin JavaExec 入口，调用 PSD2LivePipeline.run，保留几何验证、CMO3/MOC3 导出回读。

可发布的独立入口为 `scripts/export_model.ps1`、`scripts/headless.init.gradle` 与 `scripts/stagea/StageExport.kt`；目录由参数显式传入，完整命令见 [full-workflow.md](full-workflow.md)。默认只执行普通导出，嘴色需显式指定，不依赖输出目录名触发旧案例眼修复。使用 `run(psd: Path, outputDirectory: Path, config, progress)`；额外工作副本修正需检查上游 `run(source: SourceArt, sourceName, outputDirectory, config, progress)`。

## 输出验收

保存 `.cmo3`、`.moc3`、`.model3.json`、纹理及其引用的 physics/motion/expression/cdi。所有引用文件存在、非空、路径在模型目录内。参数 id/min/max 可从 previewModel.rig.puppet.parameters 另行导出，验证 MouthOpenY、EyeLOpen/ROpen、AngleX/Y 等。

本例头字节 `4d4f433305000000`，项目报告 Cubism50/MOC5；格式证据不等于所有旧 JS Runtime 兼容。记录 warnings 原文，区分重复回读警告与独立问题。成功退出和软件姿态变化都不能代表真实模型、美术质量、官方 Core 或 Android 已通过。

诊断提取器只处理已验证的简单平面 RGB8 PSD：明确 Alpha 通道、普通混合、满层透明度，无分组/复杂层蒙版。特殊图层优先使用工具完整解析；不要盲信 Pillow PSD 层解码，曾出现全部 Alpha=0 的错误。
