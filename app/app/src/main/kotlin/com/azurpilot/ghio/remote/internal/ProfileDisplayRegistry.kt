package com.azurpilot.ghio.remote.internal

import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.third.Ln
import java.util.concurrent.ConcurrentHashMap

/**
 * 配置档案 → 虚拟屏的绑定表（P0 骨架）
 *
 * 目前 [VirtualDisplayManager] 仍是「一个 object 持有一块屏」的实现，所以这张表
 * **只允许一个 profile 持屏**：第二个 profile 请求时返回
 * [DefaultDisplayConfig.DISPLAY_NONE]，让调用方拿到明确的「暂不支持」而不是一个
 * 会和别人抢焦点的 displayId。把「能挂多块」和「假装能挂多块」分开，是这一步的
 * 全部意义——多实例改造（每块屏独立采集面、独立生命周期）另行进行。
 *
 * profile 一旦持屏就固定绑定，不允许在运行中改绑：改绑意味着输入要从一块屏挪到
 * 另一块，正在操作的手指会落到错误的窗口上。
 *
 * Profile → virtual display binding (P0 skeleton).
 *
 * [VirtualDisplayManager] still owns a single display, so this table allows exactly one
 * profile to hold it: a second profile receives [DefaultDisplayConfig.DISPLAY_NONE] instead
 * of an id that would fight over focus. Keeping "cannot do multi yet" separate from
 * "pretends to" is the whole point of this step; the real multi-instance refactor (own
 * capture surface and lifecycle per display) comes later.
 *
 * A binding is fixed once created: rebinding at runtime would move input from one display
 * to another under the user's finger, landing taps in the wrong window.
 */
object ProfileDisplayRegistry {

    private const val TAG = "ProfileDisplay"

    /** 默认档案名；未指定 profile 的旧调用方落到这里 / Profile name used by legacy callers. */
    const val DEFAULT_PROFILE = "default"

    private val bindings = ConcurrentHashMap<String, Int>()

    /**
     * 为 profile 创建（或复用）虚拟屏
     *
     * @param profileId 配置档案名；空串按 [DEFAULT_PROFILE] 处理
     * @return 该 profile 的 displayId，失败或已有其它 profile 持屏时返回 `DISPLAY_NONE`
     *
     * @throws IllegalStateException 当底层创建抛出异常时（由 [start] 转成 `DISPLAY_NONE`）
     */
    fun startProfileDisplay(profileId: String): Int = synchronized(this) {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        existing(id)?.let { return it }

        val holder = bindings.entries.firstOrNull()
        if (holder != null) {
            // 单屏实现下的硬约束：宁可明确失败，也不要返回一个抢焦点的 id
            Ln.w("$TAG: $id 请求持屏被拒，$holder 仍持有单块虚拟屏（多屏尚未实现）")
            return DefaultDisplayConfig.DISPLAY_NONE
        }

        val displayId = runCatching { VirtualDisplayManager.start() }
            .onFailure { Ln.e("$TAG: 为 $id 创建虚拟屏失败", it) }
            .getOrDefault(DefaultDisplayConfig.DISPLAY_NONE)
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: $id 创建虚拟屏返回 DISPLAY_NONE")
            return DefaultDisplayConfig.DISPLAY_NONE
        }
        bindings[id] = displayId
        Ln.i("$TAG: $id 绑定 displayId=$displayId")
        return displayId
    }

    /**
     * 停止 profile 的虚拟屏并解绑
     *
     * 只有持有者才真正停屏；其它 profile 调用是 no-op。停屏走
     * [VirtualDisplayManager.stop]，它自己保证幂等。
     */
    fun stopProfileDisplay(profileId: String) {
        val id = profileId.ifBlank { DEFAULT_PROFILE }
        synchronized(this) {
            val owned = bindings[id] ?: return
            bindings.remove(id)
            runCatching { VirtualDisplayManager.stop() }
                .onFailure { Ln.w("$TAG: 停止 $id 的虚拟屏失败") }
            Ln.i("$TAG: $id 释放 displayId=$owned")
        }
    }

    /** 查询 profile 的 displayId；未持屏返回 `DISPLAY_NONE`。 */
    fun displayIdFor(profileId: String): Int =
        existing(profileId.ifBlank { DEFAULT_PROFILE }) ?: DefaultDisplayConfig.DISPLAY_NONE

    /**
     * 导出 `profileId:displayId` 列表，供 App 侧渲染绑定关系
     *
     * 用字符串数组而不是 Map：跨 Binder 时不需要额外的泛型声明，日志里也可读。
     */
    fun snapshot(): Array<String> =
        bindings.entries.sortedBy { it.key }.map { "${it.key}:${it.value}" }.toTypedArray()

    /** 特权进程重启后清理残留绑定 / Clears stale bindings after the privileged process restarts. */
    fun reset() {
        synchronized(this) {
            bindings.clear()
        }
    }

    /**
     * 已绑定则返回 id，但要求底层显示仍然存活
     *
     * 底层可能被别处停掉（例如 UI 里的「停止环境」直接调了
     * `VirtualDisplayManager.stop()`），此时表里还留着旧 id，必须当作未绑定处理，
     * 否则调用方会拿到一个已经销毁的 displayId。
     *
     * Returns the bound id only while the underlying display is still alive.
     *
     * The display can be stopped elsewhere (the environment's stop path calls
     * `VirtualDisplayManager.stop()` directly), leaving a stale id here; treating it as
     * unbound keeps callers from receiving a destroyed displayId.
     */
    private fun existing(profileId: String): Int? {
        val bound = bindings[profileId] ?: return null
        if (VirtualDisplayManager.getDisplayId() != bound) {
            bindings.remove(profileId)
            Ln.w("$TAG: $profileId 的 displayId=$bound 已失效，清除绑定")
            return null
        }
        return bound
    }
}
