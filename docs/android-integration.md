# Android Gradle 接入和复测

`dev.corevision:corevision-android:0.1.0` 支持 Android API 26 及以上的 ARM64、4 KiB 页应用进程。应用调用私有 Binder 服务，服务从 `ApplicationInfo.nativeLibraryDir` 启动自研程序，托管每请求独立的工作进程。服务用文件描述符传递像素，工作进程通过带长度限制的管道返回结果。托管程序按请求的剩余时限设置内核定时器。到期时内核终止托管程序及工作进程，服务返回 `TIMEOUT`。

## 接入 AAR

解压 `corevision-android-0.1.0-maven.zip`。在宿主的 `settings.gradle` 添加解压后的 `maven` 目录。

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri('/path/to/distribution/maven') }
    }
}
```

在宿主的 `app/build.gradle` 添加依赖和原生文件打包配置。`corevisionRuntimeDir` 指向本机的获准运行时目录。可先省略该属性，只运行 `probe()`。

```groovy
dependencies {
    implementation 'dev.corevision:corevision-android:0.1.0'
}

def runtimeDir = providers.gradleProperty('corevisionRuntimeDir').orNull
android {
    defaultConfig {
        minSdk 26
        ndk { abiFilters 'arm64-v8a' }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += ['**/*.so']
        }
    }
    if (runtimeDir != null) {
        sourceSets.main.jniLibs.srcDir file("${runtimeDir}/jniLibs")
        sourceSets.main.assets.srcDir file("${runtimeDir}/assets")
    }
}
```

`useLegacyPackaging` 让包管理器把可执行文件解包到安装目录。对 AAB 生成的 APK 也应执行安装探针。保留原厂 ELF 的字节和原文件名，让 musl 能按 SONAME 加载对应依赖。宿主若已依赖同名的 `libc++_shared.so` 或其他 `.so`，应解决源依赖的冲突再合并 APK。按文件名挑选一个副本会让另一套 ABI 失去对应的库。

## 配置原厂文件

通过获准使用的原件准备平铺的 ARM64 `.so` 装载闭包、匹配的鸿蒙 musl 加载器以及 `resources/resfile`。原始 HAP/HSP、版本和关键 SHA-256 见 [技术方案](technical-proposal.md) 与 [ABI 配置](../corevision-android/src/main/assets/corevision/profile.sha256)。

```sh
python3 tools/runtime_bundle.py prepare \
  --loader "$MUSL_LOADER" --libs "$ARM64_LIBS" --models "$RESFILE" \
  --output "$COREVISION_RUNTIME_DIR"
python3 tools/runtime_bundle.py verify "$COREVISION_RUNTIME_DIR"
```

`COREVISION_RUNTIME_DIR` 取仓库以外的新目录。已有审计阶段的加载器可填写 `image-musl-loader`，库目录可用已经平铺的 `libs/`。musl 本身提供 `libc.so`，把这个文件从平铺目录中省略。模型侧使用三个 `.ms` 和三个 `.bin`。脚本生成宿主 `jniLibs`、`assets` 和全量哈希清单。应用验证已安装 ELF 和模型哈希，将模型放进私有目录，再启动 worker。原厂文件和本地验收输出由接入方留存。

`--models` 指向同版本 `coBackboneService` 的 `resources/resfile`。可按需要组合以下参数。

| 能力 | `prepare` 参数与来源 |
| --- | --- |
| 物体检测 | `--include-objects` 加入 `head_detect_640.ms` |
| 人脸检测 | `--include-face` 加入检测头、关键点、姿态、人脸概率和遮挡模型 |
| 人脸比对 | `--face-comparison-model "$FACE_COMPARISON_MODEL"` 传入 `clusterService` 的 `cv_faceClusterModel_01.ms`，同时启用人脸检测 |
| 骨骼检测 | `--include-skeleton` 加入骨骼模型及其依赖的共享检测模型 |
| 图像超分 | `--super-resolution-models "$SR_RESFILE"` 指向 `imageSRService/resources/resfile`，并把该 HAP 的 `libimagesuperresolution.so` 和匹配的 `libimage_source_ndk.z.so` 加入 `--libs` |
| 文字识别 | `--text-recognition-models "$OCR_RAWFILE"` 指向 `textService/resources/rawfile`，并把同一 HAP 的 `liblite_common_ocr.so`、`libtext-clustering.so` 加入 `--libs` |
| 以文搜图 | `--image-search-service "$LABEL_SERVICE"` 指向解包后的 `labelService` 根目录。脚本从 `resources/resfile/image_retrieval_text.ms` 与 `libs/arm64-v8a/liblabel_detect.so` 取得文本模型和词表，并从 `--models` 取得两个视觉检测头 |

各能力的版本以 [SHA-256 清单](../corevision-android/src/main/assets/corevision/) 为准。以文搜图词表对应 `labelService` 的旧版 256 维模型。脚本校验词表来源及提取结果。[原厂组件与调用链](technical-proposal.md)

```sh
python3 tools/runtime_bundle.py prepare \
  --loader "$MUSL_LOADER" --libs "$ARM64_LIBS" --models "$RESFILE" \
  --include-skeleton --face-comparison-model "$FACE_COMPARISON_MODEL" \
  --super-resolution-models "$SR_RESFILE" --text-recognition-models "$OCR_RAWFILE" \
  --image-search-service "$LABEL_SERVICE" --output "$COREVISION_RUNTIME_DIR"
