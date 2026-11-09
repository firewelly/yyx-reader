# YYX 阅读器（Android）整体架构设计

| 项目 | 内容 |
|------|------|
| 文档版本 | V1.0（草案） |
| 日期 | 2026-11-07 |
| 状态 | 待评审 |
| 配套文档 | [PRD_ANDROID.md](./PRD_ANDROID.md) |

---

## 1. 技术选型与决策记录

### D1：原生 Kotlin 而非 Flutter（已决策）

| 维度 | 原生 Kotlin + Compose | Flutter | 结论 |
|------|----------------------|---------|------|
| 系统 TTS / MediaSession / 音频路由 | 直接调用，能力完整 | 需经平台通道，音频路由/强制外放等能力受插件质量制约 | 原生胜 |
| 无 GMS 设备适配 | 无额外负担 | Flutter 引擎本身无 GMS 依赖，可行 | 平 |
| APK 体积 | ~8–15MB | ~25MB+ | 原生胜 |
| 前台服务/后台保活细节控制 | 完全可控 | 需写原生代码兜底 | 原生胜 |
| 团队既有经验 | novelmgt 为 Flutter 项目，但桌面端经验对 Android 系统集成帮助有限 | — | 中性 |

**决策**：本项目核心壁垒在「TTS 前台播放、音频路由、听读进度同步」的系统集成深度，采用 **Kotlin + Jetpack Compose** 原生方案。

### D2："无 Google"的技术边界（已决策，对应 PRD P-2/P-5）

**禁用清单（CI 强制检查，见 §10）**：
- GMS / Play Services 全部 SDK（`com.google.android.gms.*`、`com.google.android.play:*`）
- Firebase 全家桶（Analytics / Crashlytics / Messaging…）
- ML Kit、Google Fonts 在线字体、`googleapis` 任何 REST 端点
- 任何需要 `INTERNET` 权限的功能（默认 offline 构建变体）

**允许清单**（AOSP 开源、本地运行、不发起网络请求）：
- Android 框架 API（含 `android.speech.tts`、`MediaSession`、`AudioManager`）
- AndroidX/Jetpack：Compose、Room、DataStore、Media3、Lifecycle、Navigation、WorkManager、Hilt
- 第三方开源库（许可证合规、纯本地逻辑）：Jsoup、juniversalchardet、Okio、sherpa-onnx 等

### D3：技术栈一览

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 | Kotlin 2.x，100% | — |
| UI | Jetpack Compose + Material3 | 阅读器翻页画布用 `AndroidView` 包 `StaticLayout` |
| 架构 | MVVM + Repository + UseCase | 单向数据流（StateFlow） |
| DI | Hilt | 仅编译期代码生成，无运行时网络/GMS |
| 数据库 | Room（SQLite） | WAL 模式 |
| 设置存储 | DataStore Preferences | — |
| 异步 | Coroutines + Flow | — |
| EPUB 解析 | `java.util.zip` + Jsoup | Jsoup 仅用于 HTML→纯文本抽取（MIT） |
| 编码探测 | juniversalchardet（Mozilla universalchardet 的 Java 移植，MPL-1.1）+ BOM/回退链 | 移植 novelmgt 的 UTF-8→GB18030→Big5 策略 |
| TTS 框架 | `android.speech.tts`（AOSP） | 引擎无关，不绑定 Google TTS |
| 内置离线引擎（V1.1） | sherpa-onnx（k2-fsa，Apache-2.0）+ VITS 中文模型 | 模型经 SAF 导入，APK 不内置 |
| 音频播放/路由 | Media3 ExoPlayer + `AudioManager` | Media3 是 AOSP 开源库，**不需要 Google 服务** |
| 崩溃日志 | 自建本地 `UncaughtExceptionHandler` 写文件 | 不上报；不引入 ACRA（保持零网络） |
| 分发 | GitHub Release / F-Droid | 不依赖 Google Play |

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层 (Compose)                        │
│  书架   扫描导入   书籍详情   阅读器   听书面板   书签   设置      │
│  (feature/library, scan, bookdetail, reader, listen, ...)   │
├─────────────────────────────────────────────────────────────┤
│                     ViewModel 层 (MVVM)                       │
│      StateFlow<UiState>  ←  事件  →  UseCase 调用             │
├─────────────────────────────────────────────────────────────┤
│                      Domain / UseCase 层                      │
│  ScanLibrary / ImportBook / OpenBook / UpdateProgress /       │
│  StartListening / SeekTo / SwitchOutputDevice / ManageTags    │
├─────────────────────────────────────────────────────────────┤
│                      Repository 层                            │
│  BookRepository  ChapterRepository  ProgressRepository        │
│  SettingsRepository  BookmarkRepository  OutputDeviceRepo     │
├──────────────┬──────────────┬──────────────┬────────────────┤
│  数据源：      │              │              │                 │
│  Room DB     │  SAF 文件层   │  TTS 子系统   │  DataStore      │
│  (books/     │  (txt/epub   │  (引擎抽象/    │  (阅读/听书      │
│  chapters/   │  字节读取、   │  分句/合成/    │  默认设置)       │
│  progress…)  │  缓存目录)   │  前台服务)    │                 │
└──────────────┴──────────────┴──────────────┴────────────────┘
        全程无网络；offline 变体不声明 INTERNET 权限
