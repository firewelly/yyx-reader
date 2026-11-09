# MR 提交材料（New app: YYX Reader）

提交到 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 时，直接复制以下内容。

## MR 标题

```
New app: YYX Reader (com.yyx.reader)
```

## MR 描述（复制粘贴）

```markdown
### App

YYX Reader — a fully offline, ad-free local novel reader (TXT/EPUB) with text-to-speech listening.

- Source: https://github.com/firewelly/yyx-reader
- License: BSD-3-Clause
- Package: `com.yyx.reader`
- Format: Kotlin + Jetpack Compose, Gradle 8.10.2 (wrapper), AGP 8.7.3, JDK 17
- Build: `./gradlew :app:assembleOfflineDebug` (variant `offline`)
- No network permission in the offline variant (manifest verified by a build-time compliance task).
  No ads, no analytics, no accounts.

### Notes for reviewers

- **Prebuilt library**: `app/libs/sherpa-onnx-2.0.1.aar` (Apache-2.0, official release
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) is vendored as a prebuilt
  AAR. Building sherpa-onnx + onnxruntime from source requires a full cross-compilation
  toolchain and hours of build time, which is not practical with the F-Droid buildserver.
  The library is an **optional** built-in offline TTS fallback (voice models are imported
  by the user at runtime via SAF; no models are bundled). The app works without it using any
  offline system TTS engine.
  If reviewers require, we can explore `prebuild`-based approaches or dropping the optional
  engine in an F-Droid-specific flavor.

- The default `offline` flavor merges a manifest with **no INTERNET / ACCESS_NETWORK_STATE**
  permissions (there is a second `full` flavor reserved for a future optional WebDAV backup
  feature; the F-Droid build must use `offline`).

- `fastlane/metadata/android/{en-US,zh-CN}/` in the source repo provides the listing
  title, short/full description and screenshots.

### Version

- versionName `0.1.0`, versionCode `1`, commit/tag `v0.1.0`.
```
