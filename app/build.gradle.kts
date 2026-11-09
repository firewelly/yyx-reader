import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.yyx.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yyx.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // V1.5 内置离线 TTS：sherpa-onnx AAR 全 4 ABI 未压缩 ~126MB，按需裁剪。
            // arm64-v8a = 2019+ 真机与 Apple Silicon 模拟器（yyx_test 即 arm64）；
            // armeabi-v7a = 老 32 位设备（恰是无系统 TTS 引擎、最需要兜底引擎的设备）。
            // x86/x86_64 为极旧模拟器镜像，舍弃（APK 体积优先）。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 分发变体（见 docs/ARCHITECTURE_ANDROID.md §8）
    flavorDimensions += "distribution"
    productFlavors {
        create("offline") {
            dimension = "distribution"
            // 默认分发变体：全离线，manifest 不声明 INTERNET 权限
        }
        create("full") {
            dimension = "distribution"
            // V2 预留：WebDAV 备份等需要网络的唯一变体（仍无任何 Google 服务）
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Room schema 导出（V1.3：迁移测试基准）——生成 app/schemas/<DatabaseFqcn>/<version>.json 并保留在仓库
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ───────────────────────────────────────────────────────────────────────────
// 合规校验（docs/ARCHITECTURE_ANDROID.md §10「合规检查（CI）」，AGENTS.md 合规红线 1/2/5）
// 把两件事从人工检查变成构建期断言：
//   1) offline（默认分发）变体合并后 manifest 绝无 INTERNET / ACCESS_NETWORK_STATE，
//      且 TTS 引擎发现所需的 <queries> + TTS_SERVICE 声明未被误删；
//   2) 变体运行时依赖树绝无 GMS / Firebase / MLKit / Play 依赖。
// 只使用 AGP 8.7 自带的 Variant API，不新增任何依赖或插件。
// ───────────────────────────────────────────────────────────────────────────
androidComponents {
    onVariants { variant ->
        // 任务名：offlineDebug → verifyOfflineDebugCompliance
        val variantTaskName = "verify${variant.name.replaceFirstChar { it.uppercase() }}Compliance"
        // 仅 offline 变体强制「零网络权限」；full 是 V2 预留的网络变体，只断言依赖与 TTS 声明
        val enforceOfflineNetworkRule = variant.flavorName == "offline"
        // 合并后 manifest 产物：作为任务输入后，执行校验任务会自动先跑 manifest 合并任务
        val mergedManifestProvider = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        // 运行时依赖配置：只在任务执行期（doLast）解析，避免配置期解析拖慢配置阶段
        val runtimeConfiguration = variant.runtimeConfiguration

        val verifyTask = project.tasks.register(variantTaskName) {
            group = "verification"
            description = "合规校验（$variantTaskName）：" +
                (if (enforceOfflineNetworkRule) "无网络权限 + " else "") +
                "无 GMS/Firebase/MLKit 依赖、TTS 查询声明存在"
            inputs.file(mergedManifestProvider) // 未声明输出 → 每次执行都重新断言
            doLast {
                val problems = mutableListOf<String>()
                val manifestText = mergedManifestProvider.get().asFile.readText(Charsets.UTF_8)

                // ── 1a. 合并后 manifest 权限断言（按 android:name="..." 精确匹配，避免注释/字符串误伤）──
                fun manifestDeclaresPermission(permission: String): Boolean =
                    Regex("android:name\\s*=\\s*\"${Regex.escape(permission)}\"").containsMatchIn(manifestText)

                if (enforceOfflineNetworkRule) {
                    if (manifestDeclaresPermission("android.permission.INTERNET")) {
                        problems += "合并后 manifest 出现 android.permission.INTERNET（offline 变体必须零网络权限）"
                    }
                    if (manifestDeclaresPermission("android.permission.ACCESS_NETWORK_STATE")) {
                        problems += "合并后 manifest 出现 android.permission.ACCESS_NETWORK_STATE（media3 会合并进来，" +
                            "需保留 app/src/offline/AndroidManifest.xml 的 tools:node=\"remove\" 声明）"
                    }
                }

                // ── 1b. TTS 包可见性声明断言（AGENTS.md 合规红线 5：勿删 <queries>）──
                if (!Regex("<queries[\\s>]").containsMatchIn(manifestText) ||
                    !manifestText.contains("android.intent.action.TTS_SERVICE")
                ) {
                    problems += "合并后 manifest 缺少 <queries> + android.intent.action.TTS_SERVICE 声明" +
                        "（Android 11+ TTS 引擎发现依赖它，见 AGENTS.md 合规红线 5）"
                }

                // ── 2. 运行时依赖断言：禁止 GMS / Firebase / MLKit / Play ──
                // 允许 com.google.dagger（Hilt）、com.google.android.material 等非 GMS 库
                val forbiddenGroupPrefixes = listOf(
                    "com.google.android.gms",  // GMS / Play Services 全家桶
                    "com.google.firebase",     // Firebase 全家桶
                    "com.google.mlkit",        // ML Kit
                    "com.google.android.play", // Play Core（应用内更新/评论等）
                )
                val resolutionResult = runtimeConfiguration.incoming.resolutionResult
                val offendingComponents = resolutionResult.allComponents.filter { component ->
                    val id = component.id
                    id is ModuleComponentIdentifier &&
                        forbiddenGroupPrefixes.any { id.group == it || id.group.startsWith("$it.") }
                }

                // 在依赖图里 BFS 找命中组件的引入路径，便于定位是哪个依赖带进来的
                fun dependencyPath(target: ModuleComponentIdentifier): String {
                    val root = resolutionResult.root
                    val queue = ArrayDeque(listOf(root to listOf(root.id.displayName)))
                    val visited = mutableSetOf<ComponentIdentifier>()
                    while (queue.isNotEmpty()) {
                        val (current, path) = queue.removeFirst()
                        if (!visited.add(current.id)) continue
                        if (current.id == target) return path.joinToString(" → ")
                        current.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                            queue.addLast(dependency.selected to (path + dependency.selected.id.displayName))
                        }
                    }
                    return "未找到传递路径（直接声明或解析结果缺失）"
                }

                offendingComponents.map { it.id as ModuleComponentIdentifier }.forEach { gav ->
                    problems += "禁用依赖 ${gav.group}:${gav.module}:${gav.version}，引入路径：${dependencyPath(gav)}"
                }

                if (problems.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("❌ 合规校验失败（${variant.name} 变体），共 ${problems.size} 项：")
                            problems.forEach { appendLine("  · $it") }
                            appendLine("处理建议：")
                            appendLine(
                                "  · 网络权限：offline 变体不得声明 INTERNET；ACCESS_NETWORK_STATE 由 " +
                                    "app/src/offline/AndroidManifest.xml 的 tools:node=\"remove\" 剔除，勿删。",
                            )
                            appendLine("  · GMS/Firebase/MLKit/Play：从依赖中移除（见 docs/ARCHITECTURE_ANDROID.md D2 禁用清单）。")
                            appendLine("  · TTS 查询：恢复 app/src/main/AndroidManifest.xml 的 <queries> + TTS_SERVICE 声明（AGENTS.md 合规红线 5）。")
                        },
                    )
                }

                if (enforceOfflineNetworkRule) {
                    logger.lifecycle(
                        "✅ 合规校验通过：offline 变体无 INTERNET/ACCESS_NETWORK_STATE，" +
                            "无 GMS/Firebase/MLKit 依赖，TTS 查询声明存在",
                    )
                } else {
                    logger.lifecycle(
                        "✅ 合规校验通过：${variant.name} 变体无 GMS/Firebase/MLKit 依赖，" +
                            "TTS 查询声明存在（该变体不限制网络权限）",
                    )
                }
            }
        }

        // 接入生命周期：:app:check 只挂 offline 变体，保证默认分发构建回归时必跑；
        // full 变体校验任务保留独立执行方式（如 ./gradlew verifyFullDebugCompliance）
        if (enforceOfflineNetworkRule) {
            project.tasks.named("check") { dependsOn(verifyTask) }
        }
    }
}

dependencies {
    // Compose（BOM 统一管理版本）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 数据
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)   // SAF 目录遍历

    // 听书播放
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.androidx.media)          // MediaSessionCompat + MediaStyle 通知

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // 电子书解析
    implementation(libs.jsoup)                 // EPUB HTML → 纯文本（MIT）
    implementation(libs.juniversalchardet)     // TXT 编码探测（MPL-1.1）

    // 内置离线 TTS 引擎（V1.5，AGENTS.md 待办 #6）：sherpa-onnx 官方 AAR（Apache-2.0）。
    // 纯本地推理、manifest 零权限（已人工核验），不触发任何网络；
    // 来源：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1 （官方发行版，SHA 仓库内随档）
    // 语音模型不内置 APK，由用户经 SAF 导入（见 LocalTtsEngine / SettingsScreen「离线语音模型」）。
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
