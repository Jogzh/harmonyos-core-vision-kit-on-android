# HarmonyOS Core Vision Kit on Android

本项目探索在 Android 设备上本地运行华为原始 HarmonyOS Core Vision Kit 引擎和模型。

`dev.corevision:corevision-android:0.1.0` 提供异步 `segment(Bitmap)`，返回原尺寸 8 位 alpha 和可选的预乘前景图。宿主通过 Gradle 加入获准使用的原厂运行时和模型，工作进程在本地执行 301 主体分割及 302 精细抠图。

ARM64、4 KiB 页的未 root Android 真机与模拟器均通过普通应用 UID 和 SELinux Enforcing 下的安装路径探针及 AAR 基线验收。301 和 302 的 IoU 分别为 `0.99166` 和 `0.99700`（阈值 `>128`）。

- [Android Gradle 接入和复测](docs/android-integration.md)
- [在 Android 上运行华为原始 Core Vision Kit 的技术方案](docs/technical-proposal.md)
