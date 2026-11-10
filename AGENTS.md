# AGENTS.md — YYX 阅读器（yyx-lib / Android）

给 AI 代理 / 新会话的最小上下文、构建命令与当前待办。细节见 `docs/PRD_ANDROID.md`（需求）与 `docs/ARCHITECTURE_ANDROID.md`（架构）。姊妹项目：`/Users/firewell/bin/novelmgt`（Flutter 桌面/Web 书库，TXT 章节正则与编码探测算法由此移植）。

## 项目一句话

安卓原生（Kotlin + Compose）**全离线**本地小说阅读器：扫描管理本地 TXT/EPUB、无广告阅读器、TTS 听书（外放/耳机），**听书进度与阅读进度是同一条进度**（章节序号 + 章内字符偏移）。

## 合规红线（违反即打回）

1. **禁止 GMS / Firebase / ML Kit / 任何 googleapis 在线接口**；依赖仅限 AOSP/AndroidX/纯本地开源库
2. **`offline` 变体（默认分发）manifest 不得含 `INTERNET`**；`ACCESS_NETWORK_STATE` 已在 `app/src/offline/AndroidManifest.xml` 用 `tools:node="remove"` 剔除（media3 会合并进来，勿删）
3. **不做爬虫/在线书城/书源**（novelmgt 的 crawler_service 明确不移植）
4. 零数据收集：无埋点/无统计/无账号；崩溃日志仅存本地
5. TTS 引擎无关：不绑定 Google TTS；manifest 已声明 `<queries>` + `TTS_SERVICE`（Android 11+ 包可见性，勿删）

## 架构与关键路径

