package com.azurpilot.ghio.di

import com.azurpilot.ghio.constant.AppPaths
import com.azurpilot.ghio.log.AzurPilotLogSource
import com.azurpilot.ghio.log.AppLogWriter
import com.azurpilot.ghio.log.LogCleaner
import com.azurpilot.ghio.log.LogExportService
import com.azurpilot.ghio.storage.StorageJanitor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/**
 * 日志链路绑定：app 侧写入器、AzurPilot 日志源、冷启动清理与导出服务
 *
 * Log pipeline bindings: the app-side writer, the AzurPilot log source,
 * cold-start cleanup, and the export service.
 */
val logModule = module {
    single { AppLogWriter() }
    single { AzurPilotLogSource(androidContext()) }
    single { LogCleaner(get()) }
    // 运行时目录回收：日志/截图按 7 天 + 1.2 GB 双闸，孤儿 staging 与残包按 mtime。
    // rootfs 根与宿主私有目录都从 filesDir 推，构造参数与 RootfsProvisioner 一致。
    single {
        val filesDir = androidContext().filesDir
        StorageJanitor(rootfsDir = File(filesDir, "rootfs"), filesDir = filesDir)
    }
    single {
        LogExportService(
            context = androidContext(),
            baseDir = { AppPaths.ROOT },
            launcherRoots = { listOf(AppPaths.LOG_DIR, AppPaths.DEBUG_DIR) },
            logDir = { get<AzurPilotLogSource>().logDir() },
        )
    }
}
