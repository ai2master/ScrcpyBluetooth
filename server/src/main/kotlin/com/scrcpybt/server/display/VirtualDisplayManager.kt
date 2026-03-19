package com.scrcpybt.server.display

import android.os.IBinder
import android.view.Surface
import com.scrcpybt.common.util.Logger
import java.lang.reflect.Method

/**
 * 虚拟显示器管理器
 *
 * 通过反射调用 Android DisplayManager 隐藏 API 创建和管理虚拟显示器。
 * 虚拟显示器是实现 RDP 式远程控制的核心技术。
 *
 * ### 虚拟显示器的作用
 * - 创建独立的显示环境，与物理屏幕隔离
 * - 将输入事件路由到隔离的显示环境
 * - 物理屏幕关闭时保持远程会话活跃（隐私保护）
 * - 提供独立的"第二屏幕"用于远程控制，不影响物理显示器
 *
 * ### 技术实现
 * 参考 scrcpy 的 NewDisplayCapture 实现，通过反射访问隐藏的 Android 系统服务。
 * 由于以 shell UID (2000) 运行（通过 app_process），可以访问系统级 API。
 *
 * ### 创建流程
 * 1. 通过 ServiceManager 获取 IDisplayManager 服务
 * 2. 根据 Android 版本选择合适的反射方法创建虚拟显示器
 * 3. 跟踪系统分配的显示器 ID，用于输入注入路由
 * 4. 可选：独立控制物理显示器的电源状态
 *
 * ### 版本兼容性
 * - Android 14+ (API 34): 使用 VirtualDisplayConfig.Builder
 * - Android 10-13 (API 29-33): 使用传统的 createVirtualDisplay 方法签名
 * - Android <10: 不支持
 *
 * ### 显示器标志
 * 虚拟显示器创建时可以设置多个标志来控制行为：
 * - PUBLIC: 允许其他应用显示内容
 * - SUPPORTS_TOUCH: 支持触摸输入
 * - SHOULD_SHOW_SYSTEM_DECORATIONS: 显示系统装饰（状态栏、导航栏、启动器）
 * - TRUSTED: 受信任的显示器（Android 13+）
 * - OWN_FOCUS: 独立焦点管理（Android 14+）
 * - ALWAYS_UNLOCKED: 始终解锁状态（Android 13+）
 *
 * @see DisplayManager
 * @see VirtualDisplay
 */
class VirtualDisplayManager {

    companion object {
        private const val TAG = "VirtualDisplayManager"

        /** 虚拟显示器标志 - 这些是 Android 框架的隐藏常量 */
        /** 允许其他应用显示内容 */
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0                          // 1
        /** 演示模式 */
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1                    // 2
        /** 仅显示自己的内容 */
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3               // 8
        /** 支持触摸输入 */
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6                 // 64
        /** 随内容旋转 */
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7           // 128
        /** 移除时销毁内容 */
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8     // 256
        /** 显示系统装饰（启动器、状态栏、导航栏） */
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9 // 512
        /** 受信任的显示器（Android 13+） */
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10                       // 1024
        /** 独立显示组（Android 13+） */
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11             // 2048
        /** 始终解锁（Android 13+） */
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12               // 4096
        /** 禁用触摸反馈（Android 13+） */
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13       // 8192
        /** 独立焦点（Android 14+） */
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14                     // 16384

        /** 物理显示器电源模式 - 关闭 */
        private const val POWER_MODE_OFF = 0
        /** 物理显示器电源模式 - 待机 */
        private const val POWER_MODE_DOZE = 1
        /** 物理显示器电源模式 - 正常 */
        private const val POWER_MODE_ON = 2

        /** 虚拟显示器名称 */
        private const val VIRTUAL_DISPLAY_NAME = "scrcpy-bt"
    }

    /** 系统分配的虚拟显示器 ID（未创建时为 -1） */
    var virtualDisplayId: Int = -1
        private set

    /** 虚拟显示器模式是否处于活动状态 */
    val isActive: Boolean get() = virtualDisplayId >= 0

