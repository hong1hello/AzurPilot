package com.azurpilot.ghio.storage

import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行时目录回收：把「装了之后越用越大」的那部分收住
 *
 * 为什么需要它：宿主自己的日志有 [com.azurpilot.ghio.log.LogCleaner] 兜底，但
 * **运行时内部**（rootfs 里的 AzurPilot）没人管——每跑一次任务就在
 * `opt/azurpilot/log/<任务>/<时间>/` 下留一批截图与识别中间图，一年下来几个 GB；
 * 更新中断还会留下 `rootfs.tmp` / `rootfs.previous` / 下载残包这三类孤儿占位。
 *
 * 只碰可以再生的数据：日志、截图、guest 的临时目录、孤儿 staging 目录。
 * **绝不碰** `opt/azurpilot/config`（用户配置）、`.venv`、`frontend/dist`、源码树——
 * 那些删了就得重装运行时。
 *
 * Reclaims runtime directories that only ever grow.
 *
 * The host's own logs are covered by [com.azurpilot.ghio.log.LogCleaner], but the
 * runtime inside the rootfs writes a fresh screenshot tree under
 * `opt/azurpilot/log/<task>/<stamp>/` on every run, and an interrupted update can
 * leave `rootfs.tmp`, `rootfs.previous`, or a half-downloaded archive behind.
 *
 * Only regenerable data is touched: logs, screenshots, guest temp dirs, orphan
 * staging paths. User config, the venv, `frontend/dist`, and the source tree are
 * never candidates — deleting those would demand a full runtime reinstall.
 *
 * @param rootfsDir 已部署的运行时根目录 / the deployed runtime root
 * @param filesDir 宿主私有目录（存放 staging 与下载残包）/ the host private dir
 * @param clock 当前时间来源，测试可注入 / the clock, injectable for tests
 */
