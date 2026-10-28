# 第三方来源、署名和许可

本仓库的主代码 MIT 许可仅覆盖维护者有权授权的代码/文档，不能替代本文件列出的素材与依赖许可。这里区分直接纳入的图片、外部库、构建工具和仅调用的在线服务；专注页背景照片另见第 2 节。

## 1. 直接纳入：DeepSeek 娘角色图片

| 项目 | 记录 |
|---|---|
| 作者 | **YunYueSama** |
| GitHub | **https://github.com/YunYueSama/codex-deepseek-pet** |
| 固定提交 | `7661c8b304c5400701f91da01b1a643a207331de` |
| 原图 | `assets/whale/portrait.png`；`assets/whale/idle-front.png`（睁眼/闭眼两帧图集） |
| 本项目文件 | `chan_portrait.png`、`chan_head.png`、`chan_idle_open.png`、`chan_idle_blink.png`、`chan_head_open.png`、`chan_head_blink.png`，位于 `app/src/main/res/drawable-nodpi/` |
| 修改 | 从 `idle-front.png` 左右两帧切出睁眼/闭眼帧；按共同包围盒裁切并缩放为 256×350 全身与 192×192 头像；适配呼吸、眨眼、出现、收起/展开与状态动效；保留透明背景；未重绘或改色 |
| 许可 | 自定义“大肥鱼项目署名许可 1.0”，**不是 MIT** |

完整许可及上游第三方参考边界见 [third_party/deepseek-chan](third_party/deepseek-chan/README.md)。这套图为社区二创，不是 DeepSeek 官方形象或授权声明。

未移植原仓库的 Windows 桌宠、Electron/Node.js、桌宠物理/喂食代码；未纳入原参考图、Noto 字体或完整动画。开源本项目不代表获得任何第三方设计的额外商用权利。

## 1b. 账本模块来源说明（v1.8.0）

「账本」模块由同作者的个人项目 **PersonalLedger（小账本）v1.3**（`com.jsy.ledger`）移植并入：
业务逻辑（金额/计算/备份校验/Room 结构）按原样迁移并改用 `com.jsy.dayplan.ledger.*` 包名，UI 按日序马卡龙风格重绘。
该模块为维护者本人原创代码，随主代码一起按 MIT 许可提供；未引入任何新的第三方依赖、图片、字体或音频。

## 2. 专注页背景照片（用户提供，来源不明）

| 项目 | 记录 |
|---|---|
| 文件 | `app/src/main/res/drawable-nodpi/focus_canopy.jpg`、`focus_alpine.jpg`、`focus_maple.jpg`、`focus_dusk.jpg`、`focus_coast.jpg` |
| 来源 | 维护者从小红书（Xiaohongshu）随机收集，未记录原作者、原帖链接或授权方式 |
| 性质 | 不是本项目原创，不纳入 MIT 许可；著作权归原摄影师/作者 |
| 使用范围 | 仅用于个人、非商业的本地 App 展示与仓库代码说明；不得用于商业发布、再销售、广告或声称原创 |
| 处理 | 如权利人提出异议，请通过 GitHub Issues 联系，将立即删除或替换；公开或商业分发前建议替换为有授权或自摄图片 |

这些照片与代码许可相互独立：即使仓库主代码采用 MIT，也不代表获得这些照片的复制、改编或商用授权。

## 3. App 中使用的库

