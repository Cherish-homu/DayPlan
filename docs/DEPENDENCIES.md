# 依赖坐标和来源

构建脚本是直接依赖的权威来源；下面固定版本对应 v1.5。第三方许可和作者链接见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

| 范围 | Maven 坐标 | 版本 |
|---|---|---|
| UI BOM | androidx.compose:compose-bom | 2024.09.03 |
| UI | androidx.compose.ui:ui | BOM 管理 |
| UI | androidx.compose.material3:material3 | BOM 管理 |
| UI 图标 | androidx.compose.material:material-icons-extended | BOM 管理 |
| Activity | androidx.activity:activity-compose | 1.9.2 |
| Navigation | androidx.navigation:navigation-compose | 2.8.2 |
| Lifecycle | androidx.lifecycle:lifecycle-viewmodel-compose、lifecycle-runtime-compose、lifecycle-runtime-ktx | 2.8.6 |
| Core | androidx.core:core-ktx | 1.13.1 |
| Room | androidx.room:room-runtime、room-ktx、room-compiler | 2.6.1 |
| JSON | org.jetbrains.kotlinx:kotlinx-serialization-json | 1.7.3 |
| Coroutines | org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 |
| PDF | com.tom-roush:pdfbox-android | 2.0.27.0 |
| PDF 传递依赖 | org.bouncycastle:bcprov-jdk15to18、bcpkix-jdk15to18、bcutil-jdk15to18 | 1.72 |
| EXIF | androidx.exifinterface:exifinterface | 1.3.7 |
| 测试 | junit:junit | 4.13.2 |
| 测试 | org.jetbrains.kotlinx:kotlinx-coroutines-test | 1.8.1 |
| 测试 | org.robolectric:robolectric | 4.13 |
| 测试 | androidx.test:core | 1.6.1 |
| 测试 | androidx.test.ext:junit | 1.2.1 |
| 测试 | androidx.room:room-testing | 2.6.1 |
| Debug | androidx.compose.ui:ui-tooling | BOM 管理 |

查看当前完整运行时传递依赖树：

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

本仓库不收录 Maven 缓存和依赖 JAR/AAR 本体，Gradle 从 Google Maven、Maven Central 和 Gradle Plugin Portal 按固定脚本解析。库与模型服务分别有自己的许可/使用条款。
