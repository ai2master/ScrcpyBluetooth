package com.scrcpybt.server.capture

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import com.scrcpybt.common.util.Logger
import java.nio.ByteBuffer

/**
 * 屏幕捕获实现
 *
 * 通过反射调用 SurfaceControl 隐藏 API 或虚拟显示器实现屏幕捕获。
 *
 * ### 两种运行模式
 *
 * **1. 镜像模式（默认）**
 * - 创建 SurfaceControl 显示器，镜像物理屏幕（layer stack 0）
 * - 这是传统的 scrcpy 实现方式
 * - 远程用户看到的内容与物理屏幕完全一致
 *
 * **2. 虚拟显示器模式**
 * - 从 VirtualDisplayManager 创建的虚拟显示器捕获
 * - 虚拟显示器直接渲染到我们的 Surface，无需 SurfaceControl.createDisplay
 * - 物理屏幕可以保持关闭/锁定状态
 * - 捕获的内容仅存在于虚拟显示器上
 * - 物理用户看不到任何内容（或仅看到锁屏）
 * - 实现类似 Windows RDP 的行为
 *
 * ### 技术实现
 * - 使用 SurfaceTexture 接收屏幕帧
 * - 通过 OpenGL ES 2.0 进行像素回读
 * - 使用 EGL 上下文管理 OpenGL 环境
 * - 事件驱动的帧捕获（OnFrameAvailableListener）
 *
 * @see VirtualDisplayManager
 * @see ScreenCapture
 */
class SurfaceControlCapture : ScreenCapture {

    /** 显示器信息（分辨率、DPI、旋转） */
    private val displayInfo = DisplayInfo()

    /** 捕获运行状态 */
    @Volatile
    private var running = false

    /** 捕获处理线程 */
    private var handlerThread: HandlerThread? = null

    /** 捕获处理器 */
    private var handler: Handler? = null

    /** SurfaceControl 显示器令牌（仅镜像模式使用） */
    private var displayToken: IBinder? = null

    /** 用于接收帧的 SurfaceTexture */
    private var surfaceTexture: SurfaceTexture? = null

    /** 渲染目标 Surface */
    private var surface: Surface? = null

    /** OpenGL EGL 显示器（用于像素回读） */
    private var eglDisplay: EGLDisplay? = null

    /** OpenGL EGL 上下文 */
    private var eglContext: EGLContext? = null

    /** OpenGL EGL Surface */
    private var eglSurface: EGLSurface? = null

    /** 是否为虚拟显示器模式（如果设置，从虚拟显示器捕获而非镜像物理屏幕） */
    private var virtualDisplayMode = false

    /**
     * 获取捕获使用的 Surface
     *
     * 在虚拟显示器模式下，VirtualDisplayManager 会渲染到这个 Surface。
     *
     * @return 渲染目标 Surface，如果尚未初始化则返回 null
     */
    fun getSurface(): Surface? = surface

    /**
     * 启用虚拟显示器捕获模式
     *
     * 必须在 start() 之前调用。
     * 在此模式下，调用者负责创建一个渲染到我们 Surface 的虚拟显示器
     * （通过 getSurface() 获取，需在 start() 之后）。
     *
     * @param enabled 是否启用虚拟显示器模式
     */
    fun setVirtualDisplayMode(enabled: Boolean) {
        virtualDisplayMode = enabled
    }

    /**
     * 开始捕获帧
     *
     * 使用物理显示器的默认分辨率。
     *
     * - 镜像模式：创建镜像物理屏幕的 SurfaceControl 显示器
     * - 虚拟显示器模式：仅设置 SurfaceTexture/OpenGL 管线，调用者需创建虚拟显示器
     *
     * @param callback 帧回调接口，用于接收捕获的帧数据
     */
    override fun start(callback: ScreenCapture.FrameCallback) {
        start(callback, displayInfo.getEffectiveWidth(), displayInfo.getEffectiveHeight())
    }

    /**
     * 开始捕获帧（指定分辨率）
     *
     * 用于虚拟显示器模式，可以使用与物理屏幕不同的分辨率。
     *
     * @param callback 帧回调接口
     * @param width 捕获宽度
     * @param height 捕获高度
     */
    fun start(callback: ScreenCapture.FrameCallback, width: Int, height: Int) {
        Logger.i(TAG, "Starting capture: ${width}x$height (virtualDisplay=$virtualDisplayMode)")

        handlerThread = HandlerThread("ScreenCapture").apply {
            start()
            handler = Handler(looper)
        }

        // 初始化 EGL 用于像素回读
        initEGL(width, height)

        // 创建 SurfaceTexture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        surfaceTexture = SurfaceTexture(textures[0]).apply {
            setDefaultBufferSize(width, height)
        }
        surface = Surface(surfaceTexture)

        if (!virtualDisplayMode) {
            // 镜像模式：创建镜像物理屏幕的 SurfaceControl 显示器
            createMirrorDisplay(width, height)
        }
        // 虚拟显示器模式：调用者将创建渲染到我们 Surface 的虚拟显示器

        running = true

