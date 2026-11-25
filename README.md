# HarmonyOS Core Vision Kit on Android

在普通 Android 应用中调用 HarmonyOS Core Vision Kit 的原厂引擎，离线完成主体分割和精细抠图。输入一张 `Bitmap`，得到原图尺寸的 8 位 alpha 蒙版和可选的透明前景图。

## 为什么做这个项目

iOS 的 Vision 和鸿蒙的 Core Vision Kit 都提供系统级前景分割。Android 上，ML Kit 的主体分割模型通过 Google Play 服务下发，缺少这项服务的国行设备无法使用该接口。我们原先在应用里内置 `RMBG-1.4` 做离线抠图。这个社区适配项目让这些设备也能在本地运行鸿蒙的主体分割与精细抠图流程。

[Apple Vision](https://developer.apple.com/documentation/vision/vngenerateforegroundinstancemaskrequest) · [鸿蒙主体分割](https://developer.huawei.com/consumer/cn/doc/doccenter-references/api/core-vision-subjectsegmentation-api.md) · [ML Kit 主体分割](https://developers.google.com/ml-kit/vision/subject-segmentation/android)

## 快速上手

`0.1.0` 面向 Android 8.0（API 26）及以上、使用 4 KiB 内存页的 `arm64-v8a` 设备。仓库提供适配层源码、示例和验证工具。接入方自行准备与项目版本匹配的原厂运行时和模型，随应用分发时需具备相应的再分发权利。

1. 按指南配置 SDK，用获准使用的原厂文件生成 `COREVISION_RUNTIME_DIR`。[Gradle 接入与运行时准备](docs/android-integration.md)
2. 连接设备后安装示例，打开应用并点击「选择图片并抠图」。

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

## 体积对比

| 文件或产物 | 大小 | 统计口径 |
| --- | ---: | --- |
| Core Vision Android AAR `0.1.0` | 73.9 KiB | 适配层与桥接程序 |
| Core Vision 原厂运行时和模型 | 159.0 MiB | 未压缩，含 124.7 MiB 原生库及加载器、34.3 MiB 模型 |
| Core Vision 完整示例 Release APK | 73.0 MiB | ARM64 未签名 APK，包含运行时和模型 |
| RMBG-1.4 FP32 ONNX | 168.0 MiB | Bria 公开模型文件 |
| RMBG-1.4 FP16 ONNX | 84.1 MiB | Bria 公开模型文件 |
| RMBG-1.4 量化 ONNX | 42.3 MiB | Bria 公开模型文件 |

按未压缩文件大小，Core Vision 完整运行时加模型比 RMBG-1.4 的 FP32 单模型小约 9 MiB，比 FP16 和量化单模型大。替换后的 APK 体积还取决于宿主实际使用的 RMBG 模型、推理库和压缩设置。[Bria ONNX 文件清单](https://huggingface.co/briaai/RMBG-1.4/tree/da1d078376e6691105a5530a374a010627fc60b5/onnx)

## 真机与离线验证

未 root 的 ARM64 真机和断网模拟器均以普通应用权限、在 SELinux Enforcing 下通过 AAR 验收。与鸿蒙真机对同一张 `1536 × 1024` 图片的输出相比，301 主体分割和 302 精细抠图的 IoU 分别为 `0.99166` 和 `0.99700`（alpha `>128`）。[完整验收记录](docs/android-integration.md#已测结果) · [原厂引擎适配方案](docs/technical-proposal.md)
