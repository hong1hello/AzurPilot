package com.azurpilot.ghio.third.wrappers

import android.os.IBinder

/**
 * 物理屏电源模式开关：只关显示面板，不动系统电源状态
 *
 * 与 `PowerManager.goToSleep()` 的本质区别：后者让**整机**进入休眠（Doze、进程冻结、
 * 息屏广播、虚拟屏帧停滞），而这里只是把物理屏在 SurfaceFlinger 层的电源模式设成
 * [SurfaceControl.POWER_MODE_OFF]。系统仍认为屏幕是亮的，所以后台任务、虚拟屏采集、
 * 定时器都不受影响，只是面板不再发光。
 *
 * 因此 [isOff] 与 `PowerManager.isScreenOn()` 是**两件不同的事**：面板被关上之后
 * `isScreenOn()` 依然返回 true。判断「我们是否关掉了面板」只能问 [isOff]。
 *
 * 仅在特权进程（root / shell）内可用：`setDisplayPowerMode` 要过 SurfaceFlinger 的
 * uid 校验。Android 14+ 的 token 解析走 [ServerDisplayControl]，更早的版本走
 * [SurfaceControl] 的隐藏静态方法。
 *
 * Physical display power switch: blanks the panel without touching system power state.
 *
 * Unlike `PowerManager.goToSleep()`, which suspends the whole device (Doze, frozen
 * processes, screen-off broadcasts, stalled virtual-display frames), this only sets the
 * physical display's SurfaceFlinger power mode to [SurfaceControl.POWER_MODE_OFF]. The
 * system still considers the screen on, so background tasks, virtual-display capture and
 * timers keep running; only the panel stops emitting light.
 *
 * [isOff] is therefore **not** the inverse of `PowerManager.isScreenOn()`: after blanking,
 * `isScreenOn()` still reports true, and only [isOff] answers "did we blank the panel".
 *
 * Privileged process only (root / shell): `setDisplayPowerMode` passes through
 * SurfaceFlinger's uid check. Token resolution goes through [ServerDisplayControl] on
 * Android 14+ and through [SurfaceControl]'s hidden statics on older releases.
 */
object DisplayPower {

    /** Android 14 起物理屏 ID 只能从 services.jar 的 DisplayControl 取 / Android 14+ needs the services.jar DisplayControl */
    private const val API_34 = 34

    @Volatile
    private var panelOff = false

    /** 面板是否已被本进程关掉 / Whether this process blanked the panel. */
    fun isOff(): Boolean = panelOff

    /**
     * 开/关物理屏面板；返回是否至少在一个显示器上生效
     *
     * 逐个物理屏设置，多屏设备（含外接）一并处理。任一成功即视为成功，并把
     * [isOff] 同步到目标状态；全部失败返回 false，由调用方决定是否回退到
     * `goToSleep`。
     *
     * Blanks or restores the physical panel; returns whether at least one display took it.
     *
     * Every physical display is set, so multi-panel devices are covered. One success counts
     * as success and syncs [isOff] to the requested state; a total failure returns false so
     * the caller can fall back to `goToSleep`.
     */
    fun setPower(on: Boolean): Boolean {
        val tokens = physicalTokens()
        if (tokens.isEmpty()) return false
        val mode = if (on) SurfaceControl.POWER_MODE_NORMAL else SurfaceControl.POWER_MODE_OFF
        var applied = false
        for (token in tokens) {
            if (SurfaceControl.setDisplayPowerMode(token, mode)) applied = true
        }
        if (applied) panelOff = !on
        return applied
    }

    /**
     * 解析全部物理屏 token；三级回退，任何一级拿到结果就停
     *
     * 顺序：Android 14+ 的 services.jar 通道 → `SurfaceControl` 隐藏静态方法 →
     * 单一内置屏。最后一级保证老 ROM 上也能拿到一个 token。
     *
     * Resolves every physical display token with a three-step fallback, stopping at the
     * first level that yields results: the Android 14+ services.jar channel, then
     * `SurfaceControl`'s hidden statics, then the single built-in display.
     */
    private fun physicalTokens(): List<IBinder> {
        val tokens = ArrayList<IBinder>(2)
        if (android.os.Build.VERSION.SDK_INT >= API_34 && ServerDisplayControl.isAvailable()) {
            ServerDisplayControl.physicalDisplayIds()?.forEach { id ->
                ServerDisplayControl.physicalDisplayToken(id)?.let(tokens::add)
            }
        }
        if (tokens.isEmpty()) {
            SurfaceControl.getPhysicalDisplayIds()?.forEach { id ->
                SurfaceControl.getPhysicalDisplayToken(id)?.let(tokens::add)
            }
        }
        if (tokens.isEmpty()) {
            SurfaceControl.getBuiltInDisplay()?.let(tokens::add)
        }
        return tokens
    }
}