```

## 调用 API

```kotlin
val coreVision = CoreVision(context)
coreVision.segment(bitmap, true).whenComplete { result, error ->
    if (error == null) {
        runOnUiThread { imageView.setImageBitmap(result.foreground) }
    }
}
```

- `segment(bitmap)` 返回合并 alpha、主体数和边界。`segment(bitmap, true)` 同时生成前景 `Bitmap`。
- `result.alpha` 按行紧密排列，每像素一个字节。Java 和 Kotlin 读取无符号值时使用 `alpha[index].toInt() and 0xff` 或 `Byte.toUnsignedInt(alpha[index])`。
- 输出前景的 alpha 等于 302 蒙版，RGB 由原图的 sRGB 通道乘以浮点覆盖率得到。两种输出的像素尺寸均与输入一致。
- 输入在后台转成紧密 RGBA。调用期间保持 `Bitmap` 可读。输入限制为单边 8192 像素且总像素不超过 12,582,912。
- `CoreVision` 可在连续请求间复用。会话内串行执行，允许一个排队请求。用完调用 `close()` 取消待处理请求并通知服务终止活动 worker。
- `CompletableFuture` 通过 `CoreVisionException` 报告失败。在 `get()` 或 `join()` 的异常原因中读取 `code`，可识别版本不匹配、运行时缺失、设备不支持、超时、CPU 配置失败及场景失败。

### 主体明细

```kotlin
val options = CoreVision.SegmentationOptions(6, true, true)
coreVision.segment(bitmap, options).thenAccept { result ->
    val bounds = result.subjectBounds
    for (subject in result.subjectDetails) {
        val boundsOfSubject = subject.bounds
        val mask = subject.alpha
    }
}
```

- `maxCount` 接受 `1–20`。普通重载默认 `6`，主体明细默认关闭。上限影响计数和明细，合并蒙版保留完整的 302 输出。
- 单主体按 302 蒙版中 alpha `>128` 的八连通区域面积降序排列。面积小于原生蒙版 `0.1%` 或少于四个像素的区域会被忽略。相连主体会合并为同一区域，纯色图的主体数为零。
- 边界使用输入图像像素坐标，以 `left`、`top`、`width`、`height` 表示。存在主体时，合并边界依据非零 alpha 计算。无主体时边界为空。
- `Subject.getAlpha()` 和 `getForeground()` 按需创建原图尺寸的单主体输出。关闭前景选项时返回 `null` 前景。主体明细的区域划分由适配层完成。

### 人脸检测

人脸检测使用当前源码构建的 AAR。`detectFaces()` 复用同一会话、尺寸限制和异步队列，返回只读列表。

```kotlin
coreVision.detectFaces(bitmap).thenAccept { faces ->
    for (face in faces) {
        val bounds = face.bounds()
        val points = face.landmarks()
        val pose = face.pose()
    }
}
```

- `bounds()` 是输入 `Bitmap` 像素坐标系中的 `left`、`top`、`right`、`bottom`。右边界和下边界是矩形外边缘。多张人脸按可见框面积降序排列。
- `landmarks()` 依次给出左眼、右眼、鼻尖、左嘴角和右嘴角。`pose()` 返回以度为单位的 `yaw`、`pitch`、`roll`。`occluded()` 返回原厂遮挡检测结果。
- `probability()` 是原厂模型输出的人脸概率，范围为 `0–1`。没有检测到人脸时返回空列表。
- Android 的人脸场景始终返回遮挡字段。鸿蒙在初始化时提供独立的 `faceBlock` 开关。
- 仅打包抠图模型时，`detectFaces()` 返回 `RUNTIME_MISSING`。模型哈希与清单不一致时返回 `RUNTIME_INCOMPATIBLE`。原厂人脸场景失败时返回 `FACE_DETECTION`、`CPU_CONFIGURATION` 或 `TIMEOUT` 等对应错误。

### 双图人脸比对

双图比对使用当前源码构建的 AAR 和比对模型。两张图可以使用不同尺寸，且各须检测到一张人脸。`similarity()` 是 `0–1` 的相似度，达到 `0.62` 时 `samePerson()` 为 `true`。示例依次选取两张图并显示相似度和判定。

```kotlin
coreVision.compareFaces(firstBitmap, secondBitmap).thenAccept { result ->
    val similarity = result.similarity()
    val samePerson = result.samePerson()
}
```

- 比对复用会话的串行队列，每张图分别运行一次工作进程。原厂模型的特征只在请求内用于计算相似度。
- 任一图片检测到零张或多张人脸时返回 `INVALID_INPUT`。缺少比对模型时返回 `RUNTIME_MISSING`，特征推理失败时返回 `FACE_COMPARISON`。

### 物体、骨骼、OCR 和超分

| Android 方法 | 输出 |
| --- | --- |
| `detectObjects(bitmap)` | 输入图像像素坐标中的外接框、`0–1` 得分、类别和从零开始的图内 ID。所用场景每个物体返回一个类别，`labels()` 返回单元素列表 |
| `detectSkeletons(bitmap)` | 骨骼框、得分及 17 个按 `SkeletonPointType` 排列的关键点，关键点包含坐标和得分 |
| `recognizeText(bitmap, detectDirection)` | 原厂识别文本、块、行、词及四角坐标。省略第二个参数时开启方向检测。`getSupportedTextLanguages()` 返回该模型声明的语言列表 |
| `superResolve(bitmap)` | 宽高各为输入 4 倍的 `ARGB_8888` 位图，输出 alpha 为 `255`。输入单边最多 `2048` 像素且总像素最多 `786,432` |

检测无结果时返回空列表，OCR 无结果时返回空字符串和空块列表。模型未打包时，对应推理调用报告 `RUNTIME_MISSING`。

### 以文搜图

```kotlin
coreVision.insertImage("photo-123", "Gallery", bitmap)
    .thenCompose { coreVision.searchImages("宇航员", "Gallery", 10) }
    .thenAccept { results ->
        for (match in results) {
            val id = match.imageId()
            val similarity = match.similarity()
        }
    }
