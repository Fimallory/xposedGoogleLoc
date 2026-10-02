# xposedGoogleLoc

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
![Xposed API](https://img.shields.io/badge/Xposed%20API-102-informational)
![minSdk](https://img.shields.io/badge/minSdk-26-green)

一个基于 **LibXposed API 102** 的 Xposed / LSPosed 模块，只让 **Google 系应用**在特定的位置上报路径上使用伪造的 WGS-84 坐标，而不影响其他应用通过 GMS Fused 拿到真实位置。

> 本项目需要 Root + LSPosed（或支持 LibXposed API 102 的框架）。普通设备无法使用。

---

## 为什么需要它

很多"虚拟定位"模块会全局 Hook `android.location.Location` 的 getter 或 GMS 的 Fused 交付路径，副作用是**所有**应用都会收到假坐标——包括打车、外卖、签到类应用，容易被检测或产生实际影响。

本模块刻意采用**最小侵入**策略：

- **消费者进程**（Chrome / Maps / YouTube / Play / Photos / Gmail / Google App）：只 Hook `Location` 的呈现 getter，让界面显示假坐标。
- **GMS 进程**（`com.google.android.gms`）：**只** Hook 语义化的上报组件（Location Sharing Reporter、Periodic Reporting、Location Tracking、ULR、FMD Spot、MDM），把它们**参数里**的 `Location` 换成新对象。
- **绝不**Hook GMS 的 Fused 生产/交付路径，也**绝不**Hook `system_server`。因此第三方应用通过 `FusedLocationProviderClient` 拿到的仍是**真实**位置。

---

## 工作原理

```
┌──────────────────────────────────────────────────────────────┐
│  Google 消费者进程 (Chrome / Maps / YouTube / ...)            │
│  Hook: Location.getLatitude/getLongitude/getAltitude/        │
│        getAccuracy, isFromMockProvider, isMock               │
│  → 界面呈现假坐标，且不暴露 mock 标记                          │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  GMS 进程 (com.google.android.gms)                            │
│  仅 Hook 上报组件参数中的 Location 对象：                       │
│   • LocationReportingIntentOperation                          │
│   • PeriodicLocationReportingIntentOperation                  │
│   • LocationTrackingIntentOperation                           │
│   • ULR (混淆类 hbps)                                          │
│   • Spot / LocationAssigningIntentOperation                   │
│   • MDM LocateChimeraService 等（默认关闭）                     │
│  → 替换为全新 Location 对象，绝不修改源对象                      │
│                                                               │
│  Fused 生产/交付路径：不 Hook → 第三方应用得到真实位置            │
└──────────────────────────────────────────────────────────────┘
```

伪造对象是 `Location(provider)` 新建的，**不**使用拷贝构造函数，避免继承源对象的 mock 标记；同时复制 `time` / `elapsedRealtimeNanos` / 速度 / 航向 / 精度与 `extras`，保证下游解析正常。

---

## 支持的包（scope.list）

| 包名 | 模式 |
|---|---|
| `com.google.android.gms` | 仅语义上报 Hook |
| `com.android.chrome` | 消费者呈现 Hook |
| `com.android.vending` | 消费者呈现 Hook |
| `com.google.android.apps.maps` | 消费者呈现 Hook |
| `com.google.android.googlequicksearchbox` | 消费者呈现 Hook |
| `com.google.android.gm` | 消费者呈现 Hook |
| `com.google.android.youtube` | 消费者呈现 Hook |
| `com.google.android.apps.photos` | 消费者呈现 Hook |

`module.prop` 中 `staticScope=false`，你可以在 LSPosed 管理器里自行增删作用域；代码内另有一层 `TARGETS` 白名单做二次校验。

---

## 环境要求

| 项目 | 版本 |
|---|---|
| Android | 8.0+（minSdk 26） |
| 框架 | LSPosed 或支持 LibXposed API 102 的框架 |
| 构建 JDK | 17 |
| 构建 Gradle | 8.13（随 wrapper 提供） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 37 |
| Xposed API | `io.github.libxposed:api:102.0.0` |

> 说明：LibXposed `service` 依赖的 AAR 元数据要求 `compileSdk ≥ 37`，而 AGP 8.13.2 官方测试上限为 36.1，因此工程里通过 `android.suppressUnsupportedCompileSdk=37` 抑制了该提示。

---

## 构建

```bash
git clone https://github.com/Fimall/xposedGoogleLoc.git
cd xposedGoogleLoc

# 需要 Android SDK Platform 37 与 Build-Tools 37.0.0
# AGP 查找的是 platforms/android-37；若 SDK 里只有 android-37.2，
# 在 Linux/macOS 下建立软链接：
#   ln -s "$ANDROID_SDK_ROOT/platforms/android-37.2" "$ANDROID_SDK_ROOT/platforms/android-37"
# Windows 下：
#   mklink /J %ANDROID_SDK_ROOT%\platforms\android-37 %ANDROID_SDK_ROOT%\platforms\android-37.2

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

仓库根目录的 `local.properties` 请自行填写 `sdk.dir`，该文件已被 `.gitignore` 忽略。

发布版可执行 `./gradlew :app:assembleRelease`，签名请在本地配置（**不要**把 keystore 或密码提交到仓库）。

---

## 安装

1. 下载或自行构建 APK。
2. 安装到已启用 LSPosed 的设备。
3. 在 LSPosed 管理器中启用本模块，勾选需要生效的 Google 应用（默认已给出 8 个）。
4. 强制停止目标应用（或重启设备）以让 Hook 生效。

---

## 使用

打开模块 App：

- **启用虚拟定位**：总开关。关闭时所有路径均使用真实位置。
- **允许 MDM / 查找我的设备上报**：默认关闭。开启后 MDM / "查找我的设备"上报路径也使用虚拟坐标。
- **纬度 / 经度 / 海拔 / 精度**：填入 WGS-84 坐标，点击保存。

配置通过框架的 RemotePreferences 保存，被 Hook 的进程只读、UI 进程可写，改动即时生效，无需重启应用。

---

## 已知限制

- **依赖 GMS 内部类名**：语义 Hook 针对的 GMS 混淆类名（如 ULR 的 `hbps`）会随 GMS 版本变化，升级 GMS 后可能失效。主路径走稳定类名，混淆类另有 shape 匹配回退。
- **Fused 路径保持真实**是有意为之：若你的使用场景需要影响第三方应用，本模块**不适用**。
- 仅覆盖上表所列的 Google 包，不覆盖 `system_server`。

---

## 免责声明

本项目仅供学习、研究与个人设备测试使用。使用者需自行承担因使用本模块产生的一切后果与法律责任，包括但不限于违反相关服务条款或当地法律法规。请勿用于任何非法用途。

---

## 许可证

[GNU General Public License v3.0](LICENSE) © 2026 Fimall
