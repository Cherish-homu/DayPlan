# 日序 · DayPlan

<img src="app/src/main/res/drawable-nodpi/chan_portrait.png" alt="社区二创 DeepSeek 娘，作者 YunYueSama" width="120" align="right" />

一个蓝白主题的 Android 日程、待办、倒数日与专注计时 App。数据保存在本机，可选接入用户自己的 DeepSeek API，通过聊天、图片和文档生成可核对的计划，再一键导入。

当前发布版 **v1.5 / versionCode 6**，包名 `com.jsy.dayplan`，最低 Android 8.0（API 26）。

[下载安装](https://github.com/Cherish-homu/DayPlan/releases) · [使用说明](docs/使用说明.md) · [构建与测试](docs/BUILDING.md) · [来源与致谢](THIRD_PARTY_NOTICES.md)

## 功能

- 今天、月历、倒数日、我的四页；日程、待办、重要日子及本地提醒。
- 专注倒计时；今日安排与倒数日桌面小组件。
- 蓝白/深色主题；本地 JSON 备份、恢复前快照与回退。
- App 内 DeepSeek 娘聊天入口，用户自行配置 API Key。
- 文字、JPG/PNG/WebP、PDF、DOCX、XLSX、TXT/Markdown、CSV 联合整理安排；文档先在手机本地提取内容，扫描 PDF 按页转图片。
- 计划先预览，可勾选和编辑、检查来源与冲突、批量导入及撤销。附件批次始终先确认；可选自动保存明确且无冲突的纯文字新增。
- Room 事务保存与持久批次去重；恢复后旧请求、草稿及撤销失效；自动保存事务内检查最新日历。

## 下载和使用

从 [Releases](https://github.com/Cherish-homu/DayPlan/releases) 下载完整发布包（APK、校验值和第三方许可）。发布 APK 使用维护者私有签名，仓库不包含签名密钥。

AI 为可选功能。手动日程等离线功能不需要 Key；启用 AI 后，在“我的 → AI 助手设置”填写自己的 DeepSeek API Key。发送的文字及选定附件内容会传给 DeepSeek，调用由自己的 API 账户计费；编程工具赠送额度不等于手机 App API 额度。

**真实 API 识别质量与实体手机尚未完成验证。** 当前验证覆盖合成材料、本地解析、Mock 端到端、真实请求体结构和模拟器；先用一条测试日程验证预览、导入、提醒和撤销。

## 开发和验证

Kotlin 2.0.20、Jetpack Compose / Material 3、Room 2.6.1、Coroutines/Serialization。构建使用 JDK 17、Android SDK 34、Gradle Wrapper 8.9 和 AGP 8.5.2。

```sh
./gradlew :app:testReleaseUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。SDK 路径按本机设置，首次构建需要下载依赖，详见 [BUILDING.md](docs/BUILDING.md)。

v1.5 在 Windows / Android 14 模拟器环境已验证：

| 验证 | 结果 |
|---|---|
| 正式单元与 Robolectric / Room 测试 | 163/163 |
| 外部独立反例（恢复、去重、并发、附件、自动保存、提醒等） | 13/13 |
| 同签名升级、导入/撤销、快照回退后旧计划失效 | 模拟器通过 |
| 真实模型识别、实体手机、真实拔电 | 未验证 |

外部探针代码与可移植执行入口见 [verification](verification/README.md)。这些是既有环境的验证结果，其他操作系统/ROM 与后续变更需重新测试。

整理后的独立发布副本已再次构建 Debug APK，并通过 163 项正式测试及 13 项相对路径外部探针，见 [发布前核对](docs/VALIDATION.md)。

## 来源和许可证

本仓库由本地日序工程整理发布。开发过程使用 GLM、DeepSeek 辅助编码，Codex 辅助需求整理与独立复验；维护和发布由 [Cherish-homu](https://github.com/Cherish-homu) 负责。

**右下角角色素材不是本项目原创，也不是 DeepSeek 官方授权形象：**

- 作者：[YunYueSama](https://github.com/YunYueSama)。
- 原仓库：[codex-deepseek-pet](https://github.com/YunYueSama/codex-deepseek-pet)，固定 commit `7661c8b304c5400701f91da01b1a643a207331de`。
- 采用 `assets/whale/portrait.png`，裁剪、缩放为全身入口与头部头像；未改色、未重新绘制。
- 素材遵守自定义“大肥鱼项目署名许可 1.0”，**不随主代码改成 MIT**；完整条款和第三方参考边界见 [third_party/deepseek-chan](third_party/deepseek-chan/README.md)。
- 本 App 没有移植原仓库的 Windows 桌宠程序、Electron/Node.js、物理/喂食系统、参考原图或字体，也不需要电脑端运行。

主项目中维护者有权授权的代码采用 [MIT](LICENSE)。第三方图片、依赖与构建工具分别遵守各自许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [DEPENDENCIES.md](docs/DEPENDENCIES.md)。本项目与 DeepSeek、素材作者及依赖维护者无隶属或官方背书关系。

## 当前限制

- 真实模型可能识别错误，模糊日期/课表周次先澄清，导入前核对具体日期和提醒。
- 附件续聊保留最近一轮材料集，最多 6 个附件；XLSX 可选工作表，暂不提供行范围选择。
- 不支持旧 DOC/XLS、加密文档及无限重复日程；DOCX/XLSX 内嵌图片须单独发送。
- API Key、私有签名、用户数据库/备份、聊天、缓存及开发机审计日志均不在仓库内。测试文件是合成样本。