```

- `insertImage(imageId, scope, bitmap)` 使用宿主提供的图片 ID。宿主保留 ID 与图片 URI 或路径的映射。相同 `(scope, imageId)` 再次写入时覆盖向量。
- `scope` 接受 `1–32` 位 ASCII 字母或数字，ID 长度为 `1–128`，查询长度为 `1–100`。`topKey` 为 `0–100`，默认 `100`。
- `searchImages()` 在当前分区内按余弦相似度降序返回最多 `topKey` 条记录，包括低相似度条目。相似度范围是 `[-1, 1]`，词表以中文为主。
- 索引在同一个应用的会话之间持久化，保存在应用私有目录，记录分区、ID 和归一化特征。`deleteImage()` 只移除指定索引项。`clearImageSearch()` 清空应用的所有索引分区。

## 构建及复测

源码构建使用 JDK 17、Android SDK 35、Build Tools 35.0.0、NDK `27.2.12479018`、OpenHarmony native SDK 的 Clang 和 sysroot。`OHOS_NATIVE_SDK` 指向含 `llvm/` 和 `sysroot/` 的目录。只消费发布包时可直接使用已编译的 AAR。

```sh
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018'
./gradlew :mavenZip :corevision-android:lintRelease :sample:lintDebug
python3 tools/runtime_bundle.py verify-aar corevision-android/build/outputs/aar/corevision-android-release.aar
```

Maven 目录生成在 `build/maven`，发布 ZIP 生成在 `build/distributions`。`sample` 默认消费构建输出的 release AAR 文件，也可使用 `-PcorevisionUseMaven=true` 验证发布坐标。以下命令需要 `mavenZip` 已完成，并通过 `ANDROID_SERIAL` 指定要复测的 Android 设备。

首次使用无线调试或设备拒绝电脑的证书时，在手机「开发者选项 → 无线调试 → 使用配对码配对设备」页面打开配对窗口。用 `adb pair "$ADB_PAIR_ENDPOINT"` 连接配对窗口显示的地址，在终端提示时输入六位配对码。

配对完成后，把无线调试主页显示的 IP 地址和端口设为 `ANDROID_SERIAL`，运行 `adb connect "$ANDROID_SERIAL"`。无线调试服务重新启动后可能分配新端口。连接后运行 `adb -s "$ANDROID_SERIAL" get-state`，确认返回 `device` 再开始验收。

```sh
./gradlew -PcorevisionUseMaven=true :sample:assembleDebug :sample:assembleDebugAndroidTest
python3 tools/device_acceptance.py

