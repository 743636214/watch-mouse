# WatchMouse - 手表触控板鼠标

把小米手表 Watch 5 变成手机的无线触控板！通过蓝牙连接，用手表手势操控手机鼠标。

完整的构建、安装、授权、连接和故障排查步骤见 [使用与部署指南](使用与部署指南.md)。

## 功能特性

- 🖱️ **单指滑动** - 控制鼠标指针移动
- 👆 **单指点击** - 鼠标左键单击
- 🖐️ **双指滑动** - 页面上下滚动（类似 Mac 触控板）
- 👈 **左右滑** - 模拟手机返回键
- 🏠 **Home 键** - 一键回到桌面
- 🎯 **悬浮光标** - 手机屏幕显示可移动的鼠标指针

## 项目结构

```text
watch-mouse/
├── watch-android-app/      # Watch 5 HyperOS 手表端原生 Android APK（独立 Android Studio 项目）
└── phone-app/              # 手机端 Android APK（BLE GATT Server、无障碍操作、悬浮光标）
```
## 技术架构

### 通信协议

采用 **BLE（低功耗蓝牙）** 直连方案：
- **手表端**：作为 GATT Client，扫描并连接手机
- **手机端**：作为 GATT Server，广播并等待连接

**UUID 定义：**
- Service UUID: `0000ffe0-0000-1000-8000-00805f9b34fb`
- Characteristic UUID: `0000ffe1-0000-1000-8000-00805f9b34fb`

**数据格式（JSON）：**
```json
// 鼠标移动（相对位移像素）
{ "type": "move", "dx": 10, "dy": -5 }

// 鼠标单击
{ "type": "click", "button": "left" }

// 滚轮滚动
{ "type": "scroll", "dx": 0, "dy": 20 }

// 返回键
{ "type": "back" }

// Home 键
{ "type": "home" }
```

### 手势识别算法

手表端通过 `touchstart` / `touchmove` / `touchend` 事件识别手势：

| 手势 | 判定条件 | 对应操作 |
|------|---------|---------|
| 单指移动 | 1 指触摸 + 移动距离 > 2px | 鼠标相对移动 |
| 单指点击 | 1 指 + 时长 < 250ms + 位移 < 10px | 鼠标左键单击 |
| 双指移动 | 2 指触摸 + 移动 | 页面滚动 |
| 左右滑 | 1 指 + 水平位移 > 50px + 时长 < 300ms | 返回键 |

### Android 端实现原理

- **悬浮光标**：通过 `WindowManager` 添加悬浮窗，显示一个可移动的指针图标
- **模拟点击/滑动**：通过 `AccessibilityService.dispatchGesture()` 分发触摸手势
- **模拟系统按键**：通过 `AccessibilityService.performGlobalAction()` 执行 BACK/HOME

---

## 环境准备

### 手表端开发环境（Watch 5 HyperOS）

手表端是单独的原生 Android 项目，位于 `watch-android-app/`，用 Android Studio 独立打开。构建并通过 ADB 安装 APK；不使用 Vela AIoT-IDE 或 `.rpk`。
### 手机端开发环境

1. **Android Studio**：下载最新版 Android Studio
2. **JDK 17+**：Android Studio 自带
3. **Android SDK**：API 34+

---

## 快速开始

### 第一步：构建并安装手机端 App

1. 用 Android Studio 打开 `phone-app/` 目录
2. 等待 Gradle 同步完成
3. 连接 Android 手机（开启 USB 调试）
4. 点击 Run 按钮安装应用

或者使用命令行：
```bash
cd phone-app
./gradlew assembleDebug
# APK 路径: app/build/outputs/apk/debug/app-debug.apk
```

### 第二步：手机端配置权限

打开 WatchMouse 应用，按顺序授予以下权限：

1. **蓝牙权限** - 点击「请求必要权限」
2. **悬浮窗权限** - 点击「开启悬浮窗权限」，允许在其他应用上层显示
3. **无障碍服务** - 点击「开启无障碍服务」，找到 WatchMouse 并开启