```

**数据流**：UI 发事件 → ViewModel → UseCase → Repository → 数据源；数据以 Flow 回流。听书播放态例外：`TtsPlaybackService`（前台服务）为播放事实源，ViewModel 通过 `MediaController` 绑定服务读取播放态。

---

## 3. 模块划分与包结构

V1 采用**单 App 模块 + 包内严格分层**（避免过早模块化成本），包结构即模块边界，依赖方向强制单向（`feature → data → core`）：

```
com.yyx.reader
├── App.kt                        # Hilt 入口；自建崩溃日志初始化
├── MainActivity.kt               # 单 Activity；接收分享/打开方式 Intent
├── navigation/                   # Compose Navigation 路由
├── di/                           # Hilt 模块
├── core/
│   ├── common/                   # Result、Dispatchers、文本工具
│   ├── database/                 # AppDatabase、entities、DAOs、migrations
│   ├── datastore/                # SettingsRepository（DataStore）
│   ├── domain/                   # 纯 Kotlin 模型与仓库接口
│   │   ├── model/                # Book, Chapter, BookPosition, Sentence, Tag, BackupModels...
│   │   └── repository/           # 接口定义（含 TagRepository / BackupRepository）
│   ├── ebook/                    # ★ 电子书解析子系统
│   │   ├── txt/                  # EncodingDetector, ChapterParser, TxtChapterIndex
│   │   └── epub/                 # EpubParser(OPF/spine), EpubChapterLoader(Jsoup)
│   ├── tts/                      # ★ TTS 抽象
│   │   ├── TtsEngine.kt          # 引擎接口
│   │   ├── SystemTtsEngine.kt    # 系统 TTS 框架实现
│   │   ├── OnnxTtsEngine.kt      # sherpa-onnx 内置引擎（V1.1）
│   │   ├── SentenceSplitter.kt   # 分句器
│   │   └── ChapterTextSource.kt  # 章节纯文本供给
│   └── audio/                    # OutputDeviceRepository, AudioFocusController
├── data/                         # 仓库实现（Room + 文件 + DataStore）
├── service/
│   └── tts/                      # ★ TtsPlaybackService（前台服务）
│       ├── TtsPlaybackService.kt # MediaSessionService
│       ├── PlaybackController.kt # 播放状态机
│       ├── SynthesisCache.kt     # 句级音频缓存(LRU)
│       └── ProgressWriter.kt     # 听书进度回写
└── feature/
    ├── library/  scan/  bookdetail/  reader/  listen/  bookmarks/  settings/
