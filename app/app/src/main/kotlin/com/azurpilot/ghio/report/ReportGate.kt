package com.azurpilot.ghio.report

/**
 * 对外上报出口的集中闸门。
 *
 * 上游把「向项目方服务器发送数据」的出口分散在多个包里（机型报告、远程配对注册），
 * 逐处删代码会让后续同步上游变成持续冲突。这里只保留「允不允许出网」这一个判断，
 * 调用点在发起网络请求前先查一次，改一个常量即可整体关闭。
 *
 * Central gate for outbound reporting. Keeps one boolean per egress instead of
 * deleting call sites, so upstream syncs stay mergeable.
 */
internal object ReportGate {

    /**
     * 机型适配提交：`POST https://afa-api.azurpilot.com/v1/device-reports`。
     *
     * 载荷为厂商、型号、ROM、屏幕、内存等机型快照（不含 IMEI/序列号/Android ID，
     * 上游在采集端与服务端各做一次去敏）。上游设计为「用户主动选择实测结果后才发送」，
     * 本分支进一步关闭该出口，页面仍可查看本机检测结果，仅发送动作被拦下。
     */
    const val DEVICE_REPORT_ENABLED: Boolean = false

    /**
     * 远程访问配对注册：`POST https://remote.azurpilot.com/api/v1/pairings/claim`。
     *
     * 载荷只有一个六位配对码或 32 位十六进制扫描密文，不含设备信息；它本身就是
     * 「远程访问」功能的握手，关掉等于砍掉功能，因此默认保留。需要彻底断开与
     * 项目方服务器的连接时，把它改成 false。
     */
    const val REMOTE_PAIRING_ENABLED: Boolean = true
}
