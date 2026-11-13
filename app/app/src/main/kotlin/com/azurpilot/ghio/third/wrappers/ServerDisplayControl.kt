package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.os.IBinder
import com.azurpilot.ghio.third.Ln
import java.lang.reflect.Method

/**
 * `com.android.server.display.DisplayControl` 的反射入口（Android 14+ 物理屏 token）
 *
 * Android 14 起 `android.view.SurfaceControl` 不再暴露可用的
 * `getPhysicalDisplayIds()`，直接反射拿不到物理屏 ID，也就没有可用的 display token
 * 去调 `setDisplayPowerMode`。系统把这两个静态方法搬进了
 * `com.android.server.display.DisplayControl`，该类在 `services.jar` 里、且依赖
 * `android_servers` 原生库，必须先用 `ClassLoaderFactory` 造出能加载系统服务的
 * 类加载器，再用 `Runtime.loadLibrary0` 把原生库挂到那个类上，否则调用会抛
 * `UnsatisfiedLinkError`。
 *
 * 仅在特权进程（root / shell）内有意义：`setDisplayPowerMode` 本身还要过
 * SurfaceFlinger 的 uid 校验。
 *
 * Reflection entry point for `com.android.server.display.DisplayControl` (physical
 * display tokens on Android 14+).
 *
 * From Android 14 on, `android.view.SurfaceControl` no longer exposes a usable
 * `getPhysicalDisplayIds()`, so a plain reflection call cannot resolve a display
 * token for `setDisplayPowerMode`. The system moved both static methods into
 * `com.android.server.display.DisplayControl`, which lives in `services.jar` and
 * depends on the `android_servers` native library: the class must be loaded through
 * a `ClassLoaderFactory`-built loader and the library attached with
 * `Runtime.loadLibrary0`, otherwise every call raises `UnsatisfiedLinkError`.
 *
 * Only meaningful inside the privileged process (root / shell): `setDisplayPowerMode`
 * itself still goes through SurfaceFlinger's uid check.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object ServerDisplayControl {

    private const val TAG = "ServerDisplay"

    private const val SERVICES_JAR = "/system/framework/services.jar"
    private const val CLASS_NAME = "com.android.server.display.DisplayControl"
    private const val NATIVE_LIBRARY = "android_servers"

    /** 类引用；加载失败为 null，调用方据此回退到旧通道 / The class reference, null when loading failed */
    private val CLASS: Class<*>? = loadClassOrNull()

    private var idsMethod: Method? = null
    private var tokenMethod: Method? = null

    /**
     * 用系统自带的类加载器工厂加载 services.jar，并挂上 android_servers 原生库
     *
     * 任一步失败都只记日志并返回 null：这条链路是增强能力，失败要让调用方回退，
     * 不能让整个特权进程起不来。
     *
     * Loads services.jar through the platform class-loader factory and attaches the
     * android_servers native library.
     *
     * Any failure is logged and yields null: this path is an enhancement, so callers
     * must be able to fall back instead of the privileged process failing to start.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun loadClassOrNull(): Class<*>? = try {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java,
            String::class.java,
            String::class.java,
            ClassLoader::class.java,
            Integer.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java,
        )
        val loader = create.invoke(
            null, SERVICES_JAR, null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
        ) as ClassLoader
        val loaded = loader.loadClass(CLASS_NAME)
        val loadLibrary0 = Runtime::class.java.getDeclaredMethod(
            "loadLibrary0", Class::class.java, String::class.java,
        )
        loadLibrary0.isAccessible = true
        loadLibrary0.invoke(Runtime.getRuntime(), loaded, NATIVE_LIBRARY)
        loaded
    } catch (t: Throwable) {
        Ln.w("$TAG: DisplayControl unavailable on this ROM: ${t.javaClass.simpleName}")
        null
    }

    /** 本条通道是否可用 / Whether this channel is usable. */
    fun isAvailable(): Boolean = CLASS != null

    private fun idsMethod(): Method {
        var method = idsMethod
        if (method == null) {
            method = CLASS!!.getMethod("getPhysicalDisplayIds")
            idsMethod = method
        }
        return method
    }

    private fun tokenMethod(): Method {
        var method = tokenMethod
        if (method == null) {
            method = CLASS!!.getMethod("getPhysicalDisplayToken", Long::class.java)
            tokenMethod = method
        }
        return method
    }

    /** 列出物理屏 ID；不可用或失败时为 null / Lists physical display ids, null when unavailable. */
    fun physicalDisplayIds(): LongArray? {
        if (CLASS == null) return null
        return try {
            idsMethod().invoke(null) as LongArray?
        } catch (e: ReflectiveOperationException) {
            Ln.e("$TAG: getPhysicalDisplayIds failed", e)
            null
        }
    }

    /** 把物理屏 ID 解析成 display token；失败时为 null / Resolves a display token, null on failure. */
    fun physicalDisplayToken(physicalDisplayId: Long): IBinder? {
        if (CLASS == null) return null
        return try {
            tokenMethod().invoke(null, physicalDisplayId) as IBinder?
        } catch (e: ReflectiveOperationException) {
            Ln.e("$TAG: getPhysicalDisplayToken failed", e)
            null
        }
    }
}