```

V2 若需拆模块，按 `core/*` 与 `feature/*` 边界平移即可。

---

## 4. 数据层设计

### 4.1 Room 数据库 Schema（v2，2026-11-08 F-13 起）

当前 Room `version = 2`：v1 为 books / chapters / reading_progress / bookmarks / scan_folders；v2（F-13 标签与分组）新增 `tags` 与 `book_tags`。下列 DDL 与 `YYXDatabase_Impl` 生成的建表/索引语句等价（时间为 epoch 毫秒 `INTEGER`）：

```sql
CREATE TABLE books (
  id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  title         TEXT NOT NULL,
  author        TEXT,
  summary       TEXT,
  format        TEXT NOT NULL,            -- 'txt' | 'epub'
  file_uri      TEXT NOT NULL,            -- SAF tree 下文档 URI 或 file://（唯一索引）
  charset       TEXT,                     -- txt 探测结果: 'UTF-8'/'GB18030'/'Big5'
  cover_path    TEXT,                     -- 私有目录缓存的封面
  chapter_count INTEGER NOT NULL,
  word_count    INTEGER NOT NULL,
  file_mtime    INTEGER,                  -- 增量扫描用
  file_size     INTEGER,
  last_read_at  INTEGER,
  added_at      INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_books_file_uri ON books(file_uri);

CREATE TABLE chapters (
  id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  book_id      INTEGER NOT NULL,
  idx          INTEGER NOT NULL,          -- 0-based 章节序号
  title        TEXT NOT NULL,
  start_offset INTEGER NOT NULL,          -- TXT: 字节偏移；EPUB: -1
  end_offset   INTEGER NOT NULL,
  char_count   INTEGER NOT NULL,          -- 本章字符数（进度百分比用）
  epub_href    TEXT                       -- EPUB: spine item href
);
CREATE UNIQUE INDEX index_chapters_book_id_idx ON chapters(book_id, idx);

-- ★ 统一进度：每书一行，阅读与听书共同读写
CREATE TABLE reading_progress (
  book_id      INTEGER NOT NULL PRIMARY KEY,
  chapter_idx  INTEGER NOT NULL,
  char_offset  INTEGER NOT NULL,          -- 章内字符偏移（不是滚动像素！）
  percent      REAL NOT NULL,
  source       TEXT NOT NULL,             -- 'read' | 'listen'（仅展示用）
  updated_at   INTEGER NOT NULL
);

CREATE TABLE bookmarks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  book_id     INTEGER NOT NULL,
  chapter_idx INTEGER NOT NULL,
  char_offset INTEGER NOT NULL,
  excerpt     TEXT,                       -- 自动摘录当前页前 48 字
  created_at  INTEGER NOT NULL
);
CREATE INDEX index_bookmarks_book_id ON bookmarks(book_id);

-- v2（F-13）：标签与书籍-标签关联
CREATE TABLE tags (
  id   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name TEXT NOT NULL
);
CREATE UNIQUE INDEX index_tags_name ON tags(name);

CREATE TABLE book_tags (
  book_id INTEGER NOT NULL,
  tag_id  INTEGER NOT NULL,
  PRIMARY KEY (book_id, tag_id),
  FOREIGN KEY (book_id) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE,
  FOREIGN KEY (tag_id)  REFERENCES tags(id)  ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX index_book_tags_book_id ON book_tags(book_id);
CREATE INDEX index_book_tags_tag_id  ON book_tags(tag_id);

CREATE TABLE scan_folders (
  id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  tree_uri     TEXT NOT NULL,             -- SAF tree URI（持久化授权，唯一索引）
  display_name TEXT NOT NULL,
  added_at     INTEGER NOT NULL,
  last_scan_at INTEGER
);
CREATE UNIQUE INDEX index_scan_folders_tree_uri ON scan_folders(tree_uri);
```

> **外键现状（既有技术债）**：实现中只有 `book_tags` 声明了 books/tags 双 `CASCADE` 外键；`chapters` / `reading_progress` / `bookmarks` 无数据库外键（删书需应用层显式清子表，当前 `bookDao.delete` 只删 books → 会留孤儿行）；V1.3 起 `BookRepositoryImpl.deleteBook` 在事务内显式清理 chapters/reading_progress/bookmarks（孤儿行已修）；**V1.4 新增 `core/database/OrphanCleaner`**：`YYXApp` 启动时在 IO 协程内单事务批量删除历史孤儿（含 book_tags 任一端失效），实测首次清理 6 行后幂等归零；备份导入后的引用完整性告警保留为防御（只提示、不自动删数据）；DB 级外键仍未补，留待后续。上表按实际实现记录，未画设计稿中的外键。

> 对比 novelmgt 的 `scrollPosition`（滚动像素）设计，本架构将进度改为 **char_offset（字符偏移）**：像素随字号/屏尺寸变化，无法被听书复用；字符偏移与排版无关，是听读同步的基石（见 §5.4）。

> **迁移与一致性（v1→v2 + 导出 schema，2026-11-08）**：`YYXDatabase.MIGRATION_1_2`（`internal`，供 androidTest 引用）共 5 条 SQL（建 `tags` + `index_tags_name`、建 `book_tags` + `index_book_tags_book_id` + `index_book_tags_tag_id`），**必须与 Room 为实体生成的 `createAllTables` / `createAllIndices` 语句逐字节一致**（含外键 `ON UPDATE NO ACTION ON DELETE CASCADE` 的空格位置），否则打开旧库抛 “Migration didn't properly handle”。V1.3 起 **`exportSchema = true`**：KSP 参数 `room.schemaLocation` 将 schema 导出到 `app/schemas/com.yyx.reader.core.database.YYXDatabase/2.json`（14,014 B，identityHash `01b2213e…`，7 实体），schema 文件须随 DB 变更入库；`app/src/androidTest/.../migration/MigrationV1ToV2Test` 4 个用例（自建真实 v1 库 + 与生产一致的 Room 打开路径，验证迁移/级联/幂等，未引入 room-testing 依赖）已进仪器测试。后续升 v3 时须同步提交新 schema json 并新增迁移用例（测试 KDoc 已写扩展约定）。

### 4.2 文件存储布局（全部在应用私有目录，无需权限）

```
/data/data/com.yyx.reader/
├── databases/yyx.db            # Room
├── files/
│   ├── covers/{bookId}.jpg     # EPUB 提取/用户替换的封面
│   ├── epub_text/{bookId}/{idx}.txt   # EPUB 章节纯文本缓存（首次打开时解析落盘）
│   ├── incoming/               # 分享/VIEW 接收的待导入文件（导入后可清理）
│   └── logs/crash_*.log        # 本地崩溃日志（不上传）
└── cache/
    └── tts/{bookId}/{chIdx}/{sIdx}.wav   # 句级合成音频，LRU ≤200MB
```

> **备份数据不落私有目录**：F-12 的导出直接经 SAF `ContentResolver.openOutputStream(uri)` 写用户选定文件、导入经 `openInputStream(uri)` 读，应用内不保留备份副本（因此没有 `files/backup/` 临时目录）。
> **标签数据**：`tags` / `book_tags` 是 Room 表（`databases/yyx.db`），无独立文件；书架筛选 chips 与卡片标签摘要由 `TagRepository.observeTagMap()` 的内存映射驱动，不额外落盘。

### 4.3 设置（DataStore）

阅读默认值（字号/行距/主题/翻页模式/亮度/音量键/常亮）、听书默认值（engineId/语速/音调/焦点行为）、按书覆盖表（以 bookId 为 key 的 JSON 条目）。

---

## 5. 核心子系统设计

### 5.1 扫描与导入（`core/ebook` + `data/BookRepository`）

```
添加文件夹(SAF TREE) → 持久化授权(takePersistableUriPermission)
   → 递归遍历 DocumentFile 树（批量契约查询，避免逐文件 IPC）
   → 过滤 .txt/.epub
   → 按 file_uri 查库：
       不存在          → 解析入库（新增）
       mtime/size 变化 → 重建章节索引，按章节序号对齐迁移进度
       无变化          → 跳过
   → 汇总 ScanResult(added/updated/skipped/failed)
```

- **编码探测**：`EncodingDetector.detect(bytes)` = BOM 检查 → juniversalchardet → 回退链 UTF-8 → GB18030 → Big5（移植 novelmgt 策略）；探测结果落库（`books.charset`），重开不重探
- **章节解析**：`ChapterParser` 移植 novelmgt 的正则优先级表（第X章/回/节/卷、Chapter N、数字标题、卷X）与中文数字转换算法；扫描时两遍读取：第一遍按行匹配标题记录**字节偏移**，第二遍不读全文，直接写 `chapters` 索引
- **EPUB**：解 zip → 解析 `container.xml` → OPF → metadata（书名/作者/封面）+ spine（章节 href 序列）；章节 HTML 首读时经 Jsoup 抽取段落文本，落盘到 `epub_text/` 缓存，之后与 TXT 共用加载路径

### 5.2 TXT 章节按需加载

- `chapters.start_offset/end_offset` 为**字节**偏移；读取时按 `charset` 对字节区间解码
- 注意多字节编码不可按字节切割：解码整章字节区间后得到 String（单章通常 < 100KB，内存安全）
- 10MB 全书仅扫描时顺序流式读两遍，常驻内存 ≤ 数 MB

### 5.3 EPUB 章节加载

`EpubChapterLoader.load(book, chapterIdx)`：命中 `epub_text/` 缓存直接读；未命中 → 打开 zip → 读取 `epub_href` 项 → Jsoup 抽取（保留段落换行）→ 写缓存 → 返回。封面从 OPF `cover` 属性项提取。

### 5.4 ★ 统一进度模型（听读同步核心）

```kotlin
/** 全应用唯一的位置表达 */
data class BookPosition(
    val bookId: Long,
    val chapterIdx: Int,
    val charOffset: Int,   // 章内字符偏移
)

