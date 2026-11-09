# Google Play 发布指南（YYX Reader）

面向将本应用上架 Google Play 的准备工作与流程。**按当前（2025–2026）政策整理，提交前以 Play Console 实时要求为准。**

## 0. 前置条件

1. **开发者账号**：Play Console 一次性注册费 $25（个人/公司均可）。
   - 个人新账号需完成身份验证；且新个人账号发布前必须完成 **闭式测试（Closed testing）**：
     邀请一定数量测试者（当前要求 12–20 人，以 Console 显示为准）连续参与 **14 天**，
     之后才可申请生产发布。
2. **签名材料**：创建 upload keystore（见 §2），建议启用 **Play App Signing**（Google 托管最终签名）。
3. **隐私政策 URL（必填）**：即使零收集也要提供。可用本仓库文件：
   `https://github.com/firewelly/yyx-reader/blob/main/docs/PRIVACY_CHECK.md`
   （或 GitHub Pages 渲染版）。

## 1. 构建 AAB（Play 只收 App Bundle）

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
export ANDROID_HOME=$HOME/Library/Android/sdk
cd yyx-reader
./gradlew :app:bundleOfflineRelease
# 产物：app/build/outputs/bundle/offlineRelease/app-offline-release.aab
```

- 当前 `release` 构建为 **unsigned**（R8 已启用），需先配置签名（§2）才能上传。
- 分发变体使用 **`offline`**（默认，无网络权限）。`full` 为 V2 预留，Play 版不要用。

## 2. 发布签名配置（一次性）

```bash
# 生成 upload keystore（妥善保管，丢失无法自行更新——可用 Play App Signing 找回上传密钥）
keytool -genkeypair -v \
  -keystore upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS
```

`app/build.gradle.kts` 中追加（**不要把 keystore/密码提交仓库**；用环境变量或 `~/.gradle/gradle.properties`）：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("YYX_KEYSTORE") ?: "upload-keystore.jks")
            storePassword = System.getenv("YYX_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("YYX_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("YYX_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
```

> **R8 注意**：`proguard-rules.pro` 已包含 `-keep class com.k2fsa.sherpa.onnx.**`（JNI 回调必需），勿删。

## 3. 技术合规检查（Play 硬性要求）

| 要求 | 本项目状态 |
|---|---|
| `targetSdk` 合规（2025-08-31 起新提交需 API 35） | ✅ `targetSdk = 35`（2026-08-31 起需 36，届时升级） |
| **16 KB page size 支持**（2025-11-01 起新应用/更新强制） | ✅ 已核验：AAR 全部 `.so` 的 LOAD 段 `p_align = 16384`（16KB 对齐） |
| 64-bit 支持 | ✅ arm64-v8a（+ armeabi-v7a 可选） |
| ABI 体积 | AAB 由 Play 自动按 ABI 分流，arm64 用户下载 ≈ 55MB（含 ~30MB native） |
| 无网络权限 | ✅ offline 变体合并 manifest 无 `INTERNET`/`ACCESS_NETWORK_STATE`（CI 断言） |
| 无后台自启/无敏感权限 | ✅ 6 项功能性权限；导出组件仅分享导入 Activity |
| 广告声明 | 无广告 SDK；商店「广告」表单选「无广告」 |

## 4. 商店资料（Store listing）

- **应用名称**：`YYX Reader（YYX 阅读器）`（≤30 字符）
- **简短描述**（≤80 字符，示例）：
  - 中文：`全离线无广告本地小说阅读器：TXT/EPUB、TTS 听书、听读同进度`
  - 英文：`Offline ad-free novel reader: TXT/EPUB, TTS listening, synced progress`
- **完整描述**：见本仓库 `README.md`（功能/隐私/授权），补充「零网络、零收集」声明
- **图标**：512×512 PNG（32-bit，无透明）
- **Feature graphic**：1024×500 PNG/JPG
- **截图**：手机与 10 寸平板各 ≥2 张（书架/阅读器/听书面板/设置）
- **应用类别**：图书与工具书（Books & Reference）；**标签**：阅读器/电子书/离线
- **内容分级问卷**：无暴力/色情/恐怖内容 → 预期 3+/Everyone
- **目标受众**：13+（或按实际情况；涉及 UGC？无——本地书籍，无需社交声明）
- **应用访问说明**：无需登录/账号（直接可用）
- **Data safety 表单**：**不收集任何数据、不与第三方共享任何数据**
  - 系统 TTS 为设备上的第三方应用，不属本应用收集行为
- **隐私政策 URL**：见 §0

## 5. 发布轨道与流程

1. **Internal testing**（最多 100 人，快速自测）→ 上传 AAB、填资料、验证安装
2. **Closed testing**（新个人账号必须：12–20 测试者 × 14 天）
3. **Production**：提交审核（首次审核通常 1–7 天）

## 6. 审核风险与应对（本应用特有）

| 风险点 | 说明 / 应对 |
|---|---|
| 「全离线」声明一致性 | 应用无 `INTERNET` 权限，与声明一致，风险低 |
| 阅读器版权内容 | Play 不审查本地文件；商店描述避免暗示内置书源/爬虫（本项目不做在线书城） |
| 系统 TTS 可选依赖 | 说明「可使用设备自带 TTS；无引擎时可用内置离线引擎」，非必需外部应用 |
| 内置离线引擎体积 | AAB 分流后 arm64 ≈ 55MB，属可接受；如需更小可考虑 ABI split 或按需下载（会破坏全离线定位，不建议） |
| 无 GMS 依赖 | 不接 Play Billing（如需付费：设**付费下载**即可，无需引入 GMS SDK；应用内购才需要 Billing，会违反零 GMS 红线） |

## 7. 版本管理建议

- `versionCode`/`versionName` 在 `app/build.gradle.kts`（当前 `versionCode = 1`、`versionName = "0.1.0"`）
- 每次上传递增 `versionCode`；Git tag 与 release notes 对应（如 `v0.1.0`）

## 8. 不要做的事

- ❌ 不要提交 `upload-keystore.jks`、密码、`local.properties` 到仓库（`.gitignore` 已覆盖）
- ❌ 不要为 Play 引入 GMS/Play Services（违反项目合规红线，且非必需）
- ❌ 不要用 `full` 变体提交（V2 预留，未完成）
