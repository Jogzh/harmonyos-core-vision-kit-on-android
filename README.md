# HarmonyOS Core Vision Kit on Android

本项目探索在 Android 设备上本地运行华为原始 HarmonyOS Core Vision Kit 引擎和模型。

首轮已在未 root 的 Android 真机上跑通原厂主体分割和精细抠图，抠图 alpha 与鸿蒙真机基线的前景 IoU 为 `0.99700`（阈值 `>128`）。当前实测宿主是 ADB shell 启动的独立进程，普通 APK 集成是下一阶段。

[在 Android 上运行华为原始 Core Vision Kit 的技术方案](docs/technical-proposal.md)