interface ProgressRepository {
    suspend fun position(bookId: Long): BookPosition?
    suspend fun update(pos: BookPosition, source: Source)  // upsert + 重算 percent
    fun observe(bookId: Long): Flow<BookPosition?>
}
enum class Source { READ, LISTEN }
```

**两个方向的映射**：

| 方向 | 映射 | 时机 |
|------|------|------|
| 阅读 → 进度 | 当前页首字符的 charOffset（分页器给出每页字符区间） | 翻页时，2s 防抖写入 |
| 听书 → 进度 | 当前句 `Sentence.start` 的 charOffset | 每句结束/暂停/退出，1s 节流写入 |
| 进度 → 阅读器 | `paginator.pageOf(charOffset)` → 跳到包含该偏移的页 | 打开书籍 |
| 进度 → 听书 | `sentences.first { it.end > charOffset }` → 从该句起播 | 点"听书" |

**无冲突设计**：进度只有一行；同一时刻只有阅读或听书一个在写（听书以前台服务为准，阅读器在前台时服务不写）。`source` 字段仅用于 UI 展示「上次读到/听到」。

### 5.5 阅读器分页引擎

```kotlin
class ChapterPaginator(
    text: String,
    paint: TextPaint,            // 字号/字体/行距
    contentWidth: Int, contentHeight: Int,
) {
    val pages: List<IntRange>    // 每页字符区间
    fun pageOf(charOffset: Int): Int
}
```

- 用 `StaticLayout.Builder` 排版整章，按行累积高度切页，记录每页 `(startChar, endChar)`
- 渲染：Compose `Canvas` 中 `layout.draw(canvas)` 按页绘制对应行区间；覆盖翻页 = 两页 `graphicsLayer` 平移；滚动模式 = 整章单 `StaticLayout` + 滚动偏移（进度 = 首可见行字符偏移）
- 排版参数变化 → 重建 paginator → 用旧 charOffset 重新定位（AC-F04-b）
- 章节边界：章首/章尾无缝衔接上下章（预取相邻章 paginator）

### 5.6 ★ TTS 播放子系统

**5.6.1 引擎抽象**

```kotlin
interface TtsEngine {
    val engineId: String
    val supportsSynthesisToFile: Boolean
    suspend fun speak(s: Sentence, utteranceId: String)          // 直读模式
    suspend fun synthesizeToFile(s: Sentence, out: File): Boolean // 合成模式
    fun setSpeechRate(rate: Float); fun setPitch(pitch: Float)
    fun stop(); fun shutdown()
}
```

| 实现 | 说明 |
|------|------|
| `SystemTtsEngine` | 包装 `android.speech.tts.TextToSpeech`（AOSP 框架 API）。启动时 `Intent(ACTION_CHECK_TTS_DATA)` 探测引擎列表；用户可在设置中选择任一已安装引擎（eSpeak NG / RHVoice / 厂商引擎），**永不依赖 Google TTS** |
| `OnnxTtsEngine`（规划，未交付） | sherpa-onnx + VITS 中文模型，完全离线的兜底引擎；模型文件经 SAF 导入到私有目录（APK 不内置模型，控制体积）。V1.3 时点仍未实现，属 F-14 待办 |

无引擎设备：`EngineAvailabilityChecker` 检测为空 → 引导页（F-06-03）。

**5.6.2 分句与合成管线（路由可控模式，默认）**

```
ChapterTextSource(章节纯文本)
  → SentenceSplitter → List<Sentence(index, start, end, text)>
       切分符 [。！？!?；;…\n]，保留分隔符；<6 字短句并入前句；>120 字硬切
  → Synthesizer（2 句预取流水）: engine.synthesizeToFile → cache/tts/.../sIdx.wav
  → ExoPlayer：每句 = 一个 MediaItem(mediaId="book/ch/s/offset")
  → onMediaItemTransition + 1s ticker → ProgressWriter.update(position, LISTEN)