> ⚠️ **重要**：无障碍服务是模拟点击/返回的核心，必须开启！

### 第三步：构建手表端 APK

1. 在 Android Studio 中单独打开 `watch-android-app/`。
2. 同步 Gradle 项目，运行 `app` 配置，或选择 **Build > Build APK(s)**。
3. APK 输出路径：`watch-android-app/app/build/outputs/apk/debug/app-debug.apk`。
### 第四步：通过无线 ADB 安装手表端 APK

1. 手表和电脑连接同一 Wi-Fi，在手表开发者选项开启 **ADB 调试**与**无线调试**。
2. 如果手表提供“配对新设备”和配对码，先运行 `adb pair <手表IP>:<配对端口>` 并输入配对码。
3. 使用无线调试页面显示的连接端口运行 `adb connect <手表IP>:<连接端口>`；如果页面直接显示 `IP:5555`，就按该地址连接。
4. `adb devices` 确认手表在线后安装：

   `adb -s <手表IP>:<连接端口> install -r watch-android-app/app/build/outputs/apk/debug/app-debug.apk`
---

## 使用说明

### 连接步骤

1. 确保手机蓝牙已开启
2. 打开手机端 WatchMouse App，点击「开启服务」
3. 手表上打开 WatchMouse 应用
4. 点击「连接手机」按钮
5. 在设备列表中选择你的手机（名称以 WatchMouse 开头）
6. 连接成功后手表界面显示「触控板已就绪」
7. 手机屏幕上会出现粉色的鼠标指针

### 手势操作

| 手表手势 | 手机操作 |
|---------|---------|
| 单指滑动 | 鼠标指针跟随移动 |
| 单指轻点 | 鼠标左键点击（在指针位置） |
| 双指上下滑 | 页面上下滚动 |
| 双指左右滑 | 页面左右滚动 |
| 快速左滑/右滑 | 返回上一页 |
| 点击底部 Home 按钮 | 回到手机桌面 |

---

## 常见问题

### Q: 手表扫描不到手机？
A: 请检查：
- 手机蓝牙是否已开启
- 手机端服务是否已启动（通知栏有 WatchMouse 通知）
- 是否授予了蓝牙权限
- 手机是否支持 BLE 外设模式（大部分 Android 5.0+ 支持）

### Q: 连接后没反应？
A: 请检查：
- 无障碍服务是否已开启
- 悬浮窗权限是否已授予
- 手机端是否有安全软件拦截了无障碍服务

### Q: 鼠标移动不流畅？
A: BLE 有一定延迟（约 50-100ms），属正常现象。可以尝试：
- 减小手表和手机的距离
- 减少周围蓝牙设备干扰
- 调整灵敏度（修改 gesture.js 中的 CONFIG）

### Q: ADB 找不到手表或安装失败？
A: 确认手表已开启无线调试、电脑与手表位于同一 Wi-Fi，且使用无线调试页面显示的连接端口。手表固件必须允许 ADB 安装 APK。
---

## 开发调试

### 手表端调试

用 Android Studio 单独打开 `watch-android-app/`，通过 ADB 连接手表并运行 `app` 配置。
### 手机端调试

1. 使用 Android Studio 连接手机直接调试
2. 查看日志：
   ```bash
   adb logcat | grep -i watchmouse
   ```

### 修改灵敏度

手表端手势灵敏度在 `watch-android-app/app/src/main/java/com/watchmouse/watch/WatchMouseActivity.kt` 的 `handleTouch` 中调整。

---

## 注意事项

1. **电池续航**：持续使用 BLE 和屏幕会增加手表功耗，建议需要时再开启
2. **延迟问题**：BLE 无线传输存在约 50-100ms 延迟，不适合高速操作
3. **误触问题**：手表屏幕较小，容易误触，使用时注意手势准确
4. **安全提醒**：无障碍服务权限较高，请仅在信任的应用中开启
5. **系统兼容性**：手机端要求 Android 7.0+（支持 dispatchGesture）

---

## License

MIT License
