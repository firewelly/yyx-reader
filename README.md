# YYX Reader（YYX 阅读器）

安卓原生（Kotlin + Jetpack Compose）**全离线**本地小说阅读器：扫描管理本地 TXT / EPUB、无广告阅读器、TTS 听书（外放 / 耳机），**听书进度与阅读进度是同一条进度**（章节序号 + 章内字符偏移）。

> 完全无广告 · 全离线运行（不依赖任何 Google 服务与在线接口）· 零数据收集 · 无账号

## 功能

- **书架**：扫描导入本地 TXT / EPUB（双编码探测、章节正则、字节偏移索引），标签分组与筛选、书签
- **阅读器**：分页渲染（StaticLayout 按行切页）、日间/夜间/护眼主题、字号行距、音量键翻页、左右 1/4 屏翻页
- **听书**：系统 TTS 引擎（自动 / 指定，引擎无关）或内置 sherpa-onnx 离线引擎（模型 SAF 导入，备选）；
  句级控制、语速 0.75x–3x、定时停止、高亮跟随、前台服务 + 通知/锁屏/耳机键
- **统一进度**：读 / 听共用一条进度（source = READ / LISTEN），跨章自动续播
- **输出路由**：强制外放（系统引擎降级）；本地引擎 `AudioTrack.setPreferredDevice` 精确选择输出设备
- **备份与恢复**：单 JSON（流式读写，≤64MB）、覆盖式恢复、导入告警
- **多语言**：默认英文 + 中文（跟随系统语言；新增语言只需新增 `values-xx/`）

## 构建

环境：JDK 17、Android SDK（compileSdk 35）、Gradle 8.10.2（wrapper 已含；分发走腾讯镜像）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew assembleOfflineDebug        # 默认分发变体（无网络权限）
./gradlew :app:check                  # CI 门禁：lint + 合规断言 + 单测
./gradlew :app:testOfflineDebugUnitTest
./gradlew :app:connectedOfflineDebugAndroidTest   # 仪器测试（需设备/模拟器）
```

`offline` 变体为默认分发（合并 manifest 无 `INTERNET` / `ACCESS_NETWORK_STATE`）；
构建期合规任务 `verifyOfflineDebug/ReleaseCompliance` 会断言零网络与无 GMS/Firebase/MLKit 依赖。

## 目录

```
app/src/main/java/com/yyx/reader/
├── core/            # 领域模型、Room 数据库、TXT/EPUB 引擎、TTS（系统 + sherpa-onnx）、音频路由
├── data/            # 仓库实现、导入/扫描、备份（流式 JSON）
├── feature/         # Compose 界面：library / bookdetail / reader / settings
├── service/         # 听书前台服务、MediaSession、播放控制器
└── di/              # Hilt 模块
docs/                # PRD、架构、隐私检查、Google Play 发布指南
```

详细架构与决策记录见 `docs/ARCHITECTURE_ANDROID.md`、`docs/PRD_ANDROID.md`、`AGENTS.md`。

## 隐私

零网络、零采集、零追踪。详见 [docs/PRIVACY_CHECK.md](docs/PRIVACY_CHECK.md)。

## 第三方组件

- sherpa-onnx（Apache-2.0，`app/libs/sherpa-onnx-2.0.1.aar`，本地离线 TTS 引擎，备选功能）
- jsoup（MIT）、juniversalchardet（MPL-1.1）、AndroidX / Media3（Apache-2.0）

详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 授权

**BSD 3-Clause** —— 使用 / 修改 / 再分发均可，但须保留版权声明与许可文本（即「引用」要求），
且不得使用作者（Menglong Yuan）名义为衍生产品背书；**不提供任何担保**（AS IS）。完整文本见 [LICENSE](LICENSE)。

Copyright (c) 2026, Menglong Yuan