```

- 选合成模式而非纯 `speak()` 的原因：**只有拿到 AudioTrack 才能可靠地指定输出设备**（`Media3 setPreferredAudioDevice` / `AudioTrack.setPreferredDevice`），这是 F-08-03「强制扬声器」在 Android 12+ 上的可靠路径；同时获得精确到句的进度事件与无缝衔接
- 句级粒度的好处：合成延迟低（起播 < 1.5s）、缓存 LRU 友好、进度天然到句

**5.6.3 直读降级模式（V1.x 唯一实现）**

引擎不支持 `synthesizeToFile`（少数引擎）时：`speak()` + `UtteranceProgressListener`，`utteranceId` 编码 `bookId:chIdx:sIdx:charOffset`，回调驱动进度；输出路由**只能强制外放**（见 §5.7），其余设备跟随系统（UI 明示该限制）。

> **V1.3 现状**：`TtsPlaybackService` 在 `speakFrom` 前调用 `applyForceSpeakerRouting()`（保存系统原 `isSpeakerphoneOn`/`mode` → 置 `mode=MODE_NORMAL` + `isSpeakerphoneOn=true`），暂停/停止/`onDestroy` 调用 `restoreSystemRouting()` 成对还原；`runCatching` 失败只 `Log.w` 不回滚崩溃。未改动分句、进度回写、MediaSession/通知逻辑。

**5.6.4 前台服务与媒体会话**

- `TtsPlaybackService : MediaSessionService`（Media3），`foregroundServiceType="mediaPlayback"`
- 通知栏媒体卡片、锁屏控制、线控/蓝牙按键全部经 MediaSession
- 熄屏保活：`startForeground` + 部分唤醒锁（播放时持有，暂停即释放）
- 服务为播放事实源；UI（听书面板/阅读器高亮）经 `MediaController` 订阅

### 5.7 音频路由与焦点（V1.3 降级实现）

现状：直读模式（`TextToSpeech.speak`）由系统决定路由，应用只能可靠「强制外放」；精确设备选择留待 V2 合成管线。

```kotlin
interface OutputDeviceRepository {
    fun observeOutputs(): Flow<List<AudioOutput>>   // 已去重计数/分组排序
}

