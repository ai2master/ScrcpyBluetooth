package com.scrcpybt.server.capture

/**
 * Interface for screen capture implementations.
 */
interface ScreenCapture {

    /**
     * Start capturing frames. The callback will be invoked on a background thread
     * each time a new frame is available (event-driven via onFrameAvailable).
     *
     * @param callback Frame callback
     */
    fun start(callback: FrameCallback)

    /**
     * Stop capturing.
     */
    fun stop()

    /**
     * Get display information.
     */
    fun getDisplayInfo(): DisplayInfo

    interface FrameCallback {
        /**
         * Called when a new frame is available.
         *
         * @param pixels ARGB pixel array (width * height)
         * @param width  Frame width
         * @param height Frame height
         */
        fun onFrame(pixels: IntArray, width: Int, height: Int)

        fun onError(e: Exception)
    }
}
