package com.scrcpybt.app.sync

import android.util.Log
import com.scrcpybt.common.sync.SyncConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Implements file versioning strategies (modeled after Syncthing).
 *
 * Supports 4 versioning types:
 * 1. Trashcan: Move old versions to .stversions/, configurable cleanup days
 * 2. Simple: Keep N old versions in .stversions/ with timestamps
 * 3. Staggered: Keep versions at increasing time intervals
 * 4. External: Call user command with file path
 */
class FileVersioner(private val config: SyncConfig, private val syncRootPath: String) {

    companion object {
        private const val TAG = "FileVersioner"
        private const val VERSIONS_DIR = ".stversions"

        // Staggered versioning intervals
        private val STAGGERED_INTERVALS = listOf(
            Interval(30, TimeUnit.SECONDS, 3600),    // 30s for 1h
            Interval(3600, TimeUnit.SECONDS, 86400), // 1h for 1d
            Interval(86400, TimeUnit.SECONDS, 2592000), // 1d for 30d
            Interval(604800, TimeUnit.SECONDS, Long.MAX_VALUE) // 1w beyond
        )

        data class Interval(
            val intervalSec: Long,
            val unit: TimeUnit,
            val maxAgeSec: Long
        )
    }

    private val versionsDir = File(syncRootPath, VERSIONS_DIR)

    init {
        if (config.versioningType != SyncConfig.VersioningType.NONE) {
            versionsDir.mkdirs()
        }
    }