enum class AudioOutputGroup { SPEAKER, WIRED, BLUETOOTH, USB, OTHER }
```

- **设备枚举**（已交付）：`AudioOutputRepositoryImpl`（`core/audio/`）= `callbackFlow` + `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` + `registerAudioDeviceCallback`（插拔即刷新，`awaitClose` 注销）；无 AudioManager / 注册或读取异常 → 空列表 + `Log.w`，不崩不抛
- **设备展示**（已交付）：`AudioOutputs` 纯函数——类型→中文名（未知类型兜底「其他音频设备」）、5 组分组、同类型去重计数、摘要文案（如「扬声器 · 有线耳机 · 蓝牙音频」）；阅读器听书面板 `AudioOutputSection` 显示摘要
- **强制外放**（已交付，降级）：`ListenSettings.forceSpeaker`（DataStore `tts_force_speaker`）→ `TtsPlaybackService` 播放前 `mode=MODE_NORMAL` + `isSpeakerphoneOn=true`（保存/还原成对，见 §5.6.3）；manifest 需 `MODIFY_AUDIO_SETTINGS`（普通权限，安装即授予）；接管与还原两侧都带通话守卫（`MODE_IN_CALL/IN_COMMUNICATION` 时跳过并保留待还原状态，见 AGENTS 已知问题 13）
- **既有行为**：`ACTION_AUDIO_BECOMING_NOISY`（拔耳机/断蓝牙）→ 立即暂停；音频焦点 `AudioFocusRequest(AUDIOFOCUS_GAIN)` 抢占（LOSS / LOSS_TRANSIENT / CAN_DUCK）→ 暂停，退出释放
- **降级限制（如实）**：直读模式下有线/蓝牙/USB 无法由应用指定，只能跟随系统；部分 ROM 可能忽略 `isSpeakerphoneOn`（UI 已提示）；精确路由（Media3 `setPreferredAudioDevice` / `AudioTrack.setPreferredDevice`）需 V2 合成管线（`synthesizeToFile` + ExoPlayer）
- **未验证**：真机 TTS 出声、真机蓝牙/USB 路由与强制外放实际效果（模拟器 `-no-audio` 不覆盖）

---

## 6. 权限设计

| 权限 | 变体 | 用途 | 备注 |
|------|------|------|------|
| （无 INTERNET） | offline（默认） | — | **核心卖点**：系统级证明全离线 |
| SAF 授权 | 全部 | 扫描文件夹/导入/备份 | 非 manifest 权限，运行时 `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` |
| `POST_NOTIFICATIONS` | 全部 | 播放控制通知 | API 33+ 运行时申请 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 全部 | 听书前台服务 | API 34+ 必须声明 typed FGS |
| `WAKE_LOCK` | 全部 | 播放保活 | — |
| `MODIFY_AUDIO_SETTINGS` | 全部 | 听书强制外放（`AudioManager.mode` / `isSpeakerphoneOn`） | 普通权限，安装即授予；不影响零网络红线（V1.3 新增） |
| `READ_EXTERNAL_STORAGE` (maxSdk=32) | 全部 | 旧设备文件遍历 | 仅 API ≤ 32 生效 |
| `INTERNET` + `ACCESS_NETWORK_STATE` | **full（V2，可选）** | WebDAV 备份 | 唯一差异点；不含任何 Google 服务 |

---

## 7. 关键流程时序

### 7.1 打开书籍继续阅读

```
书架点击 → OpenBook(bookId)
  → 并行: ①ProgressRepository.position() ②ChapterRepository.chapters()
  → ChapterTextSource.load(chapterIdx=progress.chapterIdx)
  → ChapterPaginator 构建 → pageOf(progress.charOffset) 定位
  → 渲染 + 预取相邻章