./gradlew -PcorevisionUseMaven=true -PcorevisionRuntimeDir="$COREVISION_RUNTIME_DIR" \
  :sample:verifyRuntimeApk :sample:assembleDebugAndroidTest
python3 tools/device_acceptance.py --baseline "$HARMONY_BASELINE_DIR"
```

若已有同签名的本地验收 APK，可给脚本传入 `--apk <sample-apk> --test-apk <instrumentation-apk>` 直接复测。

验收人脸检测时使用通过 `--include-face` 生成的运行时。将本地测试图片转换为按行紧密排列的 RGBA，并按实际图片填写尺寸和预期张数。插桩还会用一张纯色图验证空结果。

```sh
ffmpeg -i "$FACE_IMAGE" -frames:v 1 -pix_fmt rgba -f rawvideo /tmp/corevision-face.rgba
python3 tools/device_acceptance.py --face-input /tmp/corevision-face.rgba \
  --width "$FACE_WIDTH" --height "$FACE_HEIGHT" --face-count "$FACE_COUNT"
```

使用仅含抠图模型的 APK 时，运行 `python3 tools/device_acceptance.py --expect-face-models-missing` 检查所有可选推理接口的模型缺失行为。

验证比对时使用包含特征模型的 APK。验收脚本接收两张等尺寸的 RGBA 样本，并额外用一张纯色图检查错误路径。对同一人和不同人各运行一次，分别指定 `--expect-match yes` 和 `--expect-match no`。

```sh
python3 tools/device_acceptance.py --compare-input "$FIRST_RGBA" "$SECOND_RGBA" \
  --width "$FACE_WIDTH" --height "$FACE_HEIGHT" --expect-match yes
