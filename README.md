# HarmonyOS Core Vision Kit on Android

在普通 Android 应用中离线运行 HarmonyOS Core Vision Kit 的原厂引擎和模型。Android 库覆盖主体分割与精细抠图、人脸检测与比对、文字识别、物体检测、骨骼检测、图像超分和以文搜图。

## 为什么做这个项目

iOS 的 Vision 和鸿蒙的 Core Vision Kit 都提供系统级前景分割。Android 上，ML Kit 的主体分割模型通过 Google Play 服务下发，缺少这项服务的国行设备无法使用该接口。我们原先在应用里内置 `RMBG-1.4` 做离线抠图。这个社区适配项目让这些设备也能在本地运行鸿蒙的主体分割与精细抠图流程。

[Apple Vision](https://developer.apple.com/documentation/vision/vngenerateforegroundinstancemaskrequest) · [鸿蒙主体分割](https://developer.huawei.com/consumer/cn/doc/doccenter-references/api/core-vision-subjectsegmentation-api.md) · [ML Kit 主体分割](https://developers.google.com/ml-kit/vision/subject-segmentation/android)

## 能力一览

| 鸿蒙 Core Vision Kit 能力 | Android 适配进度 |
| --- | --- |
| 主体分割与精细抠图 `subjectSegmentation` | `CoreVision.segment()` 返回合并蒙版、主体数、边界和可选的单主体结果 |
| 人脸检测 `faceDetector` | `CoreVision.detectFaces()`，已通过离线模拟器验收，返回人脸框、关键点和姿态 |
| 双图人脸比对 `faceComparator` | `CoreVision.compareFaces()`，已通过离线模拟器验收，返回相似度和同一人判定 |
| 文字识别 `textRecognition` | `CoreVision.recognizeText()` 返回文本块、行、词和四角坐标 |
| 物体检测 `objectDetection` | `CoreVision.detectObjects()` 返回框、类别和得分 |
| 骨骼检测 `skeletonDetection` | `CoreVision.detectSkeletons()` 返回骨骼框及 17 个有类型和置信度的关键点 |
| 图像超分 `imageSuperResolution` | `CoreVision.superResolve()` 返回宽高各放大 4 倍的 `Bitmap` |
| 以文搜图 `textSearchImage` | `CoreVision.insertImage()`、`searchImages()`、`deleteImage()`、`clearImageSearch()` |

`visionBase` 的图像输入和分析生命周期映射到 Android 的 `Bitmap`、异步请求和 `CoreVision.close()`。每项能力所需的模型和接口差异见 [Gradle 接入与运行时准备](docs/android-integration.md)。

## 快速上手

`0.1.0` 面向 Android 8.0（API 26）及以上、使用 4 KiB 内存页的 `arm64-v8a` 设备。仓库提供适配层源码、示例和验证工具。接入方自行准备与项目版本匹配的原厂运行时和模型，随应用分发时需具备相应的再分发权利。

1. 按所需能力准备匹配的原厂文件并生成 `COREVISION_RUNTIME_DIR`。[Gradle 接入与运行时准备](docs/android-integration.md)
2. 连接设备后安装示例。物体检测、骨骼检测、OCR、超分和图片索引入口位于「更多视觉能力」。

   ```sh
   ./gradlew -PcorevisionRuntimeDir="$COREVISION_RUNTIME_DIR" :sample:installDebug
   ```

3. 接入自己的应用时，运行 `./gradlew :mavenZip` 生成本地 Maven 发布包，按指南添加 `dev.corevision:corevision-android:0.1.0` 和运行时打包配置。

在 Kotlin 中调用 `segment` 即可异步处理图片。

```kotlin
import dev.corevision.android.CoreVision

val coreVision = CoreVision(context)
coreVision.segment(bitmap, true).thenAccept { result ->
    imageView.post { imageView.setImageBitmap(result.foreground) }
}
```

只需蒙版时调用 `segment(bitmap)`，从结果的 `alpha` 读取。会话用完后调用 `close()`。

调用 `detectFaces(bitmap)` 可按人脸框面积从大到小获取检测结果。没有人脸时返回空列表。[人脸检测 API 与复测](docs/android-integration.md#人脸检测)

调用 `compareFaces(first, second)` 可比对两张各含一张可检测人脸的照片。[人脸比对 API 与复测](docs/android-integration.md#双图人脸比对)

## 体积对比

| 文件或产物 | 大小 | 统计口径 |
| --- | ---: | --- |
| Core Vision 原厂运行时和模型 | 159.0 MiB | 未压缩，含 124.7 MiB 原生库及加载器、34.3 MiB 模型 |
| 仅含抠图配置的示例 Release APK | 73.0 MiB | 原验收构建，ARM64 未签名 APK |
| RMBG-1.4 FP32 ONNX | 168.0 MiB | Bria 公开模型文件 |
| RMBG-1.4 FP16 ONNX | 84.1 MiB | Bria 公开模型文件 |
| RMBG-1.4 量化 ONNX | 42.3 MiB | Bria 公开模型文件 |

按未压缩文件大小，Core Vision 完整运行时加模型比 RMBG-1.4 的 FP32 单模型小约 9 MiB，比 FP16 和量化单模型大。替换后的 APK 体积还取决于宿主实际使用的 RMBG 模型、推理库和压缩设置。[Bria ONNX 文件清单](https://huggingface.co/briaai/RMBG-1.4/tree/da1d078376e6691105a5530a374a010627fc60b5/onnx)

表中的原厂运行时和模型用量来自抠图配置。人脸检测额外加入约 `14.3 MiB` 的未压缩模型文件。双图比对在检测配置上再加入约 `19.2 MiB` 的特征模型。

## 真机与离线验证

主体分割已在未 root 的 ARM64 Android 真机和断网模拟器上验证。与鸿蒙真机对同一张 `1536 × 1024` 图片的输出相比，301 主体分割和 302 精细抠图的 IoU 分别为 `0.99166` 和 `0.99700`（alpha `>128`）。其余能力及主体明细的验收环境为断网的 Android API 35 ARM64 模拟器，公开鸿蒙 API 的同图结果有待逐项对照。[完整验收记录](docs/android-integration.md#已测结果) · [原厂引擎适配方案](docs/technical-proposal.md)