class StorageJanitor(
    private val rootfsDir: File,
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一次回收的结果 / The outcome of one sweep. */
    data class Summary(val deletedEntries: Int, val freedBytes: Long) {
        /** 是否是空操作 / Whether nothing was reclaimed. */
        val isEmpty: Boolean get() = deletedEntries == 0 || freedBytes <= 0
    }

    /**
     * 执行一次回收；运行时未部署时直接返回空结果。
     *
     * Runs one sweep; returns an empty summary when no runtime is deployed.
     *
     * @return 删除条目数与释放字节数 / deleted entry count and freed bytes
     */
    fun clean(): Summary {
        // 没有清单说明运行时还没装好（或正在首次解压），任何删除都可能打断部署。
        if (!File(rootfsDir, "BUILD_MANIFEST").isFile) {
            Timber.d("StorageJanitor: 运行时未就绪，跳过")
            return Summary(0, 0)
        }
        var entries = 0
        var bytes = 0L

        fun accumulate(result: Pair<Int, Long>) {
            entries += result.first
            bytes += result.second
        }

        // 1. 按时间删：日志与截图保留 7 天，guest 临时目录保留 3 天。
        accumulate(sweep(File(rootfsDir, "opt/azurpilot/log"), LOG_KEEP_DAYS))
        accumulate(sweep(File(rootfsDir, "opt/azurpilot/screenshot"), LOG_KEEP_DAYS))
        accumulate(sweep(File(rootfsDir, "tmp"), TMP_KEEP_DAYS))
        accumulate(sweep(File(rootfsDir, "var/tmp"), TMP_KEEP_DAYS))

        // 2. 按容量收：时间还没到但总量已经过线的，从最旧的开始删到目标线。
        //    时间与容量两道闸是互补的——连续跑一周高强度任务，7 天的日志也可能上 G。
        accumulate(trimToLimit(File(rootfsDir, "opt/azurpilot/log"), LOG_LIMIT_BYTES, LOG_TARGET_BYTES))

        // 3. 孤儿：更新链路异常退出后留下的占位目录与下载残包。
        accumulate(deleteOrphan(File(filesDir, "rootfs.tmp"), ORPHAN_STAGING_MS))
        accumulate(deleteOrphan(File(filesDir, "rootfs.previous"), ORPHAN_PREVIOUS_MS))
        accumulate(deleteOrphan(File(filesDir, ARCHIVE_NAME), ORPHAN_ARCHIVE_MS))

        if (entries > 0) {
            Timber.i(
                "StorageJanitor: 回收 %d 项，释放 %s",
                entries,
                humanBytes(bytes),
            )
        } else {
            Timber.d("StorageJanitor: 无需回收")
        }
        return Summary(entries, bytes)
    }

    /**
     * 递归删除早于保留期的文件，并清掉因此变空的目录。
     *
     * @param dir 目标目录（不存在时直接返回）
     * @param keepDays 保留天数
     * @return (删除条目数, 释放字节数)
     */
    private fun sweep(dir: File, keepDays: Long): Pair<Int, Long> {
        if (!dir.isDirectory) return 0 to 0L
        val cutoff = clock() - TimeUnit.DAYS.toMillis(keepDays)
        var count = 0
        var freed = 0L
        val directories = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    directories.add(child)
                    stack.addLast(child)
                } else if (child.lastModified() < cutoff) {
                    val length = child.length()
                    if (child.delete()) {
                        count++
                        freed += length
                    }
                }
            }
        }
        // 自底向上删空目录：路径更长的先处理，父目录才有可能变空。
        directories.sortByDescending { it.path.length }
        for (candidate in directories) {
            if (candidate.list()?.isEmpty() == true) candidate.delete()
        }
        return count to freed
    }

    /**
     * 目录总量超过 [limitBytes] 时，按最后修改时间从旧到新删到 [targetBytes]。
     *
     * @param dir 目标目录
     * @param limitBytes 触发阈值
     * @param targetBytes 收敛目标
     * @return (删除条目数, 释放字节数)
     */
    private fun trimToLimit(dir: File, limitBytes: Long, targetBytes: Long): Pair<Int, Long> {
        if (!dir.isDirectory) return 0 to 0L
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return 0 to 0L
        var count = 0
        var freed = 0L
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= targetBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                count++
                freed += length
            }
        }
        return count to freed
    }

    /**
     * 删除超过 [maxAgeMs] 未修改的孤儿路径（目录整棵、文件单个）。
     *
     * 判断依据是 mtime 而不是存在与否：更新链路正在写的时候 mtime 一直是新的，
     * 只有异常退出才会停在那里变旧。
     *
     * @param path 候选路径
     * @param maxAgeMs 允许保持不变的时长
     * @return (删除条目数, 释放字节数)
     */
    private fun deleteOrphan(path: File, maxAgeMs: Long): Pair<Int, Long> {
        if (!path.exists()) return 0 to 0L
        if (clock() - path.lastModified() < maxAgeMs) return 0 to 0L
        val size = if (path.isDirectory) {
            path.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            path.length()
        }
        val removed = if (path.isDirectory) path.deleteRecursively() else path.delete()
        if (!removed) return 0 to 0L
        Timber.i("StorageJanitor: 清理孤儿 %s（%s）", path.name, humanBytes(size))
        return 1 to size
    }

    /** 人读字节数 / Formats a byte count for humans. */
    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes.toDouble() / 1024 / 1024 / 1024)
    }

    private companion object {
        /** 运行时日志与截图保留天数（与宿主 LogCleaner 对齐）/ retention for runtime logs and screenshots. */
        const val LOG_KEEP_DAYS = 7L

        /** guest 临时目录保留天数 / retention for guest temp dirs. */
        const val TMP_KEEP_DAYS = 3L

        /** 日志目录触发回收的阈值 1.2 GB / threshold that triggers the size-based trim. */
        const val LOG_LIMIT_BYTES = 1_200L * 1024 * 1024

        /** 日志目录收敛目标 800 MB / target size after a trim. */
        const val LOG_TARGET_BYTES = 800L * 1024 * 1024

        /** staging 目录被视为孤儿的时长：整包更新最长约 30 分钟 / staging is orphaned after 6 h. */
        const val ORPHAN_STAGING_MS = 6L * 60 * 60 * 1000

        /** 回滚副本保留一天，够用户发现更新异常后手动回退 / keep the rollback copy for a day. */
        const val ORPHAN_PREVIOUS_MS = 24L * 60 * 60 * 1000

        /** 下载残包保留一天 / keep a stale archive for a day. */
        const val ORPHAN_ARCHIVE_MS = 24L * 60 * 60 * 1000

        /** 整包更新下载的中转文件名，与 RuntimeAutoUpdater 保持一致 / the staging archive name. */
        const val ARCHIVE_NAME = "rootfs-autoupdate.tar.xz"
    }
}
