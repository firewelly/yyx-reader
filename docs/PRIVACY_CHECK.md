# 隐私检查报告（PRIVACY CHECK）

- **检查日期**：2026-11-09（V1.5.x 收尾，多语言版本）
- **检查对象**：`offline` 分发变体（debug + release-unsigned APK）
- **结论**：**零网络、零采集、零追踪**；全部数据仅存本机。可对外声明「全离线 · 无广告 · 零数据收集」。

## 1. 权限清单（release APK，`aapt2 dump permissions`）

| 权限 | 用途 | 说明 |
|---|---|---|
| `POST_NOTIFICATIONS` | 听书前台通知 | 运行时申请，可拒绝 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 听书前台服务 | 必需 |
| `WAKE_LOCK` | 听书期间保持播放 | 必需 |
| `MODIFY_AUDIO_SETTINGS` | 强制外放（系统引擎降级路由） | 安装即授予 |
| `READ_EXTERNAL_STORAGE`（maxSdkVersion=32） | Android 12 及以下读取扫描目录 | 13+ 走 SAF 无需权限 |

- ✅ **无 `INTERNET`**
- ✅ **无 `ACCESS_NETWORK_STATE`**（media3 声明已在 offline manifest 剔除）
- 无定位/相机/麦克风/通讯录/电话/短信等任何敏感权限
- 自检命令：`aapt2 dump permissions app/build/outputs/apk/offline/*/…apk`，CI 门禁 `verifyOffline{Debug,Release}Compliance` 自动断言

## 2. 代码网络 API 扫描

全量 grep `app/src/main/java`：`java.net.URL` / `HttpURLConnection` / `Socket` / `WebView` / `OkHttp` / `Retrofit` → **无**。
仅 `EpubParser` 使用 `URLDecoder/URLEncoder`（HTML href 解码工具，**非网络 I/O**）。

## 3. 第三方依赖（运行时）

AndroidX / Jetpack Compose / Hilt / Room / DataStore / Media3 / jsoup / juniversalchardet /
sherpa-onnx（本地离线 TTS，`app/libs` vendor AAR，manifest 零权限）。

- ❌ 无 GMS / Firebase / ML Kit / Play Services（CI 依赖树断言）
- ❌ 无任何统计/埋点/推送/广告/崩溃上报 SDK（Umeng/Bugly/Adjust/AppsFlyer/Sentry/Crashlytics 等全部未引入）
- 崩溃日志：`Thread.setDefaultUncaughtExceptionHandler` 写 `files/logs/`，**最多 5 份**，仅本机，不上传

## 4. 数据存储与出口

| 数据 | 位置 | 出口 |
|---|---|---|
| 书库索引/进度/书签/标签 | Room DB（应用私有目录） | 无 |
| 设置 | DataStore（应用私有） | 无 |
| 备份文件 | 用户经 SAF 自选位置（JSON） | 用户自己决定 |
| 崩溃日志 | `files/logs/`（≤5 份） | 无（设置页可选查看/清除） |
| 离线语音模型 | `files/tts_model/`（用户 SAF 导入） | 无 |

SAF 授权仅限用户显式选择的目录；无后台扫描、无自启动。

## 5. 组件暴露面（release APK）

- `com.yyx.reader.MainActivity`（exported=true）：仅接收 `ACTION_SEND`/`ACTION_VIEW`（`text/plain`、`application/epub+zip`），即「分享/打开方式导入」，为功能必需
- `androidx.profileinstaller.ProfileInstallReceiver`（exported=true）：AndroidX 基线配置安装器，受 `android.permission.DUMP`（signature|privileged）保护，普通第三方应用不可触发
- 所有 Service（听书/通知监听）与 Receiver（拔耳机监听）均 `exported=false`

## 6. 用户可见承诺（设置页「关于」）

- 完全无广告 · 全离线运行（不依赖任何 Google 服务与在线接口）
- 本地优先 · 零数据收集 · 无账号
- 不做在线书城与爬虫

## 7. 已知边界（诚实声明）

1. **系统 TTS 引擎是用户设备上的第三方应用**（如联想天禧AI TTS、eSpeak、RHVoice）：本 App 无法审计其行为；若用户选择的系统引擎自身联网，属该引擎行为，与本 App 无关（App 不授予、不代理其网络访问）。
2. 备份 JSON 由用户自行选择保存位置（可能含云端网盘），属用户自主行为。
3. 内置离线引擎（sherpa-onnx）纯本地推理，无任何网络路径。