| 事项 | 位置 |
|---|---|
| 需求文档 / 架构文档 | `docs/PRD_ANDROID.md` / `docs/ARCHITECTURE_ANDROID.md`（含决策记录 D1–D3、风险表） |
| 领域模型 + 仓库接口 | `app/src/main/java/com/yyx/reader/core/domain/`（model/Models.kt、repository/Repositories.kt；V1.2 新增 model/Tag.kt、model/BackupModels.kt、repository/TagRepository.kt、repository/BackupRepository.kt） |
| Room 数据库 | `core/database/`（entities/daos/YYXDatabase；**schema v2**：`tags`（name 唯一索引）+ `book_tags`（复合主键 + books/tags 双 CASCADE 外键 + book_id/tag_id 索引）；v1→v2 `MIGRATION_1_2`（internal）5 条 SQL 必须与 Room 生成语句逐字节一致；**`exportSchema=true`** → `app/schemas/com.yyx.reader.core.database.YYXDatabase/2.json`（14,014B，identityHash `01b2213e…`）；`MigrationV1ToV2Test`（4 例，自建真实 v1 库 + 与生产一致的 Room 打开路径）已入仪器测试；仅 book_tags 有 DB 外键，chapters/reading_progress/bookmarks 无外键（既有债），PRAGMA foreign_keys=ON） |
| 数据库维护（V1.4） | `core/database/OrphanCleaner`（`@Singleton`）：启动时在 `YYXApp` 的 IO 协程内单事务清理历史孤儿行（chapters/reading_progress/bookmarks 悬空 `book_id`、book_tags 任一端失效），失败只记日志；`LibraryViewModel`/详情删除走 `BookRepositoryImpl.deleteBook` 事务内清子表。与备份「导入只告警不删」语义分开 |
| 标签与书架筛选（F-13） | `feature/library/LibraryFilters.kt` 纯函数（`filterShelf`/`commonTagIds`/`tagSummary`）+ `data/repository/TagRepositoryImpl`（名称 trim/去空/≤20 字，重名 IGNORE 返回 null）；UI 在 `feature/library/LibraryScreen.kt`（筛选 chips、批量打标面板、卡片标签摘要）；`LibraryViewModel` 在标签 Flow 失效时自动复位 `filterTagId` 并按新筛选重算书架 |
| 备份与恢复（F-12） | `data/backup/`（BackupFormat / BackupJsonWriter / BackupStreamReader / BackupRepositoryImpl）+ `core/domain/model/BackupModels.kt`、`core/domain/repository/BackupRepository.kt`、`di/BackupModule.kt`；单 JSON（schema=1）SAF 直写/直读、**覆盖式**导入；**V1.4 流式化**：导出 `BackupJsonWriter` 边写边校验、导入 `android.util.JsonReader` 两遍（scan 元信息 → 逐表逐行回填），**上限 64MB**、行级内存有界、无 org.json 整串编解码；**健壮性**：缺 **核心表（books/chapters）** 直接拒绝（先于清空）、缺非核心表键保留本机 + warning、有键空数组照常清空、导入后逐表真实 `COUNT(*)`、重复主键/无效 book_tags 引用/引用完整性告警、恢复后立即 `SystemTtsEngine.useEngine` 生效、播放中拒绝恢复、抽样 ≤50 书源/扫描目录可访问性预警；**仍只含数据库索引与设置，不含书源文件与 SAF 授权** |
| 听书输出路由（F-08 + V2 精确路由） | `core/audio/`（`AudioOutputs` 纯函数：类型→中文名/分组/强制外放能力/去重摘要/**设备路由选择器 `deviceRouteKey`/`matchesRouteKey`（`t<type>[:<address>]`，JVM 可测）**；`AudioOutputRepositoryImpl` callbackFlow + `AudioDeviceCallback`，`observeOutputs()` 去重摘要 + `observeDevices()` 逐设备（含 id/address），异常返空）+ `di/AudioModule.kt`；`ListenSettings.forceSpeaker`（`tts_force_speaker`）+ **`preferredOutput`（`tts_preferred_output`，V2 新增，备份可选字段 `ttsPreferredOutput`）**；系统引擎=TtsPlaybackService 接管 speakerphone 降级；**本地引擎=AudioTrack `setPreferredDevice` 定向任意在线设备**（听书面板「输出到」单选：跟随系统/逐设备，设备不在线自动回退，UI 上限 6 行；优先级 preferredOutput > forceSpeaker）；`getAddress()` 需 **API 28**（26/27 空地址仅按类型匹配，已有版本守卫）；manifest 普通权限 **`MODIFY_AUDIO_SETTINGS`**。**真机实际路由效果未验证** |
| 合规校验任务 | `app/build.gradle.kts`（`verifyOfflineDebug/ReleaseCompliance`、`verifyFullDebug/ReleaseCompliance`，group=verification；`:app:check` 已挂 offline 两个）：断言合并 manifest 无 INTERNET/ACCESS_NETWORK_STATE、含 TTS_SERVICE `<queries>`，依赖树无 GMS/Firebase/MLKit/Play |
| TXT 引擎（编码探测/章节正则/中文数字/字节偏移索引） | `core/ebook/txt/`；**章节号单调递增过滤**在 TxtChapterIndexer（防正文「第1章第0段：…」误判），卷号重置计数 |
| EPUB 引擎（OPF/NCX/Jsoup 抽文本） | `core/ebook/epub/`；章节文本缓存 `files/epub_text/{bookId}/{idx}.txt` |
| 章节文本唯一入口 | `core/ebook/ChapterTextSource`（阅读/听书共用） |
| **统一进度**（听读同步核心） | `reading_progress` 表：每书一行 `(chapter_idx, char_offset, percent, source)`；写入入口 `data/repository/ProgressRepositoryImpl` |
| 分页引擎 | `feature/reader/ChapterPaginator`（StaticLayout 按行切页，页=字符区间） |
| **多语言（V1.5.x）** | `app/src/main/res/values/strings.xml`（**英文 = 默认**，非中文系统一律英文）+ `values-zh/strings.xml`（中文，简体/繁体系统均匹配 zh）。**新增语言 = 复制一份 values-xx/ 并翻译**，代码零改动。界面文案全部 `stringResource(R.string.*)`；VM/服务/数据层 `context.getString(R.string.*)`；纯逻辑类（`LocalModelLocator.LocateError/ModelLabel`、`BackupFormat.BackupError`、`AudioOutput.typeLabelRes`、`ReaderTheme.labelRes`）返回**枚举/资源 ID**，由界面层映射（保住 JVM 单测可行性）。**不翻译**：Logcat 日志、章节解析正则（`第…章` 是中文书解析规则）、入库内容标题（`全文/开篇/第N章`）、用户数据（书名/标签名）。**浮点多语言注意**：个数/字数格式各语言必须同类型（lint StringFormatMatches；中文「万字/亿字」分支按 Locale 选择） |
| TTS | `core/tts/`（SentenceSplitter 分句带字符区间；SystemTtsEngine **显式选引擎**：用户指定 → 系统默认（若有效）→ 首个已安装）；前台服务 `service/tts/TtsPlaybackService`（句开始即回写进度 source=LISTEN） |
| **内置离线 TTS（V1.5，待办 #6）** | `core/tts/LocalModelLocator.kt`（纯 JVM 模型目录探测：MeloTTS/Matcha/Kokoro 三类布局，int8 优先，可单测）+ `core/tts/LocalTtsEngine.kt`（sherpa-onnx `generateWithCallback` 流式合成 → AudioTrack 自播，utteranceId 协议与系统引擎一致；`setSpeechRate`→sherpa speed、pitch 无对应能力忽略；forceSpeaker→`AudioTrack.setPreferredDevice(内置扬声器)` 真·设备级路由；**drain 停滞守卫**：head 5s 无推进判输出不可用跳句，防无头环境卡死）+ `core/tts/TtsEngineRouter.kt`（门面路由，listener 双挂）+ `di/TtsModule.kt`（`TtsEngine`←Router）。引擎选择复用 `tts_engine` 字符串：保留值 **`local:sherpa-onnx`**=内置引擎、null=系统自动、其他=系统包名。模型**不内置 APK**：SAF 目录导入（先在 SAF 列表探测校验→tmp 拷贝→复检→原子替换 `files/tts_model/`），`LocalTtsEngine.modelDir()` 固定目录、不参与备份（恢复到无模型设备 init 失败给中文引导）。**依赖**：`app/libs/sherpa-onnx-2.0.1.aar`（49,441,341B，Apache-2.0，官方 release，manifest 零权限已核验）；**AAR 自带 proguard.txt 为空**，R8 需 `app/proguard-rules.pro` 的 `-keep class com.k2fsa.sherpa.onnx.**`；`abiFilters=arm64-v8a+armeabi-v7a`（全 4 ABI 未压缩 126MB，x86/x86_64 舍弃）。**⚠ D8 陷阱**：传给 sherpa JNI 的 Kotlin 回调**必须用对象表达式**，lambda 被 D8 改写成 `$$ExternalSyntheticLambdaN`（仅剩擦除桥方法）→ JNI 按 `invoke([F)Ljava/lang/Integer;` 精确签名查找时 `NoSuchMethodError` → SIGABRT（编译/单测均测不出，仅运行时崩溃） |
| 扫描（V1.5.x 方案 C） | `data/LibraryScanner`（**混合模式**：SAF 已授权文件夹照常遍历；开启「所有文件访问」后额外 `java.io.File` 直扫 Download/Documents——**系统禁止 SAF 整目录授权这两个根目录**，见 ScanSheet 提示）；UI 在 ScanSheet（所有文件访问状态行 + 去开启 deep link + 开始扫描按钮在 `folders.isEmpty() && !allFilesGranted` 时禁用）；manifest 新增 `MANAGE_EXTERNAL_STORAGE`（可选、用户经系统设置授予，零网络红线不受影响；Play 上架有拒审风险，见 docs/GOOGLE_PLAY_RELEASE.md） |
| 状态总线 | `service/tts/TtsPlaybackController`（executor 模式：UI 发命令 → 服务执行） |
| 版本目录 | `gradle/libs.versions.toml`（AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / Hilt 2.52 / Room 2.6.1 / Media3 1.5.0） |
| 构建 flavor | `offline`（默认，无网络）/ `full`（V2 预留 WebDAV） |

## 常用命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home)   # 必需：~/.zshrc 已修复失效的 openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk

cd /Users/firewell/bin/yyx-lib
./gradlew assembleOfflineDebug                # 构建（Wrapper 分发走腾讯镜像，services.gradle.org 不可达）
./gradlew :app:compileOfflineDebugKotlin      # 编译（KSP 同时导出 Room schema 到 app/schemas/；DB 变更后需提交 N.json）
./gradlew :app:testOfflineDebugUnitTest       # 单元测试（85 个：解析器/中文数字/EPUB/备份格式+流式写出/书架筛选/音频输出/离线模型探测）
./gradlew :app:connectedOfflineDebugAndroidTest  # 仪器测试（25 个，需模拟器/真机；含迁移 / 备份流式 / DB 维护）**注意：跑完会自动卸载应用与其数据**
./gradlew :app:connectedOfflineDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yyx.reader.migration.MigrationV1ToV2Test  # 仅跑迁移测试（4 例）
./gradlew installOfflineDebug                 # 安装到已连接设备

# 合规门禁（CI 落地版）：无 INTERNET / ACCESS_NETWORK_STATE、含 TTS_SERVICE <queries>、依赖树无 GMS/Firebase/MLKit/Play
./gradlew verifyOfflineDebugCompliance         # 单变体校验（全变体：verifyOfflineReleaseCompliance / verifyFull*Compliance）
./gradlew :app:check                           # **CI 门禁（已全绿）**：lint（app/lint.xml 仅抑制 3 条基线误报）+ 合规 verify 任务 + 单测

# 模拟器（无窗口后台）
$ANDROID_HOME/emulator/emulator -avd yyx_test -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2560 &

# 合规自检（人工兜底）：offline APK 权限清单（不得出现 INTERNET / ACCESS_NETWORK_STATE）
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/offline/debug/app-offline-debug.apk

# 测试书推送（SAF 扫描需 UI 授权，见下「E2E 驱动」）
adb push 某书.txt /sdcard/Download/novels/

# E2E 免 SAF 导书（模拟器实测可用）：push → run-as 拷入私有 incoming/ → VIEW 打开（**必须 -t text/plain**，否则部分场景导入静默失败）
adb push 某书.txt /sdcard/Download/x.txt
adb shell "cat /sdcard/Download/x.txt | run-as com.yyx.reader sh -c 'cat > files/incoming/x.txt'"
adb shell "am start -a android.intent.action.VIEW -t text/plain -d file:///data/data/com.yyx.reader/files/incoming/x.txt -n com.yyx.reader/.MainActivity"

# 设备内置 sqlite3 直接查库（不必先 pull）
adb shell "run-as com.yyx.reader sqlite3 databases/yyx.db 'SELECT * FROM tags; PRAGMA user_version;'"
```

## 环境

- **SDK/工具链**：`~/Library/Android/sdk`（platform 35、build-tools 35.0.0）；Gradle 8.10.2 已生成 wrapper；brew 装 Android 工具链不可行（ghcr.io ~17KB/s），直连 dl.google.com ~5MB/s、腾讯镜像 ~4MB/s。
- **AVD**：`yyx_test`（pixel_6，**AOSP android-34 无 GMS**，即 PRD E2E-2 的无 Google 设备测试环境）。已装 eSpeak NG 1.52（`com.reecedunn.espeak`），系统默认引擎已设：`adb shell settings put secure tts_default_synth com.reecedunn.espeak`。
- **模拟器限制**：`-no-audio` 下 TTS 的 AudioTrack 阻塞 → `onDone` 不回调 → 句子不自动推进；**V1.5 实测即使去掉 -no-audio，本无头模拟器的音频 HAL 也不消费数据**（stagefright 播 WAV `Frames written` 纹丝不动，播放头恒 0）——真机无此问题；LocalTtsEngine 已内置 drain 停滞守卫（5s 无推进跳句），无头环境下每句 ≈ 合成 1–2s + 5s 守卫超时后推进，可完整 E2E。
- **UI 自动化**：截图与设备坐标有缩放差，用 `uiautomator dump` 取 bounds 再 `input tap`；阅读器中央点击是菜单开关（奇偶），连续脚本点击前先 dump 确认状态。
- **进度数据库检查**：`adb shell "run-as com.yyx.reader cat databases/yyx.db" > /tmp/yyx.db`（连同 -wal）后本地 `sqlite3` 查询；设备自带 sqlite3 时可直接 `adb shell "run-as com.yyx.reader sqlite3 databases/yyx.db 'SQL'"`（免 pull，适合查 user_version/tags/book_tags）。

## 当前状态（截至 2026-11-08）

- **2026-11-08 V1.1 主体完成并 E2E 通过**：
  ① 书签（页首书签模型：阅读器菜单加/删，目录面板双页签「目录/书签」，摘录自动取当前页前 48 字）
  ② 听书增强：语速持久化（DataStore `tts_rate`，听书条 0.75x–3x 循环按钮）、定时停止（15/30/45/60 分钟/本章结束，通知条 Schedule 菜单）、高亮跟随（SpannableString + BackgroundColorSpan 注入分页器，播放中自动跨章跟随，`followPage` 不写 READ 进度避免与 LISTEN 来源互踩）
  ③ 设置页：TTS 引擎选择（自动/指定，`tts_engine` 持久化）、存储管理（数据库/EPUB 缓存大小 + 清理）、本地崩溃日志（`YYXApp` 全局 Handler 写 `files/logs/`，**最多 5 份**（2026-11-09 从 10 降为 5，防存储溢出），设置页查看/清除）
  ④ 分享接收导入：`ACTION_SEND/VIEW`（text/plain、application/epub+zip）→ 拷贝 `files/incoming/` → `DocumentFile.fromFile` 导入；通知 smallIcon 换自有 `ic_stat_book`
- **eSpeak 合成链路彻底打通**（排障一天，教训全记录）：
  1. TTS_SERVICE 由引擎的 **Service** 声明 → 必须 `queryIntentServices`（`queryIntentActivities` 永远返回空——曾误写 activities，必查）
  2. Android 11+ 需 manifest `<queries>` 声明 TTS_SERVICE intent（包可见性）
  3. AOSP 无 `tts_default_synth` 时 `TextToSpeech(context)` init 必失败 → 显式选引擎（用户指定→系统默认→首个）
  4. eSpeak 1.52 的 DownloadVoiceData 在模拟器上被 BAL 拦截、数据不解压 → 手工修复：`adb root` → 解压 APK 内 `res/t8.zip` → 推到 `/data/user_de/0/com.reecedunn.espeak/app_voices/espeak-ng-data/` → `chown u0_a142` → `restorecon`。**数据缺失时合成静默失败（utterance 无 done 回调），曾误判为 -no-audio 阻塞**
  5. 引擎 init 失败必须 `tts=null` 复位，否则重试永远卡 pending（已修）
- 验证基线（V1.1 当时）：eSpeak 自动合成推进（进度 chapter1/offset149/5.99% listen 自动增长）、书签「书签（1）」、合规零网络权限。19 单测全绿（V1.3 已 69 个，见下）。
- **2026-11-08 V1.2（F-13 标签与分组、F-12 备份与恢复、合规门禁、两个热修）**：
  ① **F-13 标签与分组**：Room DB **v1→v2**（新增 `tags`（name 唯一索引）与 `book_tags`（复合主键 + books/tags 双 CASCADE 外键 + book_id/tag_id 索引）；`Migration(1,2)` 5 条 SQL 与 Room 生成语句逐字节一致；`exportSchema` 当时为 false（V1.3 已改为 true，见下方 V1.3 ②））；新增 `core/domain/model/Tag.kt`、`core/domain/repository/TagRepository.kt`、`data/repository/TagRepositoryImpl.kt`、`di/TagModule.kt`、`feature/library/LibraryFilters.kt` 纯函数（filterShelf/commonTagIds/tagSummary）；书架 UI：顶部标签筛选 chips（单选/再点取消/长按删除）、多选后「标签」批量打标底部面板（复选 + 新建标签，初始勾选=所选书标签交集）、卡片最多 2 个标签 +「+n」、筛选/搜索空态；标签名 trim/去空/最长 20 字，重名（IGNORE）返回 null
  ② **F-12 备份与恢复**：设置页「备份与恢复」区块，SAF CreateDocument/OpenDocument + 覆盖式二次确认 + 执行中禁用；单 JSON 文件（schema=1，org.json，无新依赖），含 settings + books/chapters/reading_progress/bookmarks/tags/book_tags/scan_folders 七表；导出按 sqlite_master 判表存在（v1 库无 tags 表时跳过）；导入=**覆盖式**（校验 schema，>1 或空备份拒绝 → 单事务按子→父清空、父→子 `INSERT OR REPLACE` 且显式保留原 id，未知表/列跳过计数 → 事务外恢复设置，失败只警告）；实现见 `data/backup/`、`core/domain/{model/BackupModels.kt,repository/BackupRepository.kt}`、`di/BackupModule.kt`，`ReaderSettingsRepository` 增加 `current*/apply*` 快照与整组写回
  ③ **合规门禁落地**：`app/build.gradle.kts` 新增 `verifyOfflineDebug/ReleaseCompliance`、`verifyFullDebug/ReleaseCompliance`（group=verification）；offline 变体断言合并 manifest 无 INTERNET/ACCESS_NETWORK_STATE、含 TTS_SERVICE `<queries>`，全变体断言依赖树无 GMS/Firebase/MLKit/Play；`:app:check` 已 dependsOn offline 两个任务。命令 `./gradlew verifyOfflineDebugCompliance` 或 `./gradlew :app:check`
  ④ **验证基线**：单元测试 **44 个 / 0 失败**（基线 19 + 本迭代新增 25：BackupFormatTest 15、LibraryFiltersTest 10）；新增仪器测试 5 个文件（BackupRoundtripTest 6、TagDaoTest 5、TagUiFlowTest 1、ReaderPaginationMetricsTest 3 断言、QaTestSupport helper），`connectedOfflineDebugAndroidTest` **15/15 全绿**；模拟器 E2E（yyx_test，AOSP API34）：v1→v2 迁移真实数据保留（3 书/15 章/2 进度/1 书签）、标签全链路、备份导出→pm clear→导入全恢复（UI 层 SAF 全自动）；证据 `/tmp/yyx-qa/`（30+ 截图、日志；QA-REPORT.md / QA-REPORT-TASK8.md / QA-REPORT-TASK10.md / QA-REPORT-TASK11.md）；APK 权限仍无 INTERNET、无 ACCESS_NETWORK_STATE。**最终 APK**：`app/build/outputs/apk/offline/debug/app-offline-debug.apk`（22,005,354 B，sha256 **`77a76b59…bb7c`**；task-10 验收的 27bd9eb6… 之后为合入 Reviewer P2-1/P2-8 修复重新构建），QA task-11 增量验收通过（sha 与设备安装包一致、单测 44/0、仪器 15/15、`verifyOfflineDebugCompliance` ✅、权限达标）
  ⑤ **热修 DEF-1（P1，既有缺陷、非本迭代引入）**：`ChapterPaginator` 行距乘数 0f→1.0f，阅读器正文此前多行重叠不可读、分页塌陷；修复后正文 18 行清晰、页码 1/3→3/3，回归断言已加（行距≥字形高、80 行/1200px≥4 页）
  ⑥ **热修 DEF-2（P2）**：`ChapterTextSource` 章节加载失败占位不再直出英文 Java 异常，改为中文原因（授权失效/源文件不存在/读取失败/解析失败）+ 中文引导，原始异常仅 `Log.w("YYXChapterText")`；编译 + 44 单测通过，中文占位视觉已由 QA task-10 在模拟器确认（证据 `/tmp/yyx-qa/screens/34-def2-chinese-placeholder.png`、`logs/def2-logcat-task10.txt`：UI 无英文、原始 `SecurityException` 只在 logcat）
  ⑦ **发布前修复（Reviewer P2-1 / P2-8，tag UI）**：① `LibraryViewModel.deleteTag` 删除「当前筛选标签」时同步重算书架列表（不再停在「没有符合条件的书籍」空态）；② 批量打标面板内新建标签失败改为**面板内红色中文提示**（原 snackbar 被 ModalBottomSheet 遮住）。两处均由 QA task-11 验证（`screens/40-p2-1-delete-filtered-tag.png`、`screens/41-p2-8-inline-error.png`），最终 APK 见 ④
- **2026-11-08 V1.3（备份健壮性、迁移测试 CI 化、听书输出路由降级）**：
  ① **备份健壮性 + 恢复后状态（Reviewer P2-2/3/4/6/5 + V1.2 遗留待办「恢复后可读性校验」）**：缺表键 → 保留本机数据并 warning「备份不含 <表> 数据，已保留本机现有 <表>」；有键空数组仍按覆盖清空；导入后逐表真实 `COUNT(*)` 统计（不再虚报计划行数）；备份内重复主键告警「后者覆盖前者」；`book_tags` 预过滤无效引用（不依赖 FK 开关）；引用完整性告警（chapters/progress/bookmarks 的 book_id 悬空）；读取上限 **16MB**（分块读，超限中文拒绝）；OpenDocument mime 收紧 json/text/plain/octet-stream；恢复后立即 `SystemTtsEngine.useEngine` 生效（提示「TTS 引擎已切换」）；播放中拒绝恢复（「请先停止听书再恢复备份」）；`LibraryViewModel` 筛选标签失效自动复位；恢复后抽样 ≤50 书源/扫描目录可访问性预警。BackupFormatTest **29 例**（V1.3 收尾补导出一致性/forceSpeaker 备份）
  ② **迁移测试 CI 化**：Room **`exportSchema=true`** + `app/schemas/com.yyx.reader.core.database.YYXDatabase/2.json`（14,014B，identityHash `01b2213e…`，7 实体，book_tags 复合主键 + 双索引 + 双 CASCADE 外键）+ KSP `room.schemaLocation`；新增 `MigrationV1ToV2Test` **4 例**（自建真实 v1 库 + 与生产一致的 Room 打开路径，验证迁移/级联/幂等，**不新增 room-testing 依赖**）；`MIGRATION_1_2` 由 private 改 **internal**（androidTest 引用）；v2→v3 扩展约定写入测试 KDoc
  ③ **听书输出路由（F-08，诚实降级）**：新增 `core/audio`（`AudioOutputs` 纯函数 + `AudioOutputRepositoryImpl` callbackFlow/AudioDeviceCallback，异常返空）；`ListenSettings.forceSpeaker` 持久化（`tts_force_speaker`）；`TtsPlaybackService` 播放开始接管、暂停/停止/onDestroy **成对还原** `isSpeakerphoneOn`+`mode`（runCatching 失败只告警不崩，未改分句/进度/MediaSession）；阅读器听书面板 `AudioOutputSection`（设备摘要 + 强制外放 Switch + 限制说明 + Hilt EntryPoint）；新增普通权限 `MODIFY_AUDIO_SETTINGS`（安装即授予，不影响零网络红线）。**限制**：直读模式只能强制外放，有线/蓝牙跟随系统，精确路由需 V2 合成管线；TTS 真出声 / `-no-audio` / 真机蓝牙/USB 路由**未验证**
  ④ **验证基线**：单元测试 **69 个 / 0 失败**（ChapterParser 14 + Epub 5 + LibraryFilters 10 + BackupFormat 29 + AudioOutputs 11）；仪器测试 **19/19**（MigrationV1ToV2Test 4 + BackupRoundtripTest 6 + ReaderPaginationMetrics 3 + TagDao 5 + TagUiFlow 1）；`verifyOfflineDebugCompliance` ✅；APK 无 INTERNET/ACCESS_NETWORK_STATE（新增普通权限 MODIFY_AUDIO_SETTINGS）；最终 APK sha256 **`ee0d4684…9c4`**（21,984,819 B）；证据 `/tmp/yyx-qa-v13/`
  ⑤ **V1.3 收尾修复（Reviewer P2，Lead）**：导出侧与导入共用 **16MB** 上限（超限中文拒绝，避免「导出成功但无法恢复」）；`ListenSettings.forceSpeaker` 纳入备份设置（`ttsForceSpeaker`，显式标记合并）；`deleteBook` 事务内显式清理 chapters/reading_progress/bookmarks（修既有孤儿行债，噪声引用完整性告警消失）；输出设备摘要过滤不可播放类型（TELEPHONE/BUS/IP/TV_TUNER/FM/MIC 等，「通话音频」不再出现）；强制外放接管避开 `MODE_IN_CALL/IN_COMMUNICATION`。单测 69/0、仪器 19/19、合规 ✅、最终 APK sha256 **`ee0d4684…9c4`**（21,984,819 B）
- **2026-11-08 V1.4（备份流式化 + DB 维护 + CI 门禁）**：
  ① **备份流式化（F-12 上限解除）**：新增 `data/backup/BackupJsonWriter`（纯 JVM，逐表逐行写 `OutputStream`，每行校验上限）与 `BackupStreamReader`（`android.util.JsonReader` 两遍：scan 元信息/表键/行数 → 逐表逐行回填；`SAF` 流不可 seek 故各开一次）；上限 **16MB → 64MB**；**删除 org.json 整串编解码路径**（`BackupJsonCodec` 已移除），导出不再构造完整 String/ByteArray，导入行级内存有界；全部 V1.3 语义保留（缺非核心表键保留本机、有键空数组清空、真实 `COUNT(*)`、重复主键/book_tags 预过滤/引用完整性/可访问性告警、播放中拒绝、设置恢复、mime 收紧）
  ② **缺核心表键拒绝**：`BackupFormat.validateCoreTables`（books/chapters）+ `validateTableOrder`（book_tags 须晚于 books/tags）在**清空任何表之前**校验；损坏/截断备份直接拒绝（中文「缺少核心表…本机数据未改动」）
  ③ **DB 维护（历史孤儿清理）**：新增 `core/database/OrphanCleaner`（单事务批量 DELETE）+ `YYXApp` 启动时 IO 协程执行（失败只记日志）；清理 chapters/reading_progress/bookmarks 悬空 `book_id` 与 book_tags 任一端失效关联；实测首次启动清理 6 行历史孤儿、再次启动 0 行（幂等）
  ④ **CI 门禁**：新增 `app/lint.xml` 仅抑制 3 条逐条核对过的基线误报（AppLinkUrlError / 两处 ProduceStateDoesNotAssignValue，未全局关闭）；`./gradlew :app:check` 由「lint 3 error 必失败」变为 **BUILD SUCCESSFUL（lint 0 error）**
  ⑤ **测试补齐**：`BackupJsonWriterTest` 4 例（结构/转义/超限/流式语义）、`BackupFormatTest` 补核心键与表顺序、`BackupRoundtripTest` 新增 2 例（**>16MB 大备份 roundtrip**、缺核心表键拒绝且数据不动）、`DatabaseMaintenanceTest` 4 例（孤儿清理/幂等/无书/仓库级删书）、`TagUiFlowTest` 等待 10s→20s
  ⑥ **验证基线**：单元 **75/0**（ChapterParser 14 + Epub 5 + LibraryFilters 10 + BackupFormat 29 + BackupJsonWriter 4 + AudioOutputs 11 + BackupFormat 新增 2）；仪器 **25/25**（备份 8 + 迁移 4 + DB 维护 4 + 分页 3 + TagDao 5 + TagUiFlow 1）；`./gradlew :app:check` ✅、`verifyOfflineDebugCompliance` ✅、APK 无 INTERNET/ACCESS_NETWORK_STATE；最终 APK sha256 **`6cfbb5b5…2664`**（22,042,920 B）
  ⑦ **模拟器 E2E 实测**：流式导出 1140B JSON（7 表 + settings + ttsForceSpeaker）→ `pm clear` → 流式导入完整恢复（1 书/4 章）；导入缺失 `chapters` 键的备份 → 中文拒绝且本机数据未改动；导入 66MB 文件 → 「备份文件过大（超过 64MB）」；重启后孤儿清理幂等（0 行）；阅读器/听书面板回归正常，0 FATAL
- **2026-11-08 V1.5（内置 sherpa-onnx 离线 TTS 兜底，待办 #6 完成）**：
  ① **复测 V1.4 基线先行**：`:app:check` 全绿、单测强制重跑 75/0、仪器 25/25、APK sha256 与基线 `6cfbb5b5…2664` 逐字节一致（代码自 V1.4 后未变动）。
  ② **引擎选型**：sherpa-onnx **v2.0.1** 官方 AAR（`sherpa-onnx-2.0.1.aar`，49,441,341B，Apache-2.0，GitHub release 直下 ~5min；无 Maven Central 官方物、JitPack 不可控，故 vendor 进 `app/libs/`）；AAR manifest **零权限**（人工核验，不触红线）；Kotlin API `com.k2fsa.sherpa.onnx.OfflineTts(+Config)`、`generateWithCallback` 流式回调。**模型不内置**：主推 **vits-melo-tts-zh_en 的 `model.int8.onnx`（51MB）**（fp32 162MB 备选；hf-mirror.com 可直连，HuggingFace 直连不通），全套 int8+lexicon+dict+4 个 fst ≈ 71MB；Matcha/Kokoro 布局亦可识别。
  ③ **实现**：`LocalModelLocator`（纯 JVM 探测/校验，10 单测）+ `LocalTtsEngine`（合成线程串行化：模型加载/generate/AudioTrack 写入；`WRITE_NON_BLOCKING` 自旋写防悬挂；stop=置标志+pause/flush 即时静音；drain 停滞守卫 5s）+ `TtsEngineRouter`（门面，listener 双挂、`useEngine` 路由、`isLocalActive` 供服务分流错误文案与输出路由策略）+ `di/TtsModule.kt`；服务/设置 VM 注入点改 Router（2 处）。`tts_engine` 复用保留值 `local:sherpa-onnx`，**DataStore/备份格式零改动**（模型目录设备本地不备份）。设置页新增「离线语音模型」区块（SAF `OpenDocumentTree` 导入：SAF 列表先校验→tmp 拷贝→复检→原子替换→引擎失效待重载；移除；已导入标签展示）与引擎对话框「内置离线引擎」项（未导入模型时禁用+引导）。服务 init 失败文案按引擎分流（无模型→「请到设置导入」）。
  ④ **E2E 排障三课（全部修复并记录）**：
    1. **init 首次回调丢失**：`LocalTtsEngine.init` 未先存 `pendingInit` → 服务永远等不到 ready（进度 0 卡死）；修复为统一走 pendingInit 通道
    2. **D8 desugar × JNI 回调 = SIGABRT**：lambda 传给 `generateWithCallback` 被 D8 改写为 `$$ExternalSyntheticLambda2`，JNI 按 `invoke([F)Ljava/lang/Integer;` 查找失败 → `NoSuchMethodError` → native abort；tombstone 定位；**修复=对象表达式**（D8 不改写匿名类）。此坑编译/单测均不可见，务必牢记
    3. **无头模拟器音频 HAL 不消费**（详见「环境」）：drain 停滞守卫兜底，进度可推进
  ⑤ **E2E 实测**（yyx_test 重启去 `-no-audio` 后音频仍死、靠守卫推进）：SAF 导入 70.6MB≈10s；int8 模型加载 **1.5s**；每句合成产出真实音频（~41k frames @44.1kHz，即 ~0.9s 音频/句）；进度自动推进 `offset 0→41→85`（source=listen，percent 0→3.0%）；通知权限弹窗（新装首听）不阻塞服务；导入→选引擎→听书全链路二次复跑通过。证据：`/tmp/yyx-e2e/`（driver.py、v15-01-model-imported.png、v15-02-listen-advancing.png）
  ⑥ **验证基线**：单元 **85/0**（+10 LocalModelLocatorTest：melo int8/fp32、matcha、kokoro、各类缺文件）；仪器 **25/25**；`./gradlew :app:check` ✅；`verifyOfflineDebug/ReleaseCompliance` ✅（manifest 仍无 INTERNET/ACCESS_NETWORK_STATE）；**release R8** 构建通过且 mapping 保留 sherpa 1412 类（keep 规则生效）；APK：debug **75,518,211B** sha256 `53d8d467…6915`（+53MB=双 ABI native）、release-unsigned 55,424,595B
  ⑦ **诚实声明（未验证）**：真机出声/路由/性能（RTF）未测；强制外放对本地引擎= `setPreferredDevice`（模拟器无法验证实际路由效果）；语速映射（speed 0.75–3x）听感未验证；语速/音调与系统引擎行为差异（pitch 忽略）未做按书设置
  ⑧ **V1.5 收尾：V2 精确音频路由落地（本地引擎侧，待办 #7）**：`AudioOutput` 增加 `id/address` 字段（默认值不破坏既有纯函数/测试）；`deviceRouteKey`/`matchesRouteKey` 纯函数（`t<type>[:<address>]` 选择器，空=跟随系统）；`OutputDeviceRepository.observeDevices()` 逐设备流；`ListenSettings.preferredOutput`（DataStore `tts_preferred_output`）+ 备份可选字段 `ttsPreferredOutput(+Specified)`（模式同 ttsForceSpeaker，地址跨设备不可迁移、恢复后匹配不到自然回退）；`LocalTtsEngine.resolveRoute` 每句建轨实时解析（preferredOutput > forceSpeaker > 跟随系统）；听书面板「输出到」单选（本地引擎激活时替换强制外放 Switch，已选设备不在线有中文提示）。**踩坑**：`AudioDeviceInfo.getAddress()` 需 API 28（minSdk 26）→ lint NewApi 拦下 3 处，`Build.VERSION_CODES.P` 守卫降级；另测试曾硬编码 `TYPE_BUILTIN_SPEAKER=4`（实为 2），改用常量插值。**验证**：单测 **93/0**（+8：路由键 5 + 备份合并 3）；仪器 **25/25**（备份 roundtrip 含新字段）；`:app:check` ✅（lint 全变体 0 error）；E2E：听书面板选择器渲染 ✓、选扬声器后每句 logcat「输出路由已应用：type=2」✓、进度持续推进 ✓。证据 `/tmp/yyx-e2e/v15-03-route-picker.png`
  ⑨ **真机验证（联想 TB321FU，Android 16）与「默认系统 TTS」决策（2026-11-09）**：
    - ✅ 已验证：模型 SAF 导入（72.2MB≈秒级）、引擎切换、本地引擎真机出声（用户确认）、句级/跨章推进、暂停/继续、息屏播放、进度 source=listen 回写、输出路由 `setPreferredDevice` 生效（logcat type=2）
    - ✅ **系统 TTS（天禧AI TTS）离线可用且流畅**（内部自带预合成缓存 `saveTTSData`，句间停顿小），用户听感「好多了」
    - ❌ **本地引擎性能不可接受**：真机实测 **RTF≈1.2–1.3**（合成慢于实时；骁龙 8 Gen 3 级平板、melo int8、2→4 线程后仍慢），句间停顿明显；`dumpsys cpuinfo` 显示仅吃 ~1.1 核（ONNX 线程未吃满，疑 int8 量化在 ARM 上反优化）。**已做缓解**：预取管线（播放当前句时后台合成下一句，`TtsEngine.prepare` 默认空实现+Router 转发）、整句缓冲播放（预灌缓冲再起播，消除 HAL underrun/首轨停滞）、线程 2→4；**但 RTF>1 时预取无法消除停顿**
    - 📌 **产品决策（用户拍板）**：**默认使用系统离线 TTS**（代码默认已是「自动」）；内置引擎降级为**备选**——设置对话框排序改为 自动 → 系统引擎列表 → 内置离线引擎（备选·合成较慢），「离线语音模型」区块加性能提示；无系统引擎设备仍可用本地引擎兜底
    - 🔧 **技术债**：本地引擎性能优化待做（评估 fp32 是否反超 int8、ORT 线程/亲和性、换更小模型）；e-ink 等低性能设备上基本不可用
    - ⚠️ 真机未见 USB 音频设备（`host_manager.num_connects=0`，port1 host 模式已连接但 USB 枚举失败——疑充电专用线/设备兼容）；蓝牙/有线路由仍未实测
  ⑩ **多语言（中英）+ 隐私检查 + 打包（2026-11-09）**：
    - **国际化框架**：默认英文（values/）+ 中文（values-zh/，自动覆盖 zh-CN/zh-TW/zh-HK 回退）；系统中文→中文界面，其他语言→英文；后续加语言只需新增 values-xx 目录。全量改造 **380 处用户可见字符串**：4 个界面（Settings/Library/Reader/Detail）+ 主题（`ReaderTheme.labelRes`）+ 输出设备（`AudioOutput.typeLabelRes`，`outputSummary` 改为标签解析器参数）+ 全部 VM/服务/通知 + 数据层（BookImporter/LibraryScanner/TxtContentStore/ChapterTextSource）+ 备份层（`BackupException(BackupError)` 类型化，`BackupRepositoryImpl` 统一映射）。Logcat/正则/入库标题按设计保留
    - **单测适配**：LocalModelLocatorTest/BackupFormatTest/AudioOutputsTest/BackupJsonWriterTest 断言改为枚举/资源 ID（**93/0**）
    - **仪器测试适配**：TagUiFlowTest 的 UI 文本匹配改为 `context.getString(R.string.*)`（随设备语言）；BackupRoundtripTest 断言改为资源化，**emulator(API34, en-US) 25/25 全绿**
    - **踩坑**：① `kotlin.error(exception)` 会包成 `IllegalStateException(toString)` 而非原样抛出 → 备份错误类型识别失效，改 `throw it`；② 缺 `R` import 批量补；③ lint 双错误（跨语言 format 类型不一致 `%d` vs `%.1f`、缺翻译）已修
    - **验证**：模拟器切 en-US → 全英文界面（「Bookshelf is empty / Settings / Scan & import」）；切 zh-CN → 全中文（「书架空空如也 / 设置 / 扫描导入」）；平板（中文系统）即中文界面。截图 `/tmp/yyx-e2e/i18n-en-shelf.png` / `i18n-zh-shelf.png`
    - **隐私检查**：新增 `docs/PRIVACY_CHECK.md` —— release APK 仅 6 项功能性权限（**无 INTERNET/ACCESS_NETWORK_STATE**）、源码零网络 API（仅 EPUB 用 URLDecoder）、零追踪/广告/统计 SDK、导出组件仅 MainActivity（分享导入过滤器）+ androidx ProfileInstallReceiver（DUMP 权限保护）
    - **打包**：debug APK **76,264,347B** sha256 `e090b2fb…b60d`（可安装）；release-unsigned **55,567,427B** sha256 `a3314413…45cb`（R8，正式分发需自备签名）
  ⑪ **混合扫描（方案 C，2026-11-10）**：① 发现「系统禁止 SAF 整目录授权 Download/Documents 根目录」（DocumentsUI 显示「无法使用此文件夹」），最初设计的「一键授权常用目录」不可行，已移除；② 落地「所有文件访问（MANAGE_EXTERNAL_STORAGE，API 30+ 可选）」：ScanSheet 状态行 + 去开启 deep link，授权后 `LibraryScanner` 额外 File 直扫 Download/Documents（与 SAF 文件夹共存、导入幂等），且「开始扫描」按钮在无 SAF 文件夹 + 已授权时可用（原实现 folders.isEmpty() 禁用——E2E 抓到并修复）；③ 模拟器 E2E：appops/系统设置授权 → 扫描 Download → 「新增 9 · 失败 0」、1047 章入库（紫川/武道长生等）✓；④ 单测 93/0、仪器 25/25、:app:check 全绿；提交 39c2bfe

## 历史状态（2026-11-07）

- **V1.0 MVP 全链路打通并 E2E 通过**：扫描导入（双编码）→ 书架 → 详情（编辑/编码/重新解析/删除）→ 阅读器（分页/主题/设置持久化 DataStore）→ 听书（引擎发现/句级控制/前台服务/MediaSession）→ 听读进度双向同步实测通过。
- E2E 修复 4 bug：导航参数 `NavType.LongType`；章节号单调递增过滤（防「第1章第0段：…」误判，卷号重置）；`syncPage` 同位置提前返回致百分比恒 0；TTS 链三断点（queries/默认引擎/init 门控）。

## 已知问题 / 技术债

1. 按书覆盖阅读/听书设置未做（全局设置已持久化：排版 + 语速 + 引擎）
2. **听书输出路由双轨（V1.3 降级 + V1.5 精确）**：系统引擎=直读模式只能「强制外放」（isSpeakerphoneOn 降级，部分 ROM 可能忽略，UI 已提示）；**本地引擎=AudioTrack `setPreferredDevice` 全设备精确路由**（V1.5 收尾已实现逐设备选择 UI + 持久化/备份）；真机有线/蓝牙/USB 实际路由效果**未验证**
3. 书架封面逐个解码无内存缓存（万本场景需 Coil 或自建 LRU）
4. 定时停止无倒计时展示（仅「已设定时」标签）；EPUB 的 eSpeak 朗读依赖首次打开缓存文本
5. 大 TXT（>128MB）拒收（TxtContentStore 上限）；`epub_text` 缓存可在设置页清理，无自动 LRU
6. **备份只含数据库索引与设置，不含书源文件与 SAF 持久授权**：`pm clear` / 换机后需重新授权扫描目录、重新导入源文件才能打开正文（V1.3 已加恢复后抽样 ≤50 预警与中文提示，但仍需用户手动恢复；打开原书会落到「章节加载失败」中文占位）；**离线语音模型同样不参与备份**（设备本地文件，换机重新导入，`tts_engine=local:sherpa-onnx` 恢复到无模型设备会得到中文引导）
7. **lint 基线误报已抑制（V1.4）**：`app/lint.xml` 只忽略 3 条逐条核对过的既有误报（AppLinkUrlError / 两处 ProduceStateDoesNotAssignValue），未全局关闭；`./gradlew :app:check` 现已全绿（lint 0 error）
8. **无 CI 服务**：`./gradlew :app:check` 已是可用门禁（lint + 合规 + 单测），迁移/仪器测试与 E2E 仍需本地跑
9. **TTS 真出声未覆盖**：系统引擎在 `-no-audio`/无头模拟器上不出声（V1.5 实测去掉 -no-audio 也无 HAL 消费）；**本地引擎已具备独立合成与播放管线并在模拟器上验证到「合成真实音频 + 句级推进 + 进度回写」**，但真机出声、本地引擎 RTF/发热、拔耳机暂停、蓝牙媒体键、Doze 存活均未验证
10. **强制外放的通话边界（V1.3.x）**：接管后若在通话中暂停/停止，restore 会跳过（避免改通话路由）并把接管状态保留到下一次非通话还原；极端场景（通话中进程被杀/长期无后续播放）可能残留 speakerphone 状态，V1.4 用 AudioDeviceCallback/通话状态监听补偿。
11. **sherpa-onnx vendor AAR 体积**：APK 22MB→75MB（debug，双 ABI native）；如需瘦身可评估只留 arm64-v8a（再 -22MB）或 ABI Split 分发；x86/x86_64 模拟器镜像无法用本地引擎（仍可用系统 TTS）
12. **仪器测试在 Android 16 平板的限制**：`TagUiFlowTest`（Compose UI 流程）在 TB321FU/Android 16 上因 androidx.test `ActivityScenario` 需要启动 `InstrumentationActivityInvoker$EmptyActivity` 失败（ActivityNotFound/No compose hierarchies，测试基础设施不兼容，与本 App 无关）；同套件在 API 34 emulator **25/25 全绿**，平板其余 24 例（备份/迁移/DB 维护）通过。升级 androidx.test 或换 API 34 设备可解
13. **本地引擎性能不达标（V1.5 真机结论）**：RTF≈1.2–1.3（骁龙 8 Gen 3 级平板），句间停顿明显；已定位为 int8/线程问题待优化。**默认策略=系统离线 TTS**，内置引擎仅作无系统引擎设备的备选（UI 已标注「备选·合成较慢」）

## 剩余待办（V1.4 收尾 + V2）

1. ~~书签~~ ✅ ~~听书增强（语速/定时/高亮跟随）~~ ✅ ~~设置页引擎选择/存储/崩溃日志~~ ✅ ~~分享接收导入~~ ✅（2026-11-08 V1.1）
2. ~~备份恢复（SAF 文件导出/导入，纯本地）~~ ✅（2026-11-08 V1.2，覆盖式导入；限制见「已知问题 6」）
3. ~~标签分组（Room migration v1→v2 加 tags/book_tags）~~ ✅（2026-11-08 V1.2）
4. ~~迁移可测试化~~ ✅（2026-11-08 V1.3：exportSchema=true + `app/schemas/.../2.json` + `MigrationV1ToV2Test` 4 例；未引入 room-testing，自建 v1 库替代）
5. ~~备份恢复后校验书源可读性并给中文引导~~ ✅（2026-11-08 V1.3：恢复后抽样 ≤50 书源/扫描目录预警；仍需用户手动重新授权/导入源文件）
6. ~~内置 sherpa-onnx 离线引擎兜底（模型 SAF 导入，APK 不内置）~~ ✅（2026-11-08 V1.5：int8 melo 51MB 推荐，LocalTtsEngine+Router+设置页导入；E2E 推进+进度回写实测；真机出声/性能待验）
7. ~~V2 精确音频路由（本地引擎侧）~~ ✅（2026-11-08 V1.5 收尾：`setPreferredDevice` + 「输出到」设备单选 UI + 选择器持久化/备份；系统引擎直读模式保留「强制外放」降级；真机实际路由效果待验）
8. 真机验证清单：**本地引擎**实际出声/自动推进/RTF（中低端机 int8 预估 0.3–1×）/语速听感/**「输出到」各设备实际路由（有线/蓝牙/USB）与拔出回退**；eSpeak 实际出声/自动推进、拔耳机暂停、蓝牙媒体键、Doze 存活；蓝牙/USB 路由与强制外放兼容性
9. ~~备份流式化/上限提升~~ ✅（2026-11-08 V1.4：JSON 流式读写 + 64MB + 缺核心键拒绝）
10. ~~历史孤儿行清理 / lint 门禁~~ ✅（2026-11-08 V1.4：启动时 OrphanCleaner + `app/lint.xml` 使 `:app:check` 全绿）
