package com.azurpilot.ghio.remote.internal

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Display
import android.view.Surface
import com.azurpilot.ghio.bridge.NativeBridgeLib
import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.constant.DefaultDisplayConfig.VD_NAME
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


/**
 * 独立虚拟屏：游戏实际运行的一块 PUBLIC 展示屏
 *
 * BACKGROUND 模式（默认）用：建屏后游戏被 pin 到这块屏，采集、注入、看门狗都围绕
 * [getDisplayId] 工作。分辨率默认按 [DefaultDisplayConfig]（桥协议钉死），可由 app 侧经
 * setResolution 改，采集中的屏改完即重启。
 *
 * 建屏后处理旋转：ROM 把 VD 转了非零角度时先 freezeRotation 回正；物理屏本就是横屏原生的
 * 设备（如 AYN Odin2）freezeRotation 无效，退而 setForcedDisplaySize 强报横屏尺寸。
 *
 * 线程：生命周期操作由 binder 线程调用，共用 [displayLifecycleLock] 串行执行；
 * displayId 与配置通过原子量供桥线程读取。
 *
 * ## 多实例（P0）
 *
 * 每个 profile 一个 [VirtualDisplaySession]，`start()` 等老接口固定作用于
 * [DEFAULT_PROFILE]，行为与改造前完全一致。新接口按 profile 操作。
 *
 * **采集面是全局唯一的**：`NativeBridgeLib` 只提供 `setupNativeCapturer` /
 * `releaseNativeCapturer` 这一对无句柄接口，所以同一时刻只有一块屏能拿到 surface 推流。
 * 拿不到 surface 的会话仍然会建屏（游戏能跑、输入能进），只是没有画面回传。这是当前
 * 原生能力的硬边界，不是这里的实现缺陷；要做「多屏同时可见」必须先把原生采集器改成
 * 多实例，见 [CapturerLease] 的说明。
 *
 * Standalone virtual display: the PUBLIC display the game actually runs on.
 *
 * Used in BACKGROUND mode (the default): after creation the game is pinned onto it, and
 * capture, injection, and the watchdog all revolve around [getDisplayId]. The resolution
 * defaults to [DefaultDisplayConfig] (pinned by the bridge protocol) and can be changed by the
 * app via setResolution; a live display restarts on change.
 *
 * Post-creation rotation handling: when the ROM gives the VD a non-zero rotation, freezeRotation
 * is applied first; on devices whose physical panel is landscape-native (e.g. the AYN Odin2)
 * freezeRotation does nothing, so setForcedDisplaySize is applied instead to force the
 * landscape size reporting.
 *
 * Threading: lifecycle operations run on binder threads and serialize on [displayLifecycleLock].
 * The bridge reads the display id and configuration through atomics.
 *
 * ## Multiple instances (P0)
 *
 * One [VirtualDisplaySession] per profile. The legacy `start()` and friends always act on
 * [DEFAULT_PROFILE] and behave exactly as before; the new calls take a profile id.
 *
 * **The capture surface is global**: `NativeBridgeLib` exposes only
 * `setupNativeCapturer` / `releaseNativeCapturer` with no handle, so exactly one display can be
 * streamed at a time. A session that cannot get the surface still gets its display (the game
 * runs and receives input) but has no frames going back. That is a limit of the native
 * layer, not of this class — simultaneous previews need a multi-instance capturer first; see
 * [CapturerLease].
 */
object VirtualDisplayManager {

    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    // 以下多为 SDK 未公开的隐藏 flag 位，按 API 级别分层补齐，见 buildDisplayFlags
    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    /** 不带系统装饰：游戏全屏跑，无状态栏 / No system decorations: the game runs fullscreen without a status bar */
    private const val VD_SYSTEM_DECORATIONS = false

    /** 屏移除时销毁其上内容 / Content on the display is destroyed when the display is removed */
    private const val VD_DESTROY_CONTENT = true

