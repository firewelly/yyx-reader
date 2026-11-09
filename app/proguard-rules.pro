# Minify 规则占位。
# 注意：引入新库时优先确认无需 keep；涉及反射的库（如未来引入序列化库）在此补充规则。

# V1.5 内置离线 TTS（app/libs/sherpa-onnx-2.0.1.aar，Apache-2.0）：
# AAR 自带 proguard.txt 为空，而 JNI 按类名/方法名回调 Kotlin 数据类（OfflineTts/各 *ModelConfig），
# R8 改名会直接导致 native 侧找不到入口，必须全量保留。
-keep class com.k2fsa.sherpa.onnx.** { *; }
