# F-Droid 提交材料

本目录包含将 YYX Reader 提交到 [F-Droid](https://f-droid.org) 官方仓库（fdroiddata）所需的元数据。

## 为什么本项目适合 F-Droid

- ✅ **BSD-3-Clause** 许可（F-Droid 认可的 FOSS 许可）
- ✅ 零网络权限、零 GMS（F-Droid 政策友好）
- ✅ 纯本地功能（无服务端、无账号）
- ✅ 开源可构建（Gradle 项目 + 仓库内包提交了 sherpa-onnx AAR）
- ⚠️ **唯一需要向审阅者说明的点**：`app/libs/sherpa-onnx-2.0.1.aar` 是预编译原生库（Apache-2.0，官方 release）。
  F-Droid 通常要求从源码构建；sherpa-onnx 的 onnxruntime 全量构建需要数小时且需交叉编译工具链，
  因此以「预置二进制库」形式提交，并在 MR 说明中注明来源（GitHub release URL + 校验和）。
  审阅者可能要求 `prebuild` 步骤或接收该二进制（多数含 ONNX/FFmpeg 类库的应用均按此方式处理）。

## 提交步骤（一次性）

1. **Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata)**（GitLab）
2. 把 `fdroid/metadata/com.yyx.reader.yml` 复制为 fdroiddata 仓库根下的 `metadata/com.yyx.reader.yml`
3. 创建分支（如 `com.yyx.reader`），提交并开 **Merge Request**
   - MR 标题：`New app: YYX Reader (com.yyx.reader)`
   - 说明里附上：预置 AAR 的来源与理由（见上）、`./gradlew :app:assembleOfflineDebug` 可本地构建成功、无网络权限（`verifyOfflineDebugCompliance` 断言）
4. 在 [F-Droid 论坛](https://forum.f-droid.org/) 或 IRC/Matrix `#fdroid` 频道催审 / 答疑
5. 审阅通过后 F-Droid 构建服务器会从 tag 构建并发布；后续版本由 `UpdateCheckMode: Tags` 自动检测

## 本地验证构建（提交前自检）

```bash
export JAVA_HOME=$(/usr/libexec/java_home) && export ANDROID_HOME=$HOME/Library/Android/sdk
cd yyx-reader
./gradlew :app:assembleOfflineDebug :app:check   # 均可离线完成（Gradle 分发走腾讯镜像，见 wrapper 配置）
```

## 商店素材（fastlane）

`fastlane/metadata/android/{en-US,zh-CN}/` 下已放置标题、简介与截图；
F-Droid 会从源码仓库读取这些素材（标题/短描述/截图）。更新截图时保持文件名 `NN-*.png` 约定。