    /** 无活动虚拟屏的哨兵值 / Sentinel for "no active virtual display" */
    const val DISPLAY_NONE = -1

    /** 老接口默认作用的档案名 / Profile the legacy API acts on. */
    const val DEFAULT_PROFILE = "default"

    /** 同时存在的虚拟屏上限；超过后 [startSession] 返回 [DISPLAY_NONE] / Cap on concurrent displays. */
    const val MAX_SESSIONS = 4

    /**
     * 虚拟屏配置；帧缓冲与触摸坐标空间都按它算
     *
     * Virtual display configuration; the frame buffer and the touch coordinate space are both
     * derived from it.
     *
     * @property width 帧宽（px）/ frame width (px)
     * @property height 帧高（px）/ frame height (px)
     * @property dpi 密度 / density
     */
    data class DisplayConfig(
        val width: Int = DefaultDisplayConfig.WIDTH,
        val height: Int = DefaultDisplayConfig.HEIGHT,
        val dpi: Int = DefaultDisplayConfig.DPI
    )

    private val sessions = ConcurrentHashMap<String, VirtualDisplaySession>()
    private val defaultSession = VirtualDisplaySession(DEFAULT_PROFILE)

    /**
     * 原生采集器的租约
     *
     * `NativeBridgeLib` 的采集接口没有句柄：`setupNativeCapturer(w, h)` 设置全局采集器，
     * `releaseNativeCapturer()` 释放全局采集器。所以这里做成**独占租约**：一个会话拿到，
     * 其它会话拿不到 surface（建屏时传 null），并在日志里说明原因。
     *
     * 要把「多屏同时可见」做出来，得先把原生侧改成
     * `setupNativeCapturer(id, w, h)` + `releaseNativeCapturer(id)`，这里再按 id 分配。
     *
     * Lease over the single native capturer.
     *
     * `NativeBridgeLib` has no handle: `setupNativeCapturer(w, h)` configures the global
     * capturer and `releaseNativeCapturer()` frees it. So exactly one session may hold it; the
     * others create their display with a null surface and say why in the log.
     *
     * Simultaneous previews require turning the native side into
     * `setupNativeCapturer(id, w, h)` + `releaseNativeCapturer(id)` first.
     */
    private object CapturerLease {
        private var owner: String? = null

        /**
         * 尝试为 profile 取得采集面
         *
         * @return 取得时返回新 surface；已被其它 profile 持有时返回 null
         */
        fun acquire(profileId: String, cfg: DisplayConfig): Surface? = synchronized(this) {
            val held = owner
            if (held != null && held != profileId) {
                Ln.w("Capturer held by $held; $profileId gets a display without preview")
                return null
            }
            val surface = runCatching { NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height) }
                .onFailure { Ln.e("setupNativeCapturer failed for $profileId", it) }
                .getOrNull()
            if (surface == null) {
                Ln.w("No capture surface for $profileId")
                return null
            }
            owner = profileId
            return surface
        }

        /** 释放 profile 持有的采集器；非持有者调用无效 / Releases the capturer if the profile owns it. */
        fun release(profileId: String) = synchronized(this) {
            if (owner != profileId) return@synchronized
            owner = null
            runCatching { NativeBridgeLib.releaseNativeCapturer() }
                .onFailure { Ln.w("releaseNativeCapturer failed for $profileId") }
        }

