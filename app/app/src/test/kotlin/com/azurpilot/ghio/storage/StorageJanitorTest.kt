package com.azurpilot.ghio.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 验证运行时回收只动可再生数据、按时间与容量两道闸，并且不碰未就绪的运行时。
 *
 * Verifies the janitor only touches regenerable data, honours both the age and the
 * size gate, and stays away from a runtime that is not deployed yet.
 */
class StorageJanitorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun touch(file: File, ageDays: Long): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1024))
        file.setLastModified(now - TimeUnit.DAYS.toMillis(ageDays))
        return file
    }

    private fun deployedRootfs(): File {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "BUILD_MANIFEST").writeText("{}\n")
        return rootfs
    }

    @Test
    fun `skips everything while the runtime is not deployed`() {
        val rootfs = tmp.newFolder("rootfs")
        val stale = touch(File(rootfs, "opt/azurpilot/log/2020-01-01/a.png"), 400)
        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })

        val summary = janitor.clean()

        assertEquals(0, summary.deletedEntries)
        assertTrue("未部署时不得删除任何文件", stale.exists())
    }

    @Test
    fun `drops runtime logs older than the retention window`() {
        val rootfs = deployedRootfs()
        val old = touch(File(rootfs, "opt/azurpilot/log/task/2020-01-01/a.png"), 30)
        val recent = touch(File(rootfs, "opt/azurpilot/log/task/2026-11-25/a.png"), 1)
        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })

        janitor.clean()

        assertFalse("超过 7 天的日志应被删除", old.exists())
        assertTrue("当天日志必须保留", recent.exists())
    }

    @Test
    fun `keeps user config and the venv untouched`() {
        val rootfs = deployedRootfs()
        val config = touch(File(rootfs, "opt/azurpilot/config/azurpilot.json"), 365)
        val venv = touch(File(rootfs, "opt/azurpilot-venv/lib/python3.14/site-packages/x.py"), 365)
        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })

        janitor.clean()

        assertTrue("用户配置永不在清理范围", config.exists())
        assertTrue("运行期依赖永不在清理范围", venv.exists())
    }

    @Test
    fun `trims the log tree by size even when every file is fresh`() {
        val rootfs = deployedRootfs()
        val logDir = File(rootfs, "opt/azurpilot/log/recent")
        logDir.mkdirs()
        // 造 1.5 GB 的「新鲜」日志：单文件 300 MB × 5，全部在保留期内。
        repeat(5) { index ->
            val file = File(logDir, "shot-$index.png")
            file.writeBytes(ByteArray(1))
            // 用 RandomAccessFile 撑大，避免真的分配数组
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(300L * 1024 * 1024)
            }
            file.setLastModified(now - TimeUnit.HOURS.toMillis(index + 1L))
        }
        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })

        val summary = janitor.clean()

        val remaining = logDir.listFiles()?.sumOf { it.length() } ?: 0L
        assertTrue("超过 1.2 GB 必须触发容量回收，实际 $remaining", remaining < 1_200L * 1024 * 1024)
        assertTrue("最旧的先删", !File(logDir, "shot-4.png").exists())
        assertTrue(summary.freedBytes > 0)
    }

    @Test
    fun `removes orphan staging but keeps one that is being written`() {
        val rootfs = deployedRootfs()
        val orphan = File(tmp.root, "rootfs.tmp").apply { mkdirs() }
        File(orphan, "half-extracted.bin").writeBytes(ByteArray(2048))
        orphan.setLastModified(now - TimeUnit.DAYS.toMillis(2))

        val fresh = File(tmp.root, "rootfs.previous").apply { mkdirs() }
        File(fresh, "old-rootfs.bin").writeBytes(ByteArray(2048))
        fresh.setLastModified(now - TimeUnit.HOURS.toMillis(1))

        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })
        janitor.clean()

        assertFalse("两小时前的 staging 属于孤儿", orphan.exists())
        assertTrue("一小时前的回滚副本可能还要用，不能删", fresh.exists())
    }

    @Test
    fun `removes a stale download archive`() {
        val rootfs = deployedRootfs()
        val archive = File(tmp.root, "rootfs-autoupdate.tar.xz")
        archive.writeBytes(ByteArray(4096))
        archive.setLastModified(now - TimeUnit.DAYS.toMillis(3))

        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })
        janitor.clean()

        assertFalse(archive.exists())
    }

    @Test
    fun `reports an empty summary when there is nothing to do`() {
        val rootfs = deployedRootfs()
        val janitor = StorageJanitor(rootfs, tmp.root, clock = { now })

        val summary = janitor.clean()

        assertEquals(0, summary.deletedEntries)
        assertEquals(0L, summary.freedBytes)
        assertTrue(summary.isEmpty)
    }
}
