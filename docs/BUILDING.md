# 构建、签名和测试

## 环境

- JDK 17。
- Android SDK Platform 34、Build Tools 34.0.0、Platform Tools；Android Studio 可安装这些组件。
- 使用仓库 Gradle Wrapper 8.9；AGP 8.5.2、Kotlin 2.0.20、KSP 2.0.20-1.0.25 已固定在构建脚本。
- 通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或未跟踪的 `local.properties` 设置本机 SDK 路径。

Windows 的 `local.properties` 示例：`sdk.dir=D:/Android/Sdk`；这是示例，不要提交自己的本地路径和签名配置。仓库不会要求存在其他个人项目或特定 E 盘目录。

## 普通构建

```sh
./gradlew :app:testReleaseUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

Windows 将 `./gradlew` 换为 `gradlew.bat`。首次需要联网下载 Gradle 和依赖；缓存完整后可加 `--offline`。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。Debug 有标注清楚的 Mock 演示开关，release 不包含 Mock 客户端实现。

也可运行 `scripts/build_apk.bat`，脚本使用当前环境的 JDK / SDK，不修改系统全局配置。

## Release 签名

```sh
./gradlew :app:assembleRelease --no-daemon
```

没有私有签名配置时生成未签名 release APK。需要自己发布时，在本机创建已被 `.gitignore` 排除的 `keystore.properties`：

```properties
storeFile=keystore/your-release-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

这些均为占位符。不要上传真实配置或密钥。维护者 Releases 的签名私钥不在仓库中，自建版本需使用自己的签名；签名不同不能覆盖维护者发布包，迁移前先用 App 导出自己的数据备份。

发布包 v1.5 的证书 SHA256：`e2f023e726859cd3496c28713dcd7fd1996ff4d9bde3885ecc98b294b7399ebb`。APK SHA256：`C795A6EBAADCD50C8CFFE2DED2E365824D2BFE57EECE498ECCD7049413067CAE`。

## 独立反例

见 [verification/README.md](../verification/README.md)，在普通正式 163 项之外执行 13 项外部探针；不混入 main/release 源码。

## API Key

构建不需要 DeepSeek Key。用户在安装后的 App 设置中填写自己的 Key；源码/BuildConfig/CI 不携带 Key。不向构建者或维护者提供个人 Key。
