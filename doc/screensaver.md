# 息屏挂机 / Dim screen automation

## 中文

息屏挂机在后台模式下显示黑色遮罩挡触摸，再请特权进程把物理屏面板的电源模式设为
`POWER_MODE_OFF`。这是**面板级关屏**：系统仍认为屏幕亮着，因此不进 Doze、不冻结进程、
不打断虚拟屏采集，任务照常运行，而面板真的不再发光。面板由用户拖动滑块退出时恢复点亮。
特权连接不可用、或 ROM 拿不到 display token（Android 14 以下走 `SurfaceControl` 隐藏
方法，14 及以上走 services.jar 的 `DisplayControl`）时，退化为「黑遮罩 + 保持点亮」，
用户看到的仍是黑屏，只是没有真正省电。窗口亮度保留 `0.01`，仅用于面板恢复瞬间不刺眼。
独立全屏宿主隐藏状态栏和导航栏，黑色背景铺满屏幕；边缘系统手势可暂时唤出系统栏。
全屏宿主离开前台时同步撤下遮罩并恢复面板，避免导航栏恢复后继续常亮或留在黑屏。

### 使用

1. 在主页点击「息屏挂机」，立即显示遮罩。首次使用需要授予悬浮窗权限，再点击一次入口。
2. 在「设置 → 显示」中开启「任务启动时自动进入」，下一次启动调度器或工具时自动显示遮罩。
3. 从滑块开始向右拖到末端，松手解锁。单击、从轨道其他位置拖动、短距离快划均不会解锁。
   TalkBack 用户可以通过自定义「解锁」操作退出。

遮罩显示本地时间、日期、电量，以及正在运行实例的任务图标和名称。
运行实例与正在浏览的实例不同时，优先使用运行实例的数据；没有任务数据时显示运行、
等待或连接状态。调度器、工具或运行环境停止时自动退出。
任务状态请求失败时保留上次已知状态；WebUI 可达不能单独证明任务已经停止。

### 防烧屏与布局

- 时钟独立刷新，在分钟边界更新，并遵循系统的 12/24 小时设置。
- 时间、任务卡和解锁条作为整体，无交互 30 秒后开始上下移动防烧屏，之后每 10 秒移动一步（16dp），到达边界折返。
- 漂移范围由内容和可用纵向空间的实际尺寸决定，最大 96dp，避开屏幕缺口与安全区。
- 触摸或回弹期间暂停；结束后重新等待完整 30 秒，避免滑块在手指下移动。
- 任务卡、图标底座、解锁轨道与滑块均为黑底细边框，减少大面积常亮色块。
- 矮屏与大字体允许内容滚动，保留解锁入口。
- 使用现有 MD3 暗色主题、动态色、字阶和容器形状；黑色背景保持固定，无持续循环动画。

### 实现位置

`ScreenSaverOverlayManager` 管理悬浮窗口、运行状态观察、窗口亮度，并在遮罩显隐时
通过 `RemoteService.setDisplayPower` 请求关/开面板。
`third/wrappers/DisplayPower` 解析物理屏 token 并调 `SurfaceControl.setDisplayPowerMode`；
Android 14+ 的 token 由 `third/wrappers/ServerDisplayControl` 从 services.jar 的
`com.android.server.display.DisplayControl` 取得。远程访问的「上锁息屏」复用同一套面板
关屏（`WakeUnlockController.lockAndSleep`），拿不到 token 时才回退到 `PowerManager.goToSleep`。
`ScreenSaverActivity` 提供沉浸式全屏宿主，遮罩退出时一并关闭。
`ScreenSaverView` 处理时钟、电池广播、任务展示、空闲计时与拖动。
`ScreenSaverDrift` 处理纵轴上下逐级漂移与边界折返。
`ScreenSaverControls` 提供主页入口与显示设置开关。任务展示复用
`AzurPilotRepository` 的热流，不增加 WebSocket 订阅或改变当前浏览实例。

### 验证

执行 `compileDebugKotlin -x verifyBundledAzurPilotRuntime` 与 i18n 检查。
APK 构建还需要通过 `app/scripts/fetch_ocr_runtime.py` 准备 OCR 打包库。
在 Android 设备上检查三键与手势导航的系统栏隐藏、边缘手势临时显示、完整拖动解锁、
短划回弹及退出后的亮度恢复。**面板关屏要单独确认**：进入后遮住前置摄像头/看面板是否
真的不发光，同时用 `dumpsys SurfaceFlinger` 或远程 WebUI 确认任务仍在跑、虚拟屏帧仍在更新。
分别在 Android 13 及以下（走 `SurfaceControl` 通道）与 14 及以上（走 services.jar 的
`DisplayControl` 通道）各验一次；把特权服务停掉再进入，应退化为黑遮罩而不崩。
退出后必须恢复面板，不允许留在黑屏。

**固有风险**：面板电源模式是 SurfaceFlinger 的状态，不是本进程的引用计数。进程被系统
杀掉或崩溃时没人负责恢复，面板可能停在关闭状态——按一次电源键即可恢复（系统亮灭屏会
重置 display 电源模式）。这是面板级关屏相对 `goToSleep` 的代价：换来任务不被打断，
就得自己承担进程异常退出后的残留状态。正常退出路径（滑块解锁、环境停止、切前台、
全屏宿主销毁）都会恢复。无交互 30 秒应开始纵向位移，之后每 10 秒移动一步；触摸与回弹后重新计时。
检查漂移边界、矮屏、大字体和全屏宿主离开前台时的清理。
任务自动进入应在调度器或工具启动时触发，准备环境本身不触发。

