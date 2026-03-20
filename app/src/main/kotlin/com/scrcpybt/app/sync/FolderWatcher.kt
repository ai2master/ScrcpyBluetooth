package com.scrcpybt.app.sync

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.scrcpybt.common.sync.IgnorePattern
import java.io.File

/**
 * 文件夹监视器：使用 Android FileObserver 监视文件夹变化 | Folder Watcher: Watches folder for changes using Android FileObserver
 *
 * 工作原理 | How it works:
 * - 使用递归 FileObserver 监控所有子目录 | Uses recursive FileObserver to monitor all subdirectories
 * - 累积变化并在延迟后批量通知 | Accumulates changes and notifies after delay to batch operations
 * - 尊重忽略模式（.syncignore）| Respects ignore patterns (.syncignore)
 * - 自动跳过 .stversions/ 目录 | Automatically skips .stversions/ directory
 *
 * 监听事件 | Monitored Events:
 * - CREATE: 文件/目录创建 | File/directory created
 * - DELETE: 文件/目录删除 | File/directory deleted
 * - MODIFY: 文件内容修改 | File content modified
 * - MOVE: 文件/目录移动 | File/directory moved
 * - CLOSE_WRITE: 写入完成 | Write completed
 *
 * 性能优化 | Performance Optimizations:
 * - 延迟通知批处理多个变化 | Delay notification batches multiple changes
 * - 新建目录时自动添加观察器 | Automatically adds observer for new directories
 * - 过滤忽略的文件减少不必要通知 | Filters ignored files to reduce unnecessary notifications
 *
 * @property folderPath 要监视的文件夹路径 | Folder path to watch
 * @property delaySec 变化累积延迟（秒）| Change accumulation delay in seconds
 * @property ignorePattern 忽略模式 | Ignore pattern
 * @property listener 变化检测监听器 | Change detection listener
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class FolderWatcher(
    private val folderPath: String,
    private val delaySec: Int,
    private val ignorePattern: IgnorePattern,
    private val listener: Listener
) {
    /**
     * 变化检测监听器接口 | Change detection listener interface
     */
    interface Listener {
        /**
         * 检测到变化时触发 | Triggered when changes are detected
         * @param changedPaths 变化的文件路径集合 | Set of changed file paths
         */
        fun onChangesDetected(changedPaths: Set<String>)
    }

    companion object {
        private const val TAG = "FolderWatcher"

        // Events we care about
        private const val MASK = FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.MODIFY or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF
    }

    private val folder = File(folderPath)
    private val changedPaths = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var notifyRunnable: Runnable? = null
    private var observer: RecursiveFileObserver? = null
    private val lock = Any()

    fun start() {
        if (!folder.exists() || !folder.isDirectory) {
            Log.w(TAG, "Folder does not exist: $folderPath")
            return
        }

        observer = RecursiveFileObserver(folder)
        observer?.startWatching()
        Log.i(TAG, "Started watching: $folderPath")
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
        handler.removeCallbacks(notifyRunnable ?: return)
        Log.i(TAG, "Stopped watching: $folderPath")
    }

    private fun onFileChanged(path: String) {
        // Check if ignored
        val rootFile = File(folderPath)
        val file = File(path)
        val relativePath = try {
            file.relativeTo(rootFile).path.replace('\\', '/')
        } catch (e: Exception) {
            return
        }

        if (ignorePattern.isIgnored(relativePath)) {
            return
        }

        // Skip .stversions
        if (relativePath.startsWith(".stversions/")) {
            return
        }

        synchronized(lock) {
            changedPaths.add(relativePath)

            // Cancel pending notification
            notifyRunnable?.let { handler.removeCallbacks(it) }

            // Schedule new notification after delay
            notifyRunnable = Runnable {
                synchronized(lock) {
                    if (changedPaths.isNotEmpty()) {
                        val paths = changedPaths.toSet()
                        changedPaths.clear()
                        Log.d(TAG, "Notifying ${paths.size} changes")
                        listener.onChangesDetected(paths)
                    }
                }
            }
            handler.postDelayed(notifyRunnable!!, delaySec * 1000L)
        }
    }

    /**
     * Recursive file observer that monitors all subdirectories.
     */
    private inner class RecursiveFileObserver(private val root: File) {
        private val observers = mutableListOf<SingleFileObserver>()

        init {
            addObservers(root)
        }

        private fun addObservers(directory: File) {
            if (!directory.isDirectory) return

            // Add observer for this directory
            observers.add(SingleFileObserver(directory.absolutePath))

            // Recursively add observers for subdirectories
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    addObservers(file)
                }
            }
        }

        fun startWatching() {
            observers.forEach { it.startWatching() }
        }

        fun stopWatching() {
            observers.forEach { it.stopWatching() }
            observers.clear()
        }
    }

    /**
     * Single directory observer.
     */
    private inner class SingleFileObserver(path: String) : FileObserver(path, MASK) {
        private val dirPath = path

        override fun onEvent(event: Int, path: String?) {
            if (path == null) return

            val fullPath = File(dirPath, path).absolutePath

            when (event and ALL_EVENTS) {
                CREATE -> {
                    Log.v(TAG, "Created: $path")
                    onFileChanged(fullPath)

                    // If a directory was created, start watching it
                    val file = File(fullPath)
                    if (file.isDirectory) {
                        observer?.let { obs ->
                            // Recreate observer to include new directory
                            stop()
                            start()
                        }
                    }
                }
                DELETE, DELETE_SELF -> {
                    Log.v(TAG, "Deleted: $path")
                    onFileChanged(fullPath)
                }
                MODIFY, CLOSE_WRITE -> {
                    Log.v(TAG, "Modified: $path")
                    onFileChanged(fullPath)
                }
                MOVED_FROM, MOVED_TO, MOVE_SELF -> {
                    Log.v(TAG, "Moved: $path")
                    onFileChanged(fullPath)

                    // If a directory was moved, recreate observer
                    val file = File(fullPath)
                    if (file.isDirectory) {
                        observer?.let { obs ->
                            stop()
                            start()
                        }
                    }
                }
            }
        }
    }
}