```

新增能力可使用以下参数复测。每个命令的宽高按输入测试图填写。搜索用例需要同尺寸的宇航员和猫照片。

```sh
python3 tools/device_acceptance.py --subject-input "$MULTI_SUBJECT_RGBA" --subject-count 2 --width 1536 --height 1024
python3 tools/device_acceptance.py --object-input "$ASTRONAUT_RGBA" --object-label 13 --width 512 --height 512
python3 tools/device_acceptance.py --skeleton-input "$ASTRONAUT_RGBA" --skeleton-count 1 --width 512 --height 512
python3 tools/device_acceptance.py --ocr-input "$TEXT_RGBA" --expected-text 'CORE VISION' --width 640 --height 360
python3 tools/device_acceptance.py --super-resolution-input "$TEXTURED_RGBA" --width 128 --height 128
python3 tools/device_acceptance.py --search-input "$ASTRONAUT_RGBA" "$CAT_RGBA" --width 512 --height 512
```

两份自研 ELF 的 LOAD 段采用 16 KiB 对齐，原厂 ELF 使用 4 KiB 对齐。部分 Android 系统会在打开含原厂文件的调试 APK 时提示兼容性。以 `adb -s "$ANDROID_SERIAL" shell getconf PAGE_SIZE` 的实际页大小为准，返回 `4096` 时可点「确定」继续测试。手动测试可用相同的 `corevisionRuntimeDir` 运行 `:sample:assembleRelease`，再给生成的 release APK 签名并安装。

使用 USB ADB 复测时可以关闭设备 Wi-Fi 和移动数据。基线目录使用 `input.rgba`、`mask.i32le` 和 `foreground.rgba`。默认尺寸为 `1536 × 1024`，也可用 `--width`、`--height` 指定。基线验收记录实际映射的库及使用的模型 SHA-256、`MS_CPU` 配置次数、场景分数、时延和工作进程峰值 RSS。脚本在仓库外保存输出和日志。单主体基线以 alpha `>128` 计算 IoU，阈值为 301 `≥0.985`、302 `≥0.995`，预乘前景 RGBA 的 MAE 须 `≤2.0`。

可用 debug 宿主分别复测进程中止和 45 秒超时。复测时保持设备解锁，插桩会将示例界面置于前台。两项命令均检查故障进程回收并在失败后发起新的完整推理。

```sh
python3 tools/device_acceptance.py --baseline "$HARMONY_BASELINE_DIR" --worker-fault kill
python3 tools/device_acceptance.py --baseline "$HARMONY_BASELINE_DIR" --worker-fault stop
```

## 已测结果

2026-11-25，未 root 的 Android API 37 真机和 API 35 模拟器均在 ARM64、4 KiB 页、普通应用 UID、SELinux Enforcing 的条件下验收。真机使用无线 ADB，模拟器验收时关闭 Wi-Fi 和移动数据。`sample` 从本地 Maven 获取发布 AAR，应用清单未声明网络权限。

| 验证项 | 真机 | 模拟器 |
| --- | --- | --- |
| 安装路径执行及 Binder、管道 IPC | 通过 | 通过 |
| 301 与鸿蒙 `mattingList` 的 IoU | `0.99166` | `0.99166` |
| 302 与鸿蒙 `mattingList` 的 IoU | `0.99700` | `0.99700` |
| 预乘前景 RGBA MAE | `0.669` | `0.669` |
| 两次请求返回的 alpha | 字节一致 | 字节一致 |
| 本轮两次 AAR 端到端耗时 | `3.40 / 3.16 s` | `2.52 / 2.60 s` |
| 首次工作进程峰值 RSS | 约 `473 MiB` | 约 `484 MiB` |

真机的加载审计逐项核对了 226 个原厂 ELF 和 6 个模型文件的 SHA-256，三个模型均配置为 `MS_CPU`。模拟器的边界样例中，301 在双主体图上检出两个连通域，302 在纯渐变图上无 `>128` 像素，无效输入返回 `INVALID_INPUT`。

真机通过工作进程中止、暂停至 45 秒超时和随后的基线推理。早期一次无界面的暂停测试被系统回收，启动示例界面的插桩复测通过。断网模拟器也通过暂停超时后的推理恢复。已签名的非调试样例通过真机选图和抠图手测。16 KiB 页适配需单独校验全套原厂 ELF。

### 人脸能力验收

断网的 API 35 ARM64 模拟器使用本轮构建的本地 Maven AAR，通过了单脸、双脸和纯色图检测用例。双图比对中，同一照片的变换样本相似度为 `0.99631`，不同人照片为 `0.51802`。示例通过了不同尺寸照片的选图和双图预览，无脸或多脸的比对输入返回 `INVALID_INPUT`，仅打包抠图模型时两个人脸接口返回 `RUNTIME_MISSING`。

包含全部人脸模型的 APK 重跑同一抠图基线，301 和 302 的 IoU 分别为 `0.99166` 和 `0.99700`，预乘前景 RGBA MAE 为 `0.66908`。

### 全量离线验收

使用本地 Maven 发布包和全量运行时，在断网的 Android API 35 ARM64 模拟器完成以下验收。合并抠图基线的 301 与 302 IoU 分别为 `0.99166` 和 `0.99700`。

| 能力 | 验收用例 |
| --- | --- |
| 主体分割 | 双主体面积排序，独立的蒙版和前景，`maxCount=1` 时合并蒙版一致，纯色图为零主体 |
| 物体和骨骼检测 | 宇航员图识别出人物类别和带 17 个关键点的骨骼，纯色图返回空结果 |
| 文字识别 | 普通与倒置文字、含多行的文字块、行和词坐标、空白图 |
| 图像超分 | `128 × 128 → 512 × 512`，`17 × 9 → 68 × 36`，输出 alpha 为 `255` |
| 以文搜图 | 「宇航员」「猫咪」各自排对，另测 `topKey`、分区隔离、会话间持久化、删除和清空 |

以上新增能力及主体明细的结果来自 Android 插桩。鸿蒙侧已对照的基线为原有的合并蒙版和前景图。