## English

Dim screen automation displays a black overlay in background mode to catch touches, then asks the
privileged process to set the physical panel's power mode to `POWER_MODE_OFF`. That is **panel-level
blanking**: the system still considers the screen on, so it never enters Doze, never freezes
processes, and never stalls virtual-display capture — tasks keep running while the panel really
stops emitting light. Dragging the slider restores it. When the privileged connection or a display
token is unavailable (below Android 14 tokens come from hidden `SurfaceControl` methods; 14 and up
from `DisplayControl` in services.jar), it degrades to the black overlay with the screen kept lit:
still black, just without the power saving. The `0.01` window brightness remains only to avoid a
flash when the panel returns.
A separate immersive host hides status and navigation bars, with black covering the whole screen.
System edge gestures can reveal the bars transiently. Leaving the foreground dismisses the overlay
with its host and restores the panel so navigation does not stay illuminated or the screen black.

### Usage

1. Tap **Dim screen** on the home page to show the overlay immediately. On first use, grant the
   overlay permission and tap the entry again.
2. Enable **Enter automatically when a run starts** under **Settings → Display** to show the
   overlay when the next scheduler or tool run starts.
3. Drag from the thumb to the right end and release to unlock. Taps, drags starting elsewhere on
   the track, and short fast swipes do not unlock. TalkBack users can dismiss it through the
   custom unlock action.

The overlay displays local time, date, battery level, and the running instance's task icon and name.
When the running and browsed instances differ, the running instance takes precedence. Missing task
data falls back to running, waiting, or connecting status. Stopping the scheduler, tool, or runtime
environment dismisses the overlay automatically.
Failed task-status requests retain the last known state; WebUI reachability alone does not confirm
that a task has stopped.

### Burn-in mitigation and layout

- The clock refreshes independently at minute boundaries and respects the system's 12/24-hour format.
- The clock, task card, and unlock bar move together vertically after 30 idle seconds to mitigate burn-in,
  then take a 16dp step every 10 seconds, reversing at boundaries.
- Drift uses measured content and available vertical space, capped at 96dp and avoiding display cutouts.
- Touches and settling pause movement; completion restarts the full 30-second idle delay.
- The task card, icon container, unlock track, and thumb use thin outlines on black to reduce large
  illuminated fills.
- Short screens and large fonts allow scrolling to keep the unlock control reachable.
- The existing MD3 dark theme supplies dynamic colors, typography, and container shapes. The black
  background stays fixed, with no continuous animation.

### Implementation

`ScreenSaverOverlayManager` manages the overlay window, run-state observation, and window
brightness, and asks `RemoteService.setDisplayPower` for panel off/on as the overlay shows and hides.
`third/wrappers/DisplayPower` resolves physical display tokens and calls
`SurfaceControl.setDisplayPowerMode`; on Android 14+ the tokens come from
`third/wrappers/ServerDisplayControl`, which loads `com.android.server.display.DisplayControl` out of
services.jar. Remote access's "lock and sleep" reuses the same panel blanking
(`WakeUnlockController.lockAndSleep`) and only falls back to `PowerManager.goToSleep` when no token
can be resolved.
`ScreenSaverActivity` supplies the immersive host and closes with the overlay.
`ScreenSaverView` handles the clock, battery broadcast, task presentation, idle timer, and dragging.
`ScreenSaverDrift` handles vertical stepwise drift and boundary reversal.
`ScreenSaverControls` supplies the home entry and display setting. Task presentation reuses
`AzurPilotRepository` flows without adding WebSocket subscriptions or changing the browsed instance.

### Verification

Run `compileDebugKotlin -x verifyBundledAzurPilotRuntime` and the i18n check. APK builds also require
the OCR libraries prepared by `app/scripts/fetch_ocr_runtime.py`. On an Android device, check hidden
bars with three-button and gesture navigation, transient bars from edge gestures, full-drag dismissal,
short-drag return, and restored brightness. **Verify panel blanking separately**: cover the front
camera or watch the panel to confirm it stops emitting light, while `dumpsys SurfaceFlinger` or the
remote WebUI confirms tasks keep running and virtual-display frames keep updating. Test both Android
13 and below (the `SurfaceControl` path) and Android 14 and up (the services.jar `DisplayControl`
path); stopping the privileged service first must degrade to the black overlay without crashing.
Leaving must always restore the panel — never leave the screen black.

**Inherent risk**: the panel power mode is SurfaceFlinger state, not a reference count owned by this
process. If the process is killed or crashes nobody restores it, and the panel can stay off — one
press of the power button recovers it (the system's own screen on/off cycle resets the display power
mode). That is the price of panel-level blanking over `goToSleep`: tasks are never interrupted, in
exchange for owning the leftover state after an abnormal exit. Every normal exit path (slider unlock,
run stopped, switching to foreground, immersive host destroyed) does restore it. After 30 idle seconds, content starts vertical movement and
steps every 10 seconds; touches and settling restart the timer. Check drift boundaries, short screens,
large fonts, and cleanup when the immersive host leaves the foreground. Automatic entry follows
scheduler or tool startup; preparing the environment alone does not trigger it.