| 项目 / 作者或维护组织 | 本项目版本 | 来源 | 许可 | 作用 |
|---|---|---|---|---|
| PDFBox-Android / Tom Roush 及贡献者 | 2.0.27.0 | [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Apache-2.0，保留其附加第三方声明 | 手机端 PDF 文本提取 |
| Apache PDFBox / Apache Software Foundation | 由 PDFBox-Android 移植 | [apache/pdfbox](https://github.com/apache/pdfbox) | Apache-2.0 及随附声明 | PDFBox-Android 上游 |
| Bouncy Castle / The Legion of the Bouncy Castle Inc. | 1.72，PDFBox-Android 传递依赖 | [bcgit/bc-java](https://github.com/bcgit/bc-java) | MIT-style Bouncy Castle 许可 | 加密 PDF 相关底层依赖 |
| AndroidX Compose / Material3 / Material Icons | BOM 2024.09.03 | [androidx/androidx](https://github.com/androidx/androidx) | Apache-2.0 | 原生 UI 和图标 |
| AndroidX Room | 2.6.1 | [AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room) | Apache-2.0 | SQLite 数据持久化和事务 |
| AndroidX ExifInterface | 1.3.7 | [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface) | Apache-2.0 | 图片方向 |
| AndroidX Activity / Navigation / Lifecycle / Core | 见依赖清单 | [androidx/androidx](https://github.com/androidx/androidx) | Apache-2.0 | 系统、导航和生命周期 |
| Kotlin / JetBrains | 2.0.20 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 | 语言及标准库 |
| kotlinx.coroutines / JetBrains 及贡献者 | 1.8.1 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | 协程 |
| kotlinx.serialization / JetBrains 及贡献者 | 1.7.3 | [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 | JSON |

Apache-2.0 完整文本、PDFBox-Android 的 LICENSE/NOTICE 和 Bouncy Castle 许可见 [third_party](third_party/README.md)。PDFBox-Android 通过 Gradle 引入，未将其整个仓库复制成本项目源码。

## 4. 构建和测试工具

| 项目 | 来源 | 许可 | 使用范围 |
|---|---|---|---|
| Gradle Wrapper 8.9 | [gradle/gradle v8.9.0](https://github.com/gradle/gradle/tree/v8.9.0) | Apache-2.0 及随附声明 | 包含 wrapper JAR、脚本；构建工具本体按配置下载 |
| Android Gradle Plugin 8.5.2 | [Android build tools](https://android.googlesource.com/platform/tools/base/) | Apache-2.0 及组件声明 | 构建 |
| Kotlin Symbol Processing 2.0.20-1.0.25 | [google/ksp](https://github.com/google/ksp) | Apache-2.0 | Room 编译生成 |
| JUnit 4.13.2 | [junit-team/junit4](https://github.com/junit-team/junit4) | EPL-1.0 | 仅测试 |
| Robolectric 4.13 | [robolectric/robolectric](https://github.com/robolectric/robolectric) | MIT | 仅测试 |
| AndroidX Test / Room Testing | [android/android-test](https://github.com/android/android-test)、[androidx/androidx](https://github.com/androidx/androidx) | Apache-2.0 | 仅测试 |
| kotlinx-coroutines-test | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | 仅测试 |

工具链和测试环境按 Gradle 声明获取，不作为 App 业务代码声明原创，也不将其许可改成主项目 MIT。

## 5. 在线服务与开发过程

- 可选 DeepSeek API：[官方文档](https://api-docs.deepseek.com/)。这里只实现客户端调用，不分发模型权重、不包含公用 API Key，不代表 DeepSeek 官方背书。
- 本项目使用 GLM、DeepSeek 辅助编码，Codex 辅助需求与独立复验；这些是开发过程说明，不将模型服务的许可冒充本 App 或角色素材的许可证。
- 未纳入早期候选的 Microsoft Fluent Emoji 普通鲸鱼、DiceBear、Chibi Character Generator；它们不属于当前 APK 的角色资源。

依赖坐标和版本见 [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md)，发布 APK 的完整第三方许可随完整发布包提供。

## 6. 参考项目（仅参考交互，未复制代码）

| 项目 | 链接 | 参考内容 |
|---|---|---|
| FocusTimer / luishramorim | https://github.com/luishramorim/FocusTimer | Compose 番茄钟的圆形进度、暂停继续、会话次数等交互参考；未复制代码或资源 |
| pomodoro-tracker / Dr-klo | https://github.com/Dr-klo/pomodoro-tracker | 原生 Compose 番茄钟与本地统计的交互参考；未复制代码或资源 |

以上项目不是本项目的依赖，也不代表其作者为本项目背书。
