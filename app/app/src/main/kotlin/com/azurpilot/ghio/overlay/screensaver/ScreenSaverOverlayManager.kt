package com.azurpilot.ghio.overlay.screensaver

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.azurpilot.ghio.domain.RunMode
import com.azurpilot.ghio.overlay.OverlayViewModelOwner
import com.azurpilot.ghio.privileged.PrivilegedServicePort
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * 后台模式的环境期屏保
 *
 * 仅 [RunMode.BACKGROUND] 下工作：用一块全黑悬浮层压住屏幕
 * （TYPE_APPLICATION_OVERLAY，需 SYSTEM_ALERT_WINDOW）挡住触摸，再让特权进程把
 * 物理屏面板的电源模式设成 OFF——面板级关屏不进系统休眠，后台任务与虚拟屏采集
 * 照常跑，所以这次是真熄屏而不是装作熄屏。独立全屏宿主隐藏系统栏，避免导航按钮常亮。
 * 挂载与移除都在主线程；[setup] 后自观察运行模式
 * 与环境态；自动进入跟随调度器或工具启动，任务或环境停止时收起。
 *
 * 特权连接不可用、或 ROM 拿不到 display token 时，面板关不掉，此时退化成改动前的
 * 行为：全黑遮罩 + 保屏点亮，用户看到的仍是黑屏，只是没有真正省电。
 *
 * The screensaver shown over the environment while in background mode.
 *
 * Works in [RunMode.BACKGROUND] only: a full-black
 * overlay layer (TYPE_APPLICATION_OVERLAY, requires SYSTEM_ALERT_WINDOW)
 * covers the screen to catch touches, and the privileged process then sets the
 * physical panel's power mode to OFF — blanking at the panel level does not suspend the
 * system, so background tasks and virtual-display capture keep running. This is a real
 * screen-off rather than a pretend one. A separate immersive host hides system bars so
 * navigation buttons do not stay illuminated. Mount and removal happen on
 * the main thread; after [setup] the manager observes the run mode and the
 * environment state. Automatic entry follows scheduler or tool startup, and stopping the run or
 * environment dismisses the overlay.
 *
 * When the privileged connection or a display token is unavailable the panel cannot be
 * blanked, so it degrades to the previous behavior: the black overlay with the screen kept
 * lit. The user still sees black, only without the actual power saving.
 */