        /** 当前持有者 / Current owner. */
        fun currentOwner(): String? = synchronized(this) { owner }
    }

    /**
     * 单个 profile 的虚拟屏生命周期
     *
     * 每个会话自带配置、displayId 与 surface，互不影响；`VirtualDisplayManager` 只负责
     * 把它们收在一起并对老接口做转发。
     *
     * One profile's display lifecycle. Each session owns its config, display id and surface;
     * the manager only collects them and forwards the legacy API.
     */
    private class VirtualDisplaySession(val profileId: String) {
        val state = AtomicInteger(STATE_IDLE)
        val config = AtomicReference(DisplayConfig())
        // 0 在每次建屏时解析主屏 DPI，避免保存一个过时的设备密度。
        val requestedDpi = AtomicInteger(0)
        val requestedRefreshRate = AtomicReference(0f)
        val displayId = AtomicInteger(DISPLAY_NONE)
        val virtualDisplay = AtomicReference<VirtualDisplay?>()
        val monitorSurface = AtomicReference<Surface?>()

        /** 本会话当前是否在推流（持有采集器）/ Whether this session currently holds the capturer. */
        fun isStreaming(): Boolean = CapturerLease.currentOwner() == profileId

        fun start(): Int = synchronized(displayLifecycleLock) {
            if (state.get() == STATE_CAPTURING) {
                Ln.i("[$profileId] reused, displayId=${displayId.get()}")
                return@synchronized displayId.get()
            }
            startInternal()
        }

        fun stop() = synchronized(displayLifecycleLock) {
            state.set(STATE_IDLE)
            try {
                releaseResources()
            } finally {
                monitorSurface.getAndSet(null)?.release()
            }
            Ln.i("[$profileId] stopped")
        }

        fun restart() = synchronized(displayLifecycleLock) {
            if (state.get() != STATE_CAPTURING) return@synchronized
            state.set(STATE_IDLE)
            releaseResources()
            startInternal()
            Unit
        }

        fun setResolution(width: Int, height: Int, dpi: Int) {
            synchronized(displayLifecycleLock) {
                require(width > 0 && height > 0 && (dpi == 0 || dpi in 72..960))
                requestedDpi.set(dpi)
                val newConfig = DisplayConfig(width, height, resolveDpi(dpi))
                val oldConfig = config.getAndSet(newConfig)
                if (state.get() == STATE_CAPTURING && oldConfig != newConfig) {
                    Ln.i("[$profileId] config changed: $oldConfig -> $newConfig, restart")
                    restart()
                }
            }
        }

        fun setMonitorSurface(surface: Surface?) = synchronized(displayLifecycleLock) {
            val old = monitorSurface.getAndSet(surface)
            if (old != null && old != surface) {
                old.release()
                Ln.i("[$profileId] old monitor surface released")
            }
            Ln.i("[$profileId] setMonitorSurface: old=${old != null}, new=${surface != null}")
        }

        private fun startInternal(): Int {
            try {
                if (virtualDisplay.get() != null) releaseResources()
                val cfg = config.get().copy(dpi = resolveDpi(requestedDpi.get()))
                config.set(cfg)
                // 拿不到采集面也照建：游戏能跑、输入能进，只是没有画面回传
                val surface = CapturerLease.acquire(profileId, cfg)
                createVirtualDisplay(surface, cfg)
                state.set(STATE_CAPTURING)
                Ln.i("[$profileId] started, displayId=${displayId.get()}, preview=${surface != null}")
                return displayId.get()
            } catch (e: Throwable) {
                Ln.e("[$profileId] start failed", e)
                state.set(STATE_IDLE)
                runCatching { releaseResources() }
                    .onFailure { Ln.e("[$profileId] start cleanup failed", it) }
                if (e is Error) throw e
                return DISPLAY_NONE
            }
        }

        private fun releaseResources() {
            displayId.set(DISPLAY_NONE)
            try {
                virtualDisplay.get()?.let { vd ->
                    vd.release()
                    virtualDisplay.set(null)
                }
            } finally {
                CapturerLease.release(profileId)
            }
        }

        private fun createVirtualDisplay(surface: Surface?, cfg: DisplayConfig) {
            val flags = buildDisplayFlags()
            val wm = ServiceManager.getWindowManager()
            val physicalRotation = runCatching { wm.getRotation() }.getOrDefault(-1)
            Ln.i("[$profileId] physical display rotation: $physicalRotation")

            val vd = checkNotNull(
                ServiceManager.getDisplayManager().createNewVirtualDisplay(
                    "$VD_NAME-$profileId",
                    cfg.width,
                    cfg.height,
                    cfg.dpi,
                    surface,
                    flags,
                    requestedRefreshRate.get()
                )
            ) { "DisplayManager returned no virtual display" }
            virtualDisplay.set(vd)
            val vdId = vd.display.displayId
            displayId.set(vdId)

            val d = vd.display
            Ln.i(
                "[$profileId] VD created: id=$vdId" +
                ", configured=${cfg.width}x${cfg.height}" +
                ", dpi=${cfg.dpi}, requestedDpi=${requestedDpi.get()}" +
                ", actual=${d.width}x${d.height}" +
                ", rotation=${d.rotation}" +
                ", requestedRefreshRate=${requestedRefreshRate.get()}, refreshRate=${d.refreshRate}" +
                ", flags=0x${flags.toString(16)}"
            )

            if (d.rotation != Surface.ROTATION_0) {
                // 所有旋转非零的情况都先尝试 freezeRotation
                runCatching {
                    wm.freezeRotation(vdId, Surface.ROTATION_0)
                    Ln.i("[$profileId] freezeRotation done, post-freeze rotation=${vd.display.rotation}")
                }.onFailure { e -> Ln.w("[$profileId] freezeRotation failed: ${e.message}") }

                if (physicalRotation == Surface.ROTATION_0) {
                    // 物理屏处于自然方向（rotation=0）而 VD 却有旋转角，
                    // 这是横屏原生设备如AYN Odin2的典型特征：
                    // 此类设备的定制 ROM 对二级显示调 freezeRotation 无效，
                    // 额外调 setForcedDisplaySize 强制 VD 向内部 app 上报横屏尺寸。
                    Ln.w(
                        "[$profileId] landscape-native device (physRot=0, vdRot=${d.rotation}), " +
                        "applying setForcedDisplaySize"
                    )
                    runCatching {
                        wm.setForcedDisplaySize(vdId, cfg.width, cfg.height)
                        Ln.i("[$profileId] setForcedDisplaySize(${cfg.width}x${cfg.height}) applied")
                    }.onFailure { e -> Ln.w("[$profileId] setForcedDisplaySize failed: ${e.message}") }
                }
            }
        }

        private fun resolveDpi(dpi: Int): Int = if (dpi != 0) dpi else {
            ServiceManager.getDisplayManager().getDisplayInfo(Display.DEFAULT_DISPLAY)?.dpi
                ?.takeIf { it > 0 } ?: DefaultDisplayConfig.DPI
        }
    }

    // ---- 老接口：全部作用于 DEFAULT_PROFILE，行为与改造前一致 ----

    /** 设定期刷新率，下次建（重）建屏生效；0 表示交给系统默认 / Desired refresh rate for the next creation. */
    fun setRefreshRate(rate: Float) = synchronized(displayLifecycleLock) {
        require(rate.isFinite() && rate >= 0f)
        defaultSession.requestedRefreshRate.set(rate)
    }

    /** 挂 app 侧预览 surface；换 surface 时释放旧的 / Attaches the preview surface. */
    fun setMonitorSurface(surface: Surface?) = defaultSession.setMonitorSurface(surface)

    /** 建虚拟屏并启动采集；已在采集时幂等返回当前 displayId / Creates the display, idempotent. */
    fun start(): Int = defaultSession.start()

    /** 停采集、释放虚拟屏与预览 surface；未采集时幂等 / Stops capture and releases everything. */
    fun stop() = defaultSession.stop()

    /** 采集中的重建；空闲时不动 / Rebuilds while capturing; no-op when idle. */
    fun restart() = defaultSession.restart()

    /** 改虚拟屏分辨率；DPI 为 0 时跟随设备 / Changes the resolution; a DPI of 0 follows the device. */
    fun setResolution(width: Int, height: Int, dpi: Int = defaultSession.requestedDpi.get()) =
        defaultSession.setResolution(width, height, dpi)

    /** 当前虚拟屏 displayId，未建为 [DISPLAY_NONE] / Current display id or [DISPLAY_NONE]. */
    fun getDisplayId(): Int = defaultSession.displayId.get()

    /** 帧缓冲与触摸坐标空间都按它算 / The frame buffer and touch space derive from it. */
    fun getConfig(): DisplayConfig = defaultSession.config.get()

    // ---- 多实例接口 ----

    /**
     * 为 profile 建（或复用）一块虚拟屏
     *
     * @param profileId 配置档案名；空串按 [DEFAULT_PROFILE]
     * @param width 帧宽；<=0 用默认值
     * @param height 帧高；<=0 用默认值
     * @param dpi 密度；0 跟随设备
     * @return displayId，或失败时的 [DISPLAY_NONE]
     */
    fun startSession(
        profileId: String,
        width: Int = 0,
        height: Int = 0,
        dpi: Int = 0,
    ): Int {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        val session = if (id == DEFAULT_PROFILE) defaultSession else {
            if (sessions.size >= MAX_SESSIONS) {
                Ln.w("startSession($id) refused: reached MAX_SESSIONS=$MAX_SESSIONS")
                return DISPLAY_NONE
            }
            sessions.computeIfAbsent(id) { VirtualDisplaySession(it) }
        }
        if (width > 0 && height > 0) {
            session.setResolution(width, height, dpi)
        }
        return session.start()
    }

    /** 停掉 profile 的虚拟屏并移除会话；未知 profile 幂等 / Stops and forgets the profile's display. */
    fun stopSession(profileId: String) {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        if (id == DEFAULT_PROFILE) {
            defaultSession.stop()
            return
        }
        sessions.remove(id)?.stop()
    }

    /** profile 的 displayId；无会话为 [DISPLAY_NONE] / Display id for the profile. */
    fun sessionDisplayId(profileId: String): Int {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        return if (id == DEFAULT_PROFILE) defaultSession.displayId.get()
        else sessions[id]?.displayId?.get() ?: DISPLAY_NONE
    }

    /** profile 的配置；无会话时返回默认配置 / Config for the profile, default when absent. */
    fun sessionConfig(profileId: String): DisplayConfig {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        return if (id == DEFAULT_PROFILE) defaultSession.config.get()
        else sessions[id]?.config?.get() ?: DisplayConfig()
    }

    /** profile 是否正在推流（持有采集器）/ Whether the profile currently holds the capturer. */
    fun isStreaming(profileId: String): Boolean {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        return if (id == DEFAULT_PROFILE) defaultSession.isStreaming()
        else sessions[id]?.isStreaming() == true
    }

    /** 当前存活的 profile 列表（含默认档案）/ Live profiles, including the default one. */
    fun sessionProfiles(): List<String> {
        val named = sessions.keys.sorted()
        return if (defaultSession.displayId.get() != DISPLAY_NONE) listOf(DEFAULT_PROFILE) + named
        else named
    }

    /** 全部停掉并清空；特权进程退出或「停止环境」时调用 / Stops everything. */
    fun stopAll() {
        sessions.keys.toList().forEach { sessions.remove(it)?.stop() }
        defaultSession.stop()
    }

    /**
     * 组装建屏 flag：基础四件套（PUBLIC / PRESENTATION / OWN_CONTENT_ONLY / SUPPORTS_TOUCH）
     * 让 VD 能展示并接收触摸；API 33+ 补 TRUSTED / OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED /
     * TOUCH_FEEDBACK_DISABLED，API 34+ 再补 OWN_FOCUS / DEVICE_DISPLAY_GROUP /
     * STEAL_TOP_FOCUS_DISABLED，让游戏把 VD 当可信屏正常运行
     */
    private fun buildDisplayFlags(): Int {
        var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                or VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH)

        if (VD_DESTROY_CONTENT) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        }
        if (VD_SYSTEM_DECORATIONS) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                        or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
            }
        }
        return flags
    }
}