    /**
     * Version a file before it's overwritten or deleted.
     * Moves to .stversions/ directory with appropriate naming.
     */
    fun versionFile(file: File) {
        if (config.versioningType == SyncConfig.VersioningType.NONE) {
            return
        }

        if (!file.exists()) {
            return
        }

        try {
            when (config.versioningType) {
                SyncConfig.VersioningType.TRASHCAN -> trashcanVersion(file)
                SyncConfig.VersioningType.SIMPLE -> simpleVersion(file)
                SyncConfig.VersioningType.STAGGERED -> staggeredVersion(file)
                SyncConfig.VersioningType.EXTERNAL -> externalVersion(file)
                SyncConfig.VersioningType.NONE -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to version file: ${file.path}", e)
        }
    }

    /**
     * Clean up old versions based on policy.
     */
    fun cleanupVersions() {
        if (!versionsDir.exists()) return

        try {
            when (config.versioningType) {
                SyncConfig.VersioningType.TRASHCAN -> {
                    val cleanoutDays = config.versioningParams["cleanoutDays"]?.toIntOrNull() ?: 0
                    if (cleanoutDays > 0) {
                        cleanupTrashcan(cleanoutDays)
                    }
                }
                SyncConfig.VersioningType.SIMPLE -> {
                    cleanupSimple()
                }
                SyncConfig.VersioningType.STAGGERED -> {
                    cleanupStaggered()
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup versions", e)
        }
    }

    /**
     * Trashcan versioning: Move to .stversions/ preserving path structure.
     */
    private fun trashcanVersion(file: File) {
        val rootFile = File(syncRootPath)
        val relativePath = file.relativeTo(rootFile).path

        val versionedFile = File(versionsDir, relativePath)
        versionedFile.parentFile?.mkdirs()

        // If version already exists, append timestamp
        val finalVersionFile = if (versionedFile.exists()) {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = versionedFile.nameWithoutExtension
            val ext = versionedFile.extension
            val newName = if (ext.isNotEmpty()) {
                "${name}~$timestamp.$ext"
            } else {
                "${name}~$timestamp"
            }
            File(versionedFile.parentFile, newName)
        } else {
            versionedFile
        }

        file.copyTo(finalVersionFile, overwrite = false)
        Log.i(TAG, "Trashcan versioned: ${file.name} -> ${finalVersionFile.path}")
    }

    /**
     * Simple versioning: Keep N old versions with timestamps.
     */
    private fun simpleVersion(file: File) {
        val keep = config.versioningParams["keep"]?.toIntOrNull() ?: 5

        val rootFile = File(syncRootPath)
        val relativePath = file.relativeTo(rootFile).path
        val versionDir = File(versionsDir, relativePath).parentFile
        versionDir?.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = file.nameWithoutExtension
        val ext = file.extension
        val versionName = if (ext.isNotEmpty()) {
            "${name}~$timestamp.$ext"
        } else {
            "${name}~$timestamp"
        }

        val versionedFile = File(versionDir, versionName)
        file.copyTo(versionedFile, overwrite = false)
        Log.i(TAG, "Simple versioned: ${file.name} -> ${versionedFile.name}")

        // Keep only N versions
        val versions = findVersions(file, versionDir)
        if (versions.size > keep) {
            versions.sortedBy { it.lastModified() }
                .take(versions.size - keep)
                .forEach {
                    it.delete()
                    Log.d(TAG, "Deleted old version: ${it.name}")
                }
        }
    }

    /**
     * Staggered versioning: Keep versions at increasing time intervals.
     */
    private fun staggeredVersion(file: File) {
        val rootFile = File(syncRootPath)
        val relativePath = file.relativeTo(rootFile).path
        val versionDir = File(versionsDir, relativePath).parentFile
        versionDir?.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = file.nameWithoutExtension
        val ext = file.extension
        val versionName = if (ext.isNotEmpty()) {
            "${name}~$timestamp.$ext"
        } else {
            "${name}~$timestamp"
        }

        val versionedFile = File(versionDir, versionName)
        file.copyTo(versionedFile, overwrite = false)
        Log.i(TAG, "Staggered versioned: ${file.name} -> ${versionedFile.name}")
    }

    /**
     * External versioning: Call user command with file path.
     */
    private fun externalVersion(file: File) {
        val command = config.versioningParams["command"] ?: return

        try {
            val processBuilder = ProcessBuilder(command, file.absolutePath)
            processBuilder.directory(File(syncRootPath))
            val process = processBuilder.start()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Log.i(TAG, "External versioning succeeded: ${file.name}")
            } else {
                Log.w(TAG, "External versioning failed with code $exitCode: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "External versioning error: ${file.name}", e)
        }
    }

    private fun cleanupTrashcan(cleanoutDays: Int) {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(cleanoutDays.toLong())

        versionsDir.walkTopDown().forEach { file ->
            if (file.isFile && file.lastModified() < cutoffTime) {
                file.delete()
                Log.d(TAG, "Cleaned up old version: ${file.name}")
            }
        }

        // Remove empty directories
        versionsDir.walkBottomUp().forEach { dir ->
            if (dir.isDirectory && dir.listFiles()?.isEmpty() == true && dir != versionsDir) {
                dir.delete()
            }
        }
    }

    private fun cleanupSimple() {
        val keep = config.versioningParams["keep"]?.toIntOrNull() ?: 5

        versionsDir.walkTopDown().forEach { dir ->
            if (dir.isDirectory && dir != versionsDir) {
                val files = dir.listFiles()?.toList() ?: emptyList()

                // Group by base name (without timestamp)
                val grouped = files.groupBy { file ->
                    file.name.substringBeforeLast('~')
                }

                grouped.values.forEach { versions ->
                    if (versions.size > keep) {
                        versions.sortedBy { it.lastModified() }
                            .take(versions.size - keep)
                            .forEach {
                                it.delete()
                                Log.d(TAG, "Cleaned up old version: ${it.name}")
                            }
                    }
                }
            }
        }
    }

    private fun cleanupStaggered() {
        val maxAgeDays = config.versioningParams["maxAge"]?.toIntOrNull() ?: 365
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())

        versionsDir.walkTopDown().forEach { dir ->
            if (dir.isDirectory && dir != versionsDir) {
                val files = dir.listFiles()?.toList() ?: emptyList()

                // Group by base name
                val grouped = files.groupBy { file ->
                    file.name.substringBeforeLast('~')
                }

                grouped.values.forEach { versions ->
                    val sorted = versions.sortedByDescending { it.lastModified() }
                    val toKeep = mutableSetOf<File>()
                    var lastKeptTime = System.currentTimeMillis()

                    for (version in sorted) {
                        val age = System.currentTimeMillis() - version.lastModified()
                        val timeSinceLastKept = lastKeptTime - version.lastModified()

                        // Find appropriate interval
                        val interval = STAGGERED_INTERVALS.find { age / 1000 <= it.maxAgeSec }

                        if (interval != null && timeSinceLastKept / 1000 >= interval.intervalSec) {
                            toKeep.add(version)
                            lastKeptTime = version.lastModified()
                        }
                    }

                    // Delete versions not in keep set and older than max age
                    versions.forEach { version ->
                        if (version !in toKeep && version.lastModified() < cutoffTime) {
                            version.delete()
                            Log.d(TAG, "Cleaned up staggered version: ${version.name}")
                        }
                    }
                }
            }
        }
    }

    private fun findVersions(originalFile: File, dir: File?): List<File> {
        if (dir == null || !dir.exists()) return emptyList()

        val baseName = originalFile.nameWithoutExtension
        val ext = originalFile.extension

        return dir.listFiles()?.filter { file ->
            if (ext.isNotEmpty()) {
                file.name.startsWith("${baseName}~") && file.extension == ext
            } else {
                file.name.startsWith("${baseName}~")
            }
        } ?: emptyList()
    }
}