class ScreenSaverOverlayManager(
    private val context: Context,
    private val hostState: HostState,
    private val appSettings: AppSettingsGateway,
    private val runController: AzurPilotRunController,
    private val repository: AzurPilotRepository,
    private val privileged: PrivilegedServicePort,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 不复用 Koin 里那个 single：那份归控制层，生命周期跟着面板显隐推
     * 两处共用会互相把对方推回 CREATED，`collectAsStateWithLifecycle` 就此停摆
     */
    private val viewModelOwner = OverlayViewModelOwner()

    private var composeView: ComposeView? = null
    private var immersiveHost = WeakReference<ScreenSaverActivity>(null)
    private var hostReady: CompletableDeferred<Boolean>? = null
    private var hostJob: Job? = null
    private var setupJob: Job? = null

    private val _isShowing = MutableStateFlow(false)

    /** 屏保当前是否盖在屏上 / Whether the screensaver currently covers the screen. */
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    /** Host and overlay use the same persisted brightness, regardless of theme. */
    internal val screenBrightness: Float get() = appSettings.screenSaverBrightness.value

    /**
     * 开始观察运行模式：后台模式挂环境观察，前台模式撤观察并收起屏保；幂等
     *
     * Starts observing the run mode: background mode mounts the environment
     * watcher, foreground mode stops it and dismisses the screensaver;
     * idempotent.
     */
    fun setup() {
        if (setupJob != null) return
        setupJob = scope.launch {
            appSettings.runMode.collect { mode ->
                when (mode) {
                    RunMode.BACKGROUND -> observeHost()
                    RunMode.FOREGROUND -> {
                        stopObservingHost()
                        hide()
                    }
                }
            }
        }
    }

    private fun observeHost() {
        if (hostJob != null) return
        hostJob = scope.launch {
            var wasRunning = false
            var wasUp = hostState.snapshot.value.environmentUp
            combine(hostState.snapshot, runController.state) { snapshot, run -> snapshot to run }
                .collect { (snapshot, run) ->
                    val up = snapshot.environmentUp
                    // 状态接口短暂断线不等于任务结束，保留上次运行态以免突然亮屏。
                    val running = up && if (run.statusKnown) {
                        run.runnerAlive || run.toolAlive
                    } else wasRunning
                    when {
                        // 主页会自动准备环境，只有任务启动才应触发自动遮罩。
                        !wasRunning && running ->
                            if (appSettings.screenSaverEnabled.value) show()

                        (wasUp && !up) || (wasRunning && !running) -> hide()
                    }
                    wasRunning = running
                    wasUp = up
                }
        }
    }

    /** 停止环境观察 / Stops watching the environment state. */
    private fun stopObservingHost() {
        hostJob?.cancel()
        hostJob = null
    }

    /**
     * 盖上屏保；已盖时直接返回 true，非后台模式拒绝并告警
     *
     * 返回是否真的盖上并打开了全屏宿主；授权或启动失败时清理窗口并返回 false。
     *
     * Covers the screen with the screensaver; returns true immediately when
     * already showing, refuses with a warning outside background mode.
     *
     * Returns whether both the overlay and immersive host were shown. Permission or launch failures
     * clean up the window and return false.
     *
     * @return 盖上为 true，权限缺失或模式不符为 false / true when shown, false
     *   when the permission is missing or the mode does not match
     */
    suspend fun show(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (_isShowing.value) return@withContext true
        if (appSettings.runMode.value != RunMode.BACKGROUND) {
            Timber.w("Not in background mode; ignoring screen-saver show request")
            return@withContext false
        }

        val view = createView()
        val ready = CompletableDeferred<Boolean>()
        hostReady = ready
        runCatching { windowManager.addView(view, createLayoutParams()) }
            .onSuccess {
                composeView = view
                viewModelOwner.start()
                _isShowing.value = true
                // 遮罩就位后立刻关面板：黑遮罩负责挡触摸与防误触，面板级断电负责真正省电。
                requestDisplayPower(false)
                // 后台启动全屏宿主前先让悬浮窗实际可见，满足系统的后台启动条件。
                view.doOnPreDraw {
                    view.post {
                        if (composeView !== view) return@post
                        runCatching {
                            context.startActivity(Intent(context, ScreenSaverActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            })
                        }.onFailure {
                            ready.complete(false)
                            Timber.e(it, "Failed to launch immersive screen saver")
                        }
                    }
                }
            }
            .onFailure {
                view.disposeComposition()
                ready.complete(false)
                Timber.e(it, "Failed to show screen saver")
            }
        val shown = withTimeoutOrNull(HOST_LAUNCH_TIMEOUT_MS) { ready.await() } == true
        if (!shown) {
            if (hostReady === ready) hostReady = null
            if (composeView === view) hide()
            Timber.w("Screen saver immersive host unavailable")
        }
        shown
    }

    /**
     * 全屏宿主在主线程创建时登记；过期的启动请求不允许留下黑色空窗。
     *
     * Registers the immersive host on creation on the main thread; stale launches must not leave
     * an empty black window behind.
     */
    internal fun attachHost(activity: ScreenSaverActivity): Boolean {
        if (!_isShowing.value || composeView == null) return false
        immersiveHost = WeakReference(activity)
        hostReady?.complete(true)
        hostReady = null
        return true
    }

    /**
     * 用户离开全屏宿主时一并撤下遮罩；配置重建仍由新宿主接管。
     *
     * Dismisses the overlay when the user leaves the immersive host; a configuration recreation
     * lets the replacement host take over.
     */
    internal fun onHostStopped(activity: ScreenSaverActivity) {
        if (immersiveHost.get() === activity && !activity.isChangingConfigurations) {
            scope.launch { hide() }
        }
    }

    /**
     * 收起屏保；未盖时为 no-op，移除失败只记日志
     *
     * Dismisses the screensaver; a no-op when not showing, removal failures
     * are only logged.
     */
    suspend fun hide() = withContext(Dispatchers.Main.immediate) {
        val view = composeView
        if (view == null) {
            // 遮罩没挂上也可能已经关了面板（宿主启动超时那一路），这里补一次恢复。
            requestDisplayPower(true)
            return@withContext
        }
        runCatching { windowManager.removeView(view) }
            .onSuccess {
                view.disposeComposition()
                composeView = null
                _isShowing.value = false
                // 面板是特权进程设的，与本地窗口生命周期无关，拆完窗口再恢复即可。
                requestDisplayPower(true)
                hostReady?.complete(false)
                hostReady = null
                immersiveHost.get()?.finish()
                immersiveHost.clear()
                viewModelOwner.stop()
                Timber.d("Screen saver dismissed")
            }
            .onFailure { Timber.e(it, "Failed to remove screen saver") }
    }

    /**
     * 请求特权进程开关物理屏面板；失败只记日志、不影响遮罩
     *
     * 面板级关屏（SurfaceFlinger 的 POWER_MODE_OFF）不进系统休眠，所以后台任务、
     * 虚拟屏采集与定时器都照常跑，这正是「息屏运行」要的效果。特权连接不可用、
     * 或 ROM 拿不到 display token 时，退化成改动前的全黑遮罩，用户看到的仍是黑屏。
     *
     * Asks the privileged process to switch the physical panel; failures are only logged.
     *
     * Blanking at the SurfaceFlinger level (POWER_MODE_OFF) does not suspend the system, so
     * background tasks, virtual-display capture and timers keep running — the whole point of
     * "run with the screen off". When the privileged connection or a display token is
     * unavailable this degrades to the previous all-black overlay.
     */
    private fun requestDisplayPower(on: Boolean) {
        scope.launch {
            // 跨进程 binder 调用放到 IO，别占主线程一帧。
            runCatching {
                withContext(Dispatchers.IO) { privileged.useService { it.setDisplayPower(on) } }
            }.onFailure { Timber.w(it, "Display power request failed (on=%s)", on) }
        }
    }

    /**
     * 构建恒暗主题的独立组合，复用现有仓库热流，不新增网关订阅。
     *
     * Builds an independent always-dark composition from existing repository flows without adding
     * gateway subscriptions.
     */
    private fun createView(): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(viewModelOwner)
        setViewTreeViewModelStoreOwner(viewModelOwner)
        setViewTreeSavedStateRegistryOwner(viewModelOwner)
        setContent {
            // The overlay must not inherit the user's light mode, AMOLED palette or Miuix
            // typography: its background is always black, so those foreground colors can
            // otherwise become invisible. Use independent, high-contrast Material 3 colors.
            MaterialTheme(colorScheme = screenSaverColors) {
                val run by runController.state.collectAsState()
                val instances by repository.instances.collectAsState()
                val overview by repository.overview.collectAsState()
                val connected by repository.connected.collectAsState()
                val schema by repository.schema.collectAsState()
                ScreenSaverView(
                    run = run,
                    instances = instances,
                    overview = overview,
                    connected = connected,
                    schema = schema,
                    onUnlock = { scope.launch { hide() } },
                )
            }
        }
    }

    /**
     * 悬浮层不可聚焦，避免抢走全屏宿主的系统栏控制权；按键由宿主与系统处理。
     *
     * KEEP_SCREEN_ON 必须留着：面板的 OFF 是 SurfaceFlinger 层设的，系统自己仍认为屏幕
     * 亮着；一旦让系统超时休眠，就会走回 goToSleep 那条老路（Doze、进程冻结、
     * 虚拟屏帧停滞），等于白改。低亮度保留只为面板恢复的瞬间不刺眼。
     *
     * The overlay cannot take focus away from the immersive host's system-bar controls;
     * the host and system handle hardware keys.
     *
     * KEEP_SCREEN_ON must stay: the panel's OFF state is set at the SurfaceFlinger level,
     * while the system still believes the screen is on. Letting the system time out would
     * fall back to the old goToSleep path (Doze, frozen processes, stalled virtual-display
     * frames) and defeat the change. The low brightness only avoids a flash when the panel
     * comes back.
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            screenBrightness = this@ScreenSaverOverlayManager.screenBrightness
            // 内容仍避开安全区，但黑色窗口必须铺到系统栏原先占据的区域。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        // Stable, theme-independent OLED palette: black pixels remain off, but foreground
        // remains readable even when the display brightness is reduced.
        val screenSaverColors = darkColorScheme(
            primary = Color(0xFFACC9FF),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFD8DEEA),
            outline = Color(0xFFB9C3D4),
        )

        /** 平台拒绝后台启动时及时撤下遮罩。 / Retracts the overlay promptly if a background launch is denied. */
        const val HOST_LAUNCH_TIMEOUT_MS = 3_000L
    }
}
