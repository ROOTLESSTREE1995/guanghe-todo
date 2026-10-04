# 第三方组件与资源

项目自有源码和随仓库提供的文档、资源采用根目录的 [MIT License](LICENSE)。第三方组件继续适用各自的许可证。

## Gradle Wrapper 8.11.1

- 文件：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`。
- 来源：[Gradle 官方项目](https://github.com/gradle/gradle/tree/v8.11.1)。
- 许可证：[Apache License 2.0](licenses/Apache-2.0.txt)。
- 两个启动脚本保留原有版权与许可声明；Wrapper JAR 内保留 `META-INF/LICENSE`。该部分不因主项目的 MIT 许可而改用 MIT。

## 构建时依赖

Android Gradle Plugin、Gradle distribution、JUnit、Android SDK 和 JDK 在构建或工具链安装时从相应发行方下载，不随本源码仓库分发；使用时仍须遵守发行方的许可与条款。依赖版本见 Gradle 配置和工具链脚本。

## 品牌图形

应用图标使用 AI 图像生成工具制作，并以 Android 图标资源形式适配。生成来源、提示词和文件位置记录在 [branding/DESIGN.md](branding/DESIGN.md)。