```

### 7.2 阅读器发起听书（读→听）

```
阅读器点「听书」→ 当前页首 charOffset → StartListening(pos)
  → 绑定/启动 TtsPlaybackService
  → 加载本章文本 → 分句 → 定位 sentences.first{end>charOffset}
  → 合成前两句 → ExoPlayer 起播 → 前台服务+通知
  → 每句 transition → ProgressWriter（source=LISTEN）
```

### 7.3 听书回阅读（听→读）

```
停止听书（手动/定时/拔耳机）→ 最后一次进度落库
  → 打开阅读器 → 走 7.1 流程 → 定位在最后朗读者句首
```

### 7.4 增量重扫（文件变更）

```
扫描命中 mtime 变化 → 重建章节索引
  → 新旧章节按 idx 对齐：progress.chapterIdx 越界 → clamp 到末章并 Snackbar 提示
  → 更新 books.chapter_count/word_count/file_mtime
```

---

## 8. 构建变体（Flavor）

| 变体 | INTERNET | 差异 | 分发渠道 |
|------|----------|------|----------|
| `offline`（默认） | ❌ | 全部功能 | GitHub Release / F-Droid |
| `full`（V2） | ✅ | + WebDAV 备份 | GitHub Release |

两变体共享全部代码，WebDAV 相关代码以 `src/full/` sourceSet 隔离；`offline` 编译产物中物理不含网络代码。

---

## 9. 性能与内存策略

- **大 TXT**：章节字节偏移索引，永不整本常驻；扫描流式两遍读
- **书架**：Room 分页（Paging 3）+ 封面缩略图（Decode downsample）
- **分页器**：仅当前章 ±1 章常驻；重建放 `Dispatchers.Default`
- **TTS 缓存**：句级 wav LRU 200MB 上限，设置页可清理；退书即停合成任务（Job 取消）
- **电量**：暂停即释放唤醒锁；进度写入全部防抖/节流

---

## 10. 测试策略

| 层 | 内容（2026-11-08 现状） |
|----|------|
| 单元测试（**75 个 / 0 失败**，V1.4） | ChapterParser 14、EPUB 5、LibraryFilters 10、BackupFormat 29（+核心键/表顺序/64MB 校验）、BackupJsonWriter 4（结构/转义/超限/流式语义）、AudioOutputs 11；`./gradlew :app:testOfflineDebugUnitTest`。规划中尚未覆盖：EncodingDetector 混合编码语料、SentenceSplitter、进度 ↔ 页/句精度用例 |
| 仪器测试 | **25 个 / 0 失败**（V1.4）：MigrationV1ToV2Test 4、BackupRoundtripTest 8（含 >16MB 大备份流式 roundtrip、缺核心表键拒绝）、DatabaseMaintenanceTest 4（孤儿清理/幂等/仓库级删书）、TagDaoTest 5、TagUiFlowTest 1、ReaderPaginationMetricsTest 3；`./gradlew :app:connectedOfflineDebugAndroidTest` |
| 迁移测试（已落地） | `exportSchema=true` + `app/schemas/com.yyx.reader.core.database.YYXDatabase/2.json` + `MigrationV1ToV2Test` 4 例（自建真实 v1 库 + 与生产一致的 Room 打开路径，验证迁移/级联/幂等；不引入 room-testing）。单跑：`./gradlew :app:connectedOfflineDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yyx.reader.migration.MigrationV1ToV2Test`。后续升 v3 需同步提交新 schema 并新增迁移用例（测试 KDoc 已写约定） |
| 合规检查（**已落地**，2026-11-08） | Gradle 任务 `verifyOfflineDebug/ReleaseCompliance`、`verifyFullDebug/ReleaseCompliance`（`app/build.gradle.kts`，group=verification）：① offline 合并后 manifest 无 `INTERNET` / `ACCESS_NETWORK_STATE`，且含 TTS_SERVICE `<queries>`；② 运行时依赖树无 `com.google.android.gms` / `firebase` / `mlkit` / `play`。命令 `./gradlew verifyOfflineDebugCompliance`；`:app:check` 已 dependsOn offline 两个任务。**V1.4 起 `app/lint.xml` 只抑制这 3 条既有误报（未全局关闭），`./gradlew :app:check` 已全绿（lint 0 error + 合规 + 单测），可作 CI 门禁 |
| E2E（手动，模拟器） | yyx_test（AOSP API34，无 GMS）：V1.2 = v1→v2 迁移数据保留（3 书/15 章/2 进度/1 书签）、标签全链路、备份导出 → `pm clear` → 导入全恢复（UI 层 SAF 全自动）；V1.3 = 备份健壮性（缺表键保留本机/空数组清空/重复主键告警/17MB 超限拒绝/book_tags 无效引用过滤/恢复后引擎与书源预警）、听书面板输出设备摘要与强制外放（开关持久化 + 起播接管/暂停还原）；证据 `/tmp/yyx-qa/`、`/tmp/yyx-qa-v13/`。**未覆盖**：真机、TTS 真出声与自动推进（`-no-audio` 限制）、真机蓝牙/USB 路由 |
| 手动验收 | PRD §8 五个 E2E 用例（飞行模式、无 GMS 设备、耳机、大库、进度闭环）——无 GMS 模拟器与进度闭环已在 V1.0/V1.1 走过，真机与耳机场景待做 |

---

## 11. 风险与对策

| 编号 | 风险 | 对策 |
|------|------|------|
| R1 | 无 GMS 设备常无 TTS 引擎，或引擎无中文语音 | F-06-03 引导 + F-14 sherpa-onnx 内置引擎（模型 SAF 导入）；V1.1 交付兜底 |
| R2 | 「强制扬声器」在部分 OEM ROM 上被忽略 | 双路径实现（preferredDevice + legacy speakerphoneOn）+ UI 明示降级 |
| R3 | SAF 遍历数万文件慢 | 批量契约查询 + 协程并发 + 增量扫描；可取消 |
| R4 | sherpa-onnx 模型体积（~50–100MB）与许可证 | 模型不内置 APK，SAF 导入；模型许可单独评审入关于页 |
| R5 | EPUB 变种（缺 OPF 字段/嵌套路径）导致解析失败 | Jsoup 容错 + 失败书单章兜底 + 「重新解析」入口 |
| R6 | 多字节编码按字节切章可能切出半个字符 | 按字节区间解码后丢弃尾部不完整序列（解码器 `REPLACE` 策略 + 区间向后扩展 4 字节再裁切） |
| R7 | Doze/厂商省电杀前台服务 | typed FGS + 唤醒锁 + 引导用户加白名单（文字引导，不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，保持合规） |

---

## 12. 与 novelmgt 的复用对照

| novelmgt（Flutter/Dart） | 本架构（Kotlin） | 复用方式 |
|---|---|---|
| `chapter_parser.dart`（正则表 + 中文数字） | `core/ebook/txt/ChapterParser.kt` | **算法移植** |
| 编码探测链 UTF-8→GB18030→Big5 | `EncodingDetector.kt`（juniversalchardet + 回退链） | 策略移植 |
| `reading_progress`(chapterId+scrollPosition) | `reading_progress`(chapter_idx+char_offset) | **模型升级**（像素→字符偏移，支撑听读同步） |
| `import_service` / `library_scan_service` | ScanLibrary UseCase | 流程参考（更新检测、结果统计） |
| `epubx`+`html` 包解析 EPUB | zip+Jsoup 自解析 | 方案对齐 |
| SQL 备份 | F-12 备份与恢复：单 JSON 文件（schema=1，org.json，SAF 直写/读取），覆盖式恢复（事务清空 + 按原 id 回填），含索引/进度/书签/标签/设置；V1.3 补导入健壮性（缺非核心表键保留本机、重复主键/引用完整性告警）；V1.4 改为**流式**（`BackupJsonWriter` 逐行写 / `BackupStreamReader` 两遍 JsonReader），上限 64MB、行级内存有界、缺核心表键（books/chapters）拒绝导入 | 思路对齐、载体替换（SQL → JSON）；不含书源文件与 SAF 持久授权 |
| `crawler_service` / `book_source` / NAS 服务端 | — | **不移植（P-4 原则）** |
