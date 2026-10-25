# v1.5 发布前核对

日期：2026-10-25。以下是在 Windows、JDK17、Android SDK34 环境实际执行的结果，不等于真实模型识别或实体手机验证。

| 对象 | 执行 | 结果 |
|---|---|---|
| 原开发工程 | 正式单元与 Robolectric / Room 套件 | 163/163，0 失败 |
| 原开发工程 | 外部独立探针 | 13/13，0 失败 |
| 本仓库整理后的独立发布副本 | `:app:testReleaseUnitTest :app:assembleDebug --offline --no-daemon` | 测试 163 项通过，Debug 构建成功 |
| 本仓库相对路径探针入口 | `-I verification/audit.init.gradle` + README 中的三类测试筛选 | 13 项通过 |
| 已签名发布 APK | apksigner 与 SHA256 | v2 签名有效，哈希与原交付保持 |

发布整理没有改动 App 业务 Kotlin/资源文件；增加了 README、MIT 主代码许可、第三方归属与完整许可、跨平台 Gradle 入口，以及可移植的外部探针路径。开发机路径、私有签名、API Key、日程数据库、聊天/备份及原始审计日志没有上传。

源码中少量 `sk-...` 字符串是 Mock / 加密存储测试使用的固定合成样例，不是可用的服务凭据。构建和测试不需要真实 DeepSeek Key。

## 安装包

- 版本：1.5，versionCode 6，包名 com.jsy.dayplan。
- APK SHA256：`C795A6EBAADCD50C8CFFE2DED2E365824D2BFE57EECE498ECCD7049413067CAE`。
- 证书 SHA256：`e2f023e726859cd3496c28713dcd7fd1996ff4d9bde3885ecc98b294b7399ebb`。
- APK 是此前已验证的原签名交付文件，未为上传 GitHub 重新签名；第三方许可另随完整发布包及仓库提供。

## 尚未验证

真实 DeepSeek 文本/视觉识别、实体手机和厂商 ROM、真实拔电，以及其他操作系统上的构建。当前仓库没有声称 GitHub Actions 已自动跑通。

初次试用可在 App 内自行填写 Key，用合成课表或测试日程走完“发送→预览→导入→提醒→撤销”；不向维护者发送个人 Key。
