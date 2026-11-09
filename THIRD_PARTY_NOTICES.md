# 第三方组件声明（THIRD PARTY NOTICES）

本项目（YYX Reader）再分发时包含以下第三方组件，其授权条款适用各自组件：

| 组件 | 版本 | 授权 | 用途 |
|---|---|---|---|
| sherpa-onnx | 2.0.1（`app/libs/sherpa-onnx-2.0.1.aar`） | Apache License 2.0 | 内置离线 TTS 引擎（备选功能） |
| jsoup | 见 `gradle/libs.versions.toml` | MIT License | EPUB HTML → 纯文本 |
| juniversalchardet | 见 `gradle/libs.versions.toml` | MPL 1.1 | TXT 编码探测 |
| AndroidX / Jetpack Compose / Room / DataStore / Hilt / Media3 | 见 `gradle/libs.versions.toml` | Apache License 2.0 | UI / 数据库 / 播放 |
| Kotlin / kotlinx.coroutines | 见 `gradle/libs.versions.toml` | Apache License 2.0 | 语言与协程 |

- Apache-2.0 组件：保留其版权与许可声明；本仓库以源码/二进制形式包含 sherpa-onnx AAR，未修改其内容。
- jsoup（MIT）、juniversalchardet（MPL-1.1）依其 Maven 制品原样引入，未修改。
- 各组件完整许可文本可在其官方发布页 / Maven 制品中获得：
  - sherpa-onnx：https://github.com/k2-fsa/sherpa-onnx （Apache-2.0）
  - jsoup：https://jsoup.org/license （MIT）
  - juniversalchardet：https://code.google.com/archive/p/juniversalchardet/ （MPL-1.1）