        // 设置帧可用监听器（事件驱动捕获）
        surfaceTexture?.setOnFrameAvailableListener({ texture ->
            if (!running) return@setOnFrameAvailableListener
            try {
                texture.updateTexImage()
                val pixels = readPixels(width, height)
                callback.onFrame(pixels, width, height)
            } catch (e: Exception) {
                Logger.e(TAG, "Frame capture error", e)
                callback.onError(e)
            }
        }, handler)
    }

    override fun stop() {
        running = false

        surfaceTexture?.apply {
            setOnFrameAvailableListener(null)
            release()
        }
        surface?.release()

        if (!virtualDisplayMode) {
            displayToken?.let { destroyMirrorDisplay() }
        }

        handlerThread?.quitSafely()
        cleanupEGL()

        Logger.i(TAG, "Capture stopped")
    }

    override fun getDisplayInfo(): DisplayInfo = displayInfo

    /**
     * 创建镜像物理屏幕的 SurfaceControl 显示器
     *
     * 通过反射调用 SurfaceControl 隐藏 API，创建一个镜像 layer stack 0（物理屏幕）的虚拟显示器。
     * 这是传统的 scrcpy 镜像实现方式。
     *
     * ### 反射调用的 API
     * - `SurfaceControl.createDisplay(String, boolean)` - 创建显示器
     * - `SurfaceControl.setDisplaySurface(IBinder, Surface)` - 设置渲染目标
     * - `SurfaceControl.setDisplayProjection(...)` - 设置投影矩阵
     * - `SurfaceControl.setDisplayLayerStack(IBinder, int)` - 设置图层栈（0 = 物理屏幕）
     *
     * @param width 显示器宽度
     * @param height 显示器高度
     */
    private fun createMirrorDisplay(width: Int, height: Int) {
        val surfaceControlClass = Class.forName("android.view.SurfaceControl")

        // SurfaceControl.createDisplay(String name, boolean secure)
        val createDisplay = surfaceControlClass.getMethod(
            "createDisplay", String::class.java, Boolean::class.javaPrimitiveType
        )
        displayToken = createDisplay.invoke(null, "scrcpy_bt", false) as IBinder

        // Open transaction
        val openTransaction = surfaceControlClass.getMethod("openTransaction")
        openTransaction.invoke(null)

        try {
            val setDisplaySurface = surfaceControlClass.getMethod(
                "setDisplaySurface", IBinder::class.java, Surface::class.java
            )
            setDisplaySurface.invoke(null, displayToken, surface)

            val sourceRect = Rect(0, 0, width, height)
            val destRect = Rect(0, 0, width, height)
            val setDisplayProjection = surfaceControlClass.getMethod(
                "setDisplayProjection",
                IBinder::class.java, Int::class.javaPrimitiveType,
                Rect::class.java, Rect::class.java
            )
            setDisplayProjection.invoke(null, displayToken, 0, sourceRect, destRect)

            // Layer stack 0 = 默认物理显示器
            val setDisplayLayerStack = surfaceControlClass.getMethod(
                "setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType
            )
            setDisplayLayerStack.invoke(null, displayToken, 0)
        } finally {
            val closeTransaction = surfaceControlClass.getMethod("closeTransaction")
            closeTransaction.invoke(null)
        }

        Logger.i(TAG, "Mirror display created (layer stack 0)")
    }

    /**
     * 销毁镜像显示器
     *
     * 通过反射调用 SurfaceControl.destroyDisplay 释放镜像显示器资源。
     */
    private fun destroyMirrorDisplay() {
        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val destroyDisplay = surfaceControlClass.getMethod(
                "destroyDisplay", IBinder::class.java
            )
            destroyDisplay.invoke(null, displayToken)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to destroy mirror display", e)
        }
    }

    /**
     * 初始化 EGL 环境
     *
     * 创建 OpenGL ES 2.0 上下文用于从 SurfaceTexture 回读像素数据。
     * 使用 PBuffer Surface 作为离屏渲染目标。
     *
     * @param width Surface 宽度
     * @param height Surface 高度
     */
    private fun initEGL(width: Int, height: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    /**
     * 从 OpenGL 回读像素数据
     *
     * 使用 glReadPixels 读取帧缓冲区内容，并执行以下转换：
     * 1. RGBA 格式转换为 ARGB 格式
     * 2. 垂直翻转（OpenGL 坐标系原点在左下角）
     *
     * @param width 帧宽度
     * @param height 帧高度
     * @return ARGB 格式的像素数组
     */
    private fun readPixels(width: Int, height: Int): IntArray {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        // 将 RGBA 转换为 ARGB
        val pixels = IntArray(width * height)
        buffer.rewind()
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        // OpenGL 从底部向上读取，需要垂直翻转
        flipVertically(pixels, width, height)

        return pixels
    }

    /**
     * 垂直翻转像素数组
     *
     * 交换图像的上下行，将 OpenGL 坐标系转换为标准图像坐标系。
     *
     * @param pixels 像素数组（原地修改）
     * @param width 图像宽度
     * @param height 图像高度
     */
    private fun flipVertically(pixels: IntArray, width: Int, height: Int) {
        val temp = IntArray(width)
        for (y in 0 until height / 2) {
            val topOffset = y * width
            val bottomOffset = (height - 1 - y) * width
            System.arraycopy(pixels, topOffset, temp, 0, width)
            System.arraycopy(pixels, bottomOffset, pixels, topOffset, width)
            System.arraycopy(temp, 0, pixels, bottomOffset, width)
        }
    }

    /**
     * 清理 EGL 资源
     *
     * 销毁 EGL Surface、Context 和 Display，释放 OpenGL 相关资源。
     */
    private fun cleanupEGL() {
        eglDisplay?.let { display ->
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                eglContext?.let { EGL14.eglDestroyContext(display, it) }
                EGL14.eglTerminate(display)
            }
        }
    }

    companion object {
        private const val TAG = "SurfaceControlCapture"
    }
}