    /** 缓存的反射对象 - DisplayManager 服务 */
    private var displayManagerService: Any? = null

    /** 缓存的反射对象 - VirtualDisplay 对象 */
    private var virtualDisplayObject: Any? = null

    /** 缓存的反射对象 - 回调对象 */
    private var callbackObject: Any? = null

    /** 跟踪物理显示器状态 */
    private var physicalDisplayOff: Boolean = false

    /**
     * 创建指定尺寸的虚拟显示器
     *
     * 通过反射使用 DisplayManager 隐藏 API。
     *
     * ### 多版本策略
     * 此方法尝试不同 Android 版本的多种策略：
     * - Android 14+ (API 34): 使用 VirtualDisplayConfig.Builder
     * - Android 10-13 (API 29-33): 使用传统的 createVirtualDisplay 方法签名
     *
     * ### 显示器配置
     * 虚拟显示器会配置以下特性：
     * - 支持触摸输入
     * - 可选显示系统装饰（启动器、导航栏、状态栏）
     * - 独立的显示组和焦点（Android 13+）
     * - 始终解锁状态（Android 13+）
     *
     * @param width 显示器宽度（像素）
     * @param height 显示器高度（像素）
     * @param dpi 显示器密度（例如：320 代表 xhdpi）
     * @param surface 渲染目标 Surface（来自 SurfaceTexture 或 MediaCodec）
     * @param showSystemDecorations 是否显示系统装饰（启动器、导航栏、状态栏）
     * @return 显示器 ID，失败时返回 -1
     */
    fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        showSystemDecorations: Boolean = true
    ): Int {
        if (isActive) {
            Logger.w(TAG, "Virtual display already active (ID: $virtualDisplayId)")
            return virtualDisplayId
        }

        Logger.i(TAG, "Creating virtual display: ${width}x${height}@${dpi}dpi, decorations=$showSystemDecorations")

        try {
            // 步骤 1: 获取 IDisplayManager 服务
            displayManagerService = getDisplayManagerService()
                ?: return logError("Failed to get DisplayManager service")

            // 步骤 2: 根据 Android 版本使用适当的创建方法
            val sdkInt = android.os.Build.VERSION.SDK_INT
            virtualDisplayId = when {
                sdkInt >= 34 -> createVirtualDisplayApi34Plus(width, height, dpi, surface, showSystemDecorations)
                sdkInt >= 29 -> createVirtualDisplayApi29To33(width, height, dpi, surface, showSystemDecorations)
                else -> logError("Unsupported Android version: $sdkInt (requires API 29+)")
            }

            if (virtualDisplayId >= 0) {
                Logger.i(TAG, "Virtual display created successfully: ID=$virtualDisplayId")
            } else {
                Logger.e(TAG, "Failed to create virtual display")
            }

            return virtualDisplayId

        } catch (e: Exception) {
            Logger.e(TAG, "Exception creating virtual display: ${e.message}", e)
            return -1
        }
    }

    /**
     * 获取 IDisplayManager 服务
     *
     * 通过 ServiceManager 反射获取 DisplayManager 系统服务。
     *
     * @return IDisplayManager 服务实例，失败时返回 null
     */
    private fun getDisplayManagerService(): Any? {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val displayBinder = getService.invoke(null, "display") as IBinder

            val iDisplayManagerStub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterface = iDisplayManagerStub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, displayBinder)

            Logger.d(TAG, "Got IDisplayManager service: ${service?.javaClass?.name}")
            return service

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to get DisplayManager service: ${e.message}", e)
            return null
        }
    }

    /**
     * 为 Android 14+ (API 34+) 创建虚拟显示器
     *
     * 使用 VirtualDisplayConfig.Builder 新 API。
     * 这是 Android 14 引入的统一配置方式，支持更多标志和选项。
     *
     * @param width 显示器宽度
     * @param height 显示器高度
     * @param dpi 显示器密度
     * @param surface 渲染目标 Surface
     * @param showSystemDecorations 是否显示系统装饰
     * @return 显示器 ID，失败时返回 -1
     */
    private fun createVirtualDisplayApi34Plus(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        showSystemDecorations: Boolean
    ): Int {
        try {
            Logger.d(TAG, "Using Android 14+ VirtualDisplayConfig approach")

            // Build VirtualDisplayConfig
            val configBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val constructor = configBuilderClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val builder = constructor.newInstance(VIRTUAL_DISPLAY_NAME, width, height, dpi)

            // Set surface
            val setSurface = configBuilderClass.getMethod("setSurface", Surface::class.java)
            setSurface.invoke(builder, surface)

            // Set flags
            var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                    VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                    VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    VIRTUAL_DISPLAY_FLAG_OWN_FOCUS

            if (showSystemDecorations) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            }

            val setFlags = configBuilderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
            setFlags.invoke(builder, flags)

            // 构建配置
            val build = configBuilderClass.getMethod("build")
            val config = build.invoke(builder)

            Logger.d(TAG, "Built VirtualDisplayConfig with flags: $flags")

            // 创建回调
            callbackObject = createVirtualDisplayCallback(
                Class.forName("android.hardware.display.IVirtualDisplayCallback\$Stub")
            )

            // 查找 createVirtualDisplay 方法
            // AIDL 中的回调参数类型是 IVirtualDisplayCallback（接口）
            // 我们尝试多种参数类型组合，因为不同版本会有变化
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val projectionClass = Class.forName("android.media.projection.IMediaProjection")

            virtualDisplayObject = tryInvokeCreateVirtualDisplay(
                configClass, projectionClass, config, surface, width, height, dpi, flags
            )

            // 从返回的对象中提取显示器 ID
            return getDisplayIdFromVirtualDisplay(virtualDisplayObject)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create virtual display (API 34+): ${e.message}", e)
            return -1
        }
    }

    /**
     * 尝试调用 createVirtualDisplay 方法
     *
     * AIDL 回调参数类型会变化：可能是 IVirtualDisplayCallback（接口）
     * 或 IVirtualDisplayCallback.Stub（抽象类），取决于 Android 版本。
     * 此方法会尝试所有可能的方法签名。
     *
     * @param configClass VirtualDisplayConfig 类
     * @param projectionClass IMediaProjection 类
     * @param config 虚拟显示器配置对象
     * @param surface 渲染目标 Surface
     * @param width 显示器宽度
     * @param height 显示器高度
     * @param dpi 显示器密度
     * @param flags 显示器标志
     * @return VirtualDisplay 对象，失败时返回 null
     */
    private fun tryInvokeCreateVirtualDisplay(
        configClass: Class<*>,
        projectionClass: Class<*>,
        config: Any,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int
    ): Any? {
        val service = displayManagerService!!

        // Try with VirtualDisplayConfig (API 34+ style)
        val callbackTypes = listOf(
            "android.hardware.display.IVirtualDisplayCallback",
            "android.hardware.display.IVirtualDisplayCallback\$Stub",
            "android.os.IBinder"
        )

        for (callbackTypeName in callbackTypes) {
            try {
                val callbackType = Class.forName(callbackTypeName)
                val createMethod = service.javaClass.getMethod(
                    "createVirtualDisplay",
                    configClass,
                    callbackType,
                    projectionClass,
                    String::class.java
                )
                Logger.d(TAG, "Found createVirtualDisplay with callback type: $callbackTypeName")

                val callbackArg = if (callbackTypeName.endsWith("IBinder") && callbackObject !is IBinder) {
                    // If method expects IBinder, pass a Binder
                    android.os.Binder()
                } else {
                    callbackObject
                }

                return createMethod.invoke(
                    service,
                    config,
                    callbackArg,
                    null, // projection
                    "com.android.shell" // packageName
                )
            } catch (_: NoSuchMethodException) {
                Logger.d(TAG, "createVirtualDisplay not found with callback type: $callbackTypeName")
            }
        }

        Logger.e(TAG, "No matching createVirtualDisplay method found for API 34+")
        return null
    }

    /**
     * Create virtual display for Android 10-13 (API 29-33) using legacy method.
     */
    private fun createVirtualDisplayApi29To33(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        showSystemDecorations: Boolean
    ): Int {
        try {
            Logger.d(TAG, "Using Android 10-13 legacy createVirtualDisplay approach")

            // Create callback
            callbackObject = createVirtualDisplayCallback(
                Class.forName("android.hardware.display.IVirtualDisplayCallback\$Stub")
            )

            // Calculate flags
            var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                    VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

            if (showSystemDecorations) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            }

            // Add API 33+ flags if supported
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED or
                        VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                        VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            }

            Logger.d(TAG, "Using flags: $flags")

            // Try to find the appropriate createVirtualDisplay method.
            // The callback parameter type in AIDL varies across versions.
            // IDisplayManager.createVirtualDisplay signature for API 29-33:
            //   int createVirtualDisplay(IVirtualDisplayCallback callback,
            //       IMediaProjection projection, String packageName, String name,
            //       int width, int height, int densityDpi, Surface surface,
            //       int flags, String uniqueId)
            val service = displayManagerService!!
            val projectionClass = Class.forName("android.media.projection.IMediaProjection")

            // Try different callback types and method signatures
            val callbackTypes = listOf(
                "android.hardware.display.IVirtualDisplayCallback",
                "android.hardware.display.IVirtualDisplayCallback\$Stub",
                "android.os.IBinder"
            )

            for (callbackTypeName in callbackTypes) {
                try {
                    val callbackType = Class.forName(callbackTypeName)

                    val callbackArg = if (callbackTypeName.endsWith("IBinder") && callbackObject !is IBinder) {
                        android.os.Binder()
                    } else {
                        callbackObject
                    }

                    // Try with uniqueId parameter (10 params)
                    val result = tryLegacyCreate(
                        service, callbackType, projectionClass, callbackArg,
                        width, height, dpi, surface, flags, withUniqueId = true
                    ) ?: tryLegacyCreate(
                        service, callbackType, projectionClass, callbackArg,
                        width, height, dpi, surface, flags, withUniqueId = false
                    )

                    if (result != null) {
                        virtualDisplayObject = result
                        return getDisplayIdFromVirtualDisplay(result)
                    }
                } catch (_: ClassNotFoundException) {
                    Logger.d(TAG, "Callback type not found: $callbackTypeName")
                }
            }

            Logger.e(TAG, "No matching createVirtualDisplay method found for API 29-33")
            return -1

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create virtual display (API 29-33): ${e.message}", e)
            return -1
        }
    }

    /**
     * Try to call the legacy createVirtualDisplay with specific parameter types.
     */
    private fun tryLegacyCreate(
        service: Any,
        callbackType: Class<*>,
        projectionClass: Class<*>,
        callbackArg: Any?,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        withUniqueId: Boolean
    ): Any? {
        return try {
            val paramTypes = mutableListOf<Class<*>>(
                callbackType,
                projectionClass,
                String::class.java, // packageName
                String::class.java, // name
                Int::class.javaPrimitiveType!!, // width
                Int::class.javaPrimitiveType!!, // height
                Int::class.javaPrimitiveType!!, // densityDpi
                Surface::class.java,
                Int::class.javaPrimitiveType!! // flags
            )
            if (withUniqueId) {
                paramTypes.add(String::class.java) // uniqueId
            }

            val createMethod = service.javaClass.getMethod(
                "createVirtualDisplay",
                *paramTypes.toTypedArray()
            )

            val args = mutableListOf<Any?>(
                callbackArg,
                null, // projection
                "com.android.shell", // packageName
                VIRTUAL_DISPLAY_NAME, // name
                width,
                height,
                dpi,
                surface,
                flags
            )
            if (withUniqueId) {
                args.add(null) // uniqueId
            }

            Logger.d(TAG, "Invoking legacy createVirtualDisplay (uniqueId=$withUniqueId, callback=${callbackType.simpleName})")
            createMethod.invoke(service, *args.toTypedArray())
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    /**
     * Create a minimal IVirtualDisplayCallback implementation.
     *
     * IVirtualDisplayCallback.Stub is an abstract Binder class, not an interface,
     * so we can't use Proxy.newProxyInstance(). Instead we look for the AIDL
     * interface (IVirtualDisplayCallback) and proxy that, then wrap it with
     * a Stub via the Stub constructor approach.
     *
     * However, in practice, IDisplayManager.createVirtualDisplay accepts
     * IVirtualDisplayCallback (the interface), not necessarily the Stub.
     * We try multiple approaches:
     *
     * 1. Create a concrete subclass of IVirtualDisplayCallback.Stub at runtime
     *    using the fact that the abstract methods are simple no-ops
     * 2. Fallback: use Proxy on the IVirtualDisplayCallback interface directly
     */
    private fun createVirtualDisplayCallback(stubClass: Class<*>): Any {
        // Approach 1: Try to find a concrete no-op implementation or
        // create one using the interface + Proxy approach
        // The AIDL interface is IVirtualDisplayCallback (without $Stub)
        try {
            val interfaceClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                interfaceClass.classLoader,
                arrayOf(interfaceClass),
                java.lang.reflect.InvocationHandler { _, method, _ ->
                    when (method.name) {
                        "asBinder" -> {
                            // Return a dummy Binder
                            android.os.Binder()
                        }
                        "onPaused" -> Logger.d(TAG, "VirtualDisplay paused")
                        "onResumed" -> Logger.d(TAG, "VirtualDisplay resumed")
                        "onStopped" -> {
                            Logger.d(TAG, "VirtualDisplay stopped")
                            virtualDisplayId = -1
                        }
                        else -> Logger.d(TAG, "VirtualDisplayCallback: ${method.name}")
                    }
                    null
                }
            )
            Logger.d(TAG, "Created VirtualDisplayCallback via interface proxy")
            return proxy
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to create interface proxy: ${e.message}", e)
        }

        // Approach 2: Try to instantiate the Stub directly.
        // On some Android versions, Stub may have a default constructor and
        // the abstract methods might have default implementations from AIDL generation.
        try {
            // Try using Binder-based approach: create a Binder and wrap it
            val binder = android.os.Binder()
            // The IDisplayManager might accept IBinder directly via asInterface()
            Logger.d(TAG, "Using raw Binder as callback fallback")
            return binder
        } catch (e: Exception) {
            Logger.e(TAG, "All callback creation approaches failed", e)
            throw RuntimeException("Cannot create IVirtualDisplayCallback", e)
        }
    }

    /**
     * Extract display ID from a VirtualDisplay object returned by createVirtualDisplay.
     */
    private fun getDisplayIdFromVirtualDisplay(virtualDisplay: Any?): Int {
        if (virtualDisplay == null) {
            Logger.e(TAG, "VirtualDisplay object is null")
            return -1
        }

        try {
            // The return type is int (display ID) for IDisplayManager methods
            // If it's an Integer, just return it
            if (virtualDisplay is Int) {
                return virtualDisplay
            }

            // Otherwise it might be a VirtualDisplay wrapper object
            // Try to get the Display object and extract its ID
            val getDisplay = virtualDisplay.javaClass.getMethod("getDisplay")
            val display = getDisplay.invoke(virtualDisplay)

            if (display == null) {
                Logger.e(TAG, "Display object is null")
                return -1
            }

            val getDisplayId = display.javaClass.getMethod("getDisplayId")
            return getDisplayId.invoke(display) as Int

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to extract display ID: ${e.message}", e)

            // Try direct field access as fallback
            try {
                val field = virtualDisplay.javaClass.getDeclaredField("mDisplayId")
                field.isAccessible = true
                return field.get(virtualDisplay) as Int
            } catch (e2: Exception) {
                Logger.e(TAG, "Fallback field access failed: ${e2.message}")
            }
        }

        return -1
    }

    /**
     * Release the virtual display and clean up resources.
     */
    fun release() {
        if (!isActive) {
            Logger.d(TAG, "No active virtual display to release")
            return
        }

        Logger.i(TAG, "Releasing virtual display ID: $virtualDisplayId")

        try {
            // If we have the VirtualDisplay object, call release() on it
            virtualDisplayObject?.let { vd ->
                try {
                    val releaseMethod = vd.javaClass.getMethod("release")
                    releaseMethod.invoke(vd)
                    Logger.d(TAG, "Called release() on VirtualDisplay object")
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to call release() on VirtualDisplay: ${e.message}")
                }
            }

            // Also try to release via IDisplayManager service
            displayManagerService?.let { service ->
                try {
                    // Try releaseVirtualDisplay(IBinder token) method
                    // The callbackObject might be a Proxy, Binder, or other type
                    val binder: IBinder? = when (callbackObject) {
                        is IBinder -> callbackObject as IBinder
                        else -> {
                            // Try to call asBinder() if available
                            try {
                                val asBinder = callbackObject?.javaClass?.getMethod("asBinder")
                                asBinder?.invoke(callbackObject) as? IBinder
                            } catch (_: Exception) { null }
                        }
                    }
                    if (binder != null) {
                        val releaseMethod = service.javaClass.getMethod(
                            "releaseVirtualDisplay",
                            IBinder::class.java
                        )
                        releaseMethod.invoke(service, binder)
                        Logger.d(TAG, "Called releaseVirtualDisplay() on service")
                    }
                } catch (e: Exception) {
                    Logger.d(TAG, "Service release not available or failed: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Exception during virtual display release: ${e.message}", e)
        } finally {
            // Restore physical display if we turned it off
            if (physicalDisplayOff) {
                restorePhysicalDisplay()
            }

            // Clean up state
            virtualDisplayId = -1
            virtualDisplayObject = null
            callbackObject = null
        }
    }

    /**
     * Turn off the physical display while keeping virtual display active.
     * This is the key security feature that allows "remote unlocked, physical locked".
     *
     * Uses SurfaceControl.setDisplayPowerMode() to turn off the display at the
     * hardware level without affecting the virtual display.
     */
    fun turnOffPhysicalDisplay() {
        if (physicalDisplayOff) {
            Logger.d(TAG, "Physical display already off")
            return
        }

        if (!isActive) {
            Logger.w(TAG, "Cannot turn off physical display - no active virtual display")
            return
        }

        Logger.i(TAG, "Turning off physical display")

        try {
            val token = getPhysicalDisplayToken()
            if (token != null) {
                val surfaceControlClass = Class.forName("android.view.SurfaceControl")
                val setDisplayPowerMode = surfaceControlClass.getMethod(
                    "setDisplayPowerMode",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                )
                setDisplayPowerMode.invoke(null, token, POWER_MODE_OFF)
                physicalDisplayOff = true
                Logger.i(TAG, "Physical display turned off successfully via SurfaceControl")
                return
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to turn off physical display via SurfaceControl: ${e.message}", e)
        }

        // Fallback: use input keyevent KEYCODE_SLEEP (26)
        try {
            Logger.d(TAG, "Trying fallback method: input keyevent SLEEP")
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 26"))
            physicalDisplayOff = true
            Logger.i(TAG, "Physical display turned off via input keyevent")
        } catch (e2: Exception) {
            Logger.e(TAG, "Fallback method also failed: ${e2.message}", e2)
        }
    }

    /**
     * Restore the physical display to normal operation.
     */
    fun restorePhysicalDisplay() {
        if (!physicalDisplayOff) {
            Logger.d(TAG, "Physical display already on")
            return
        }

        Logger.i(TAG, "Restoring physical display")

        try {
            val token = getPhysicalDisplayToken()
            if (token != null) {
                val surfaceControlClass = Class.forName("android.view.SurfaceControl")
                val setDisplayPowerMode = surfaceControlClass.getMethod(
                    "setDisplayPowerMode",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                )
                setDisplayPowerMode.invoke(null, token, POWER_MODE_ON)
                physicalDisplayOff = false
                Logger.i(TAG, "Physical display restored successfully via SurfaceControl")
                return
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to restore physical display via SurfaceControl: ${e.message}", e)
        }

        // Fallback: use input keyevent KEYCODE_WAKEUP (224)
        try {
            Logger.d(TAG, "Trying fallback method: input keyevent WAKEUP")
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 224"))
            physicalDisplayOff = false
            Logger.i(TAG, "Physical display restored via input keyevent")
        } catch (e2: Exception) {
            Logger.e(TAG, "Fallback method also failed: ${e2.message}", e2)
        }
    }

    /**
     * Get the IBinder token for the primary physical display.
     *
     * The API varies across Android versions:
     * - Android 14+ (API 34): getPhysicalDisplayIds() -> getPhysicalDisplayToken(long)
     * - Android 12-13 (API 31-33): getInternalDisplayToken() (deprecated but works)
     * - Android 10-11 (API 29-30): getBuiltInDisplay(int)
     * - Legacy: getPhysicalDisplayToken(long) with hardcoded ID
     *
     * @return Physical display token, or null on failure
     */
    private fun getPhysicalDisplayToken(): IBinder? {
        val surfaceControlClass = Class.forName("android.view.SurfaceControl")

        // Strategy 1: Android 14+ getPhysicalDisplayIds() + getPhysicalDisplayToken(long)
        try {
            val getIds = surfaceControlClass.getMethod("getPhysicalDisplayIds")
            val ids = getIds.invoke(null) as LongArray
            if (ids.isNotEmpty()) {
                val getToken = surfaceControlClass.getMethod(
                    "getPhysicalDisplayToken",
                    Long::class.javaPrimitiveType
                )
                val token = getToken.invoke(null, ids[0]) as? IBinder
                if (token != null) {
                    Logger.d(TAG, "Got display token via getPhysicalDisplayIds()[0]=${ids[0]}")
                    return token
                }
            }
        } catch (e: Exception) {
            Logger.d(TAG, "getPhysicalDisplayIds() not available: ${e.message}")
        }

        // Strategy 2: getInternalDisplayToken() (Android 12-13)
        try {
            val getToken = surfaceControlClass.getMethod("getInternalDisplayToken")
            val token = getToken.invoke(null) as? IBinder
            if (token != null) {
                Logger.d(TAG, "Got display token via getInternalDisplayToken()")
                return token
            }
        } catch (e: Exception) {
            Logger.d(TAG, "getInternalDisplayToken() not available: ${e.message}")
        }

        // Strategy 3: getBuiltInDisplay(int) (Android 10-11)
        try {
            val getToken = surfaceControlClass.getMethod(
                "getBuiltInDisplay",
                Int::class.javaPrimitiveType
            )
            val token = getToken.invoke(null, 0) as? IBinder // BUILT_IN_DISPLAY_ID_MAIN = 0
            if (token != null) {
                Logger.d(TAG, "Got display token via getBuiltInDisplay(0)")
                return token
            }
        } catch (e: Exception) {
            Logger.d(TAG, "getBuiltInDisplay() not available: ${e.message}")
        }

        // Strategy 4: getPhysicalDisplayToken(long) with common hardcoded primary display ID
        try {
            val getToken = surfaceControlClass.getMethod(
                "getPhysicalDisplayToken",
                Long::class.javaPrimitiveType
            )
            val token = getToken.invoke(null, 0L) as? IBinder
            if (token != null) {
                Logger.d(TAG, "Got display token via getPhysicalDisplayToken(0L)")
                return token
            }
        } catch (e: Exception) {
            Logger.d(TAG, "getPhysicalDisplayToken(long) not available: ${e.message}")
        }

        Logger.e(TAG, "All physical display token retrieval strategies failed")
        return null
    }

    /**
     * Helper to log error and return -1.
     */
    private fun logError(message: String): Int {
        Logger.e(TAG, message)
        return -1
    }
}
