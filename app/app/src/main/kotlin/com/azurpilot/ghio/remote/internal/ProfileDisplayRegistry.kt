package com.azurpilot.ghio.remote.internal

import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.third.Ln

/**
 * 配置档案 → 虚拟屏的策略层
 *
 * 生命周期本身在 [VirtualDisplayManager] 的会话里，这里只负责三件事：
 * 1. 把 profile 名规范化（空串落到默认档案）
 * 2. 给调用方一个「不会拿到已销毁 id」的查询：displayId 为 [DefaultDisplayConfig.DISPLAY_NONE]
 *    或会话已不存在时一律当未绑定
 * 3. 输出 `profile:displayId` 快照，供 App 侧渲染绑定关系
 *
 * 为什么还要这一层：AIDL 的契约是按 profile 提问，而 [VirtualDisplayManager] 既要服务
 * 老的单屏调用方、又要按 profile 建会话。把「名字规范化 + 失效判定」集中在一处，
 * 免得每个调用点各写一遍。
 *
 * Policy layer over profile → display.
 *
 * The lifecycle lives in [VirtualDisplayManager]'s sessions; this only normalizes profile
 * names, answers "is it really alive" queries (a stale id is treated as unbound), and emits the
 * `profile:displayId` snapshot the app renders.
 *
 * Why the layer exists: the AIDL contract asks per profile, while
 * [VirtualDisplayManager] must also keep serving legacy single-display callers. Normalization
 * and staleness checks live here so no call site has to repeat them.
 */
object ProfileDisplayRegistry {

    private const val TAG = "ProfileDisplay"

    /** 默认档案名；未指定 profile 的旧调用方落到这里 / Profile used by legacy callers. */
    const val DEFAULT_PROFILE = VirtualDisplayManager.DEFAULT_PROFILE

    /**
     * 为 profile 创建（或复用）虚拟屏
     *
     * @param profileId 配置档案名；空串按 [DEFAULT_PROFILE] 处理
     * @param width 帧宽；<=0 用默认值
     * @param height 帧高；<=0 用默认值
     * @param dpi 密度；0 跟随设备
     * @return 该 profile 的 displayId，失败为 [DefaultDisplayConfig.DISPLAY_NONE]
     */
    fun startProfileDisplay(
        profileId: String,
        width: Int = 0,
        height: Int = 0,
        dpi: Int = 0,
    ): Int {
        val id = normalize(profileId)
        val displayId = VirtualDisplayManager.startSession(id, width, height, dpi)
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: $id 建屏失败（会话数已达上限或底层创建失败）")
        } else {
            Ln.i("$TAG: $id 绑定 displayId=$displayId，推流=${VirtualDisplayManager.isStreaming(id)}")
        }
        return displayId
    }

    /** 停止 profile 的虚拟屏并解绑 / Stops and unbinds the profile's display. */
    fun stopProfileDisplay(profileId: String) {
        val id = normalize(profileId)
        VirtualDisplayManager.stopSession(id)
        Ln.i("$TAG: $id 已释放")
    }

    /**
     * 查询 profile 的 displayId；未持屏或已销毁返回 [DefaultDisplayConfig.DISPLAY_NONE]
     *
     * 会话被别处停掉（例如 UI 的「停止环境」直接调 `VirtualDisplayManager.stop()`）时，
     * 这里返回 NONE 而不是历史 id，避免调用方把输入注入到已销毁的屏上。
     *
     * Display id for the profile, or [DefaultDisplayConfig.DISPLAY_NONE] when absent/destroyed.
     *
     * When the display was stopped elsewhere, returning the historical id would send input to a
     * destroyed display, so it degrades to NONE.
     */
    fun displayIdFor(profileId: String): Int {
        val id = normalize(profileId)
        val displayId = VirtualDisplayManager.sessionDisplayId(id)
        return if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            DefaultDisplayConfig.DISPLAY_NONE
        } else {
            displayId
        }
    }

    /**
     * 导出 `profileId:displayId` 列表，供 App 侧渲染绑定关系
     *
     * 用字符串数组而不是 Map：跨 Binder 时不需要额外泛型声明，日志里也可读。
     *
     * Bound profiles as `profileId:displayId` entries.
     *
     * A string array instead of a Map keeps the Binder signature flat and the log readable.
     */
    fun snapshot(): Array<String> =
        VirtualDisplayManager.sessionProfiles()
            .mapNotNull { profile ->
                val displayId = VirtualDisplayManager.sessionDisplayId(profile)
                if (displayId == DefaultDisplayConfig.DISPLAY_NONE) null
                else "$profile:$displayId"
            }
            .toTypedArray()

    /** profile 是否正在推流 / Whether the profile is currently streamed. */
    fun isStreaming(profileId: String): Boolean =
        VirtualDisplayManager.isStreaming(normalize(profileId))

    /** 特权进程重启后清空绑定 / Clears bindings after the privileged process restarts. */
    fun reset() {
        VirtualDisplayManager.stopAll()
    }

    /** 空串落到默认档案；其余原样保留（profile 名由 AzurPilot 配置决定） / Blank falls back to the default profile. */
    private fun normalize(profileId: String): String =
        profileId.trim().ifBlank { DEFAULT_PROFILE }
}
