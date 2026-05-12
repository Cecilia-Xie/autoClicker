# AutoClicker (基础版)

这是一个最基础可打包的 Android 连点器项目骨架，目标是：

- 先稳定产出 APK（本地或 GitHub Actions）
- 后续再逐步加入真正的连点逻辑（如无障碍服务、悬浮窗、点击参数配置等）

## 当前已实现

- Android 原生 Kotlin 工程（`app` 模块）
- 简单主界面（开始/停止按钮 + 状态文本）
- GitHub Actions 自动构建 Debug APK

> 说明：当前按钮仅用于 UI 演示，不包含真实自动点击能力。

## 项目结构

```text
.
├─ .github/workflows/build-apk.yml
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/...
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradle.properties
```

## 云端构建 APK（GitHub Actions）

1. 将仓库推送到 GitHub。
2. 触发方式：
   - 推送到 `main` 或 `master`
   - 或创建/更新 Pull Request
3. 进入 GitHub 仓库的 **Actions** 页面，打开 `Build Android APK` 工作流。
4. 构建完成后，在该次运行的 **Artifacts** 下载 `app-debug-apk`。

## 本地构建（可选）

如果你本地安装了 Android SDK 和 Gradle 8.7+，可以在根目录执行：

```bash
gradle assembleDebug
```

生成路径：

`app/build/outputs/apk/debug/app-debug.apk`

## 下一步建议

- 添加无障碍服务（AccessibilityService）实现真正点击能力
- 添加悬浮窗 + 坐标录制
- 增加点击间隔、点击次数、循环策略等配置
- 引入签名配置，输出 release APK
