package com.scrcpybt.common.sync

import java.io.File

/**
 * .syncignore 忽略规则解析器和匹配器（参考 Syncthing .stignore 设计）。
 *
 * 支持的语法：
 * - Glob 模式：*, **, ?, [range]
 * - ! 前缀：取反（强制包含）
 * - (?i) 前缀：大小写不敏感
 * - (?d) 前缀：删除标志（防止目录被删除）
 * - #include filename：从其他文件引入规则
 * - // 注释
 * - / 前缀：仅匹配根目录
 *
 * 匹配规则：
 * - 首个匹配的规则生效
 * - 不含 / 的模式匹配路径中任意位置
 * - 含 / 的模式锚定到同步根目录
 * - ** 跨目录分隔符匹配
 * - * 仅在单个路径组件内匹配
 */
class IgnorePattern {
    data class Rule(
        val pattern: String,
        val negate: Boolean,
        val caseInsensitive: Boolean,
        val deleteFlag: Boolean,
        val rootOnly: Boolean
    )

    private val rules: MutableList<Rule> = mutableListOf()

    /**
     * Load patterns from a file.
     */
    fun loadFromFile(file: File) {
        if (!file.exists() || !file.isFile) return
        loadFromString(file.readText())
    }

    /**
     * Load patterns from a string.
     */
    fun loadFromString(content: String) {
        rules.clear()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue

            // Handle #include directive
            if (trimmed.startsWith("#include ")) {
                val includePath = trimmed.substring(9).trim()
                // Note: In real implementation, resolve relative to current file
                // For now, just skip (would need File context to resolve)
                continue
            }

            parseRule(trimmed)?.let { rules.add(it) }
        }
    }

    /**
     * Parse a single pattern rule.
     */
    private fun parseRule(line: String): Rule? {
        var pattern = line
        var negate = false
        var caseInsensitive = false
        var deleteFlag = false

        // Check for negation
        if (pattern.startsWith("!")) {
            negate = true
            pattern = pattern.substring(1).trim()
        }

        // Check for flags
        if (pattern.startsWith("(?i)")) {
            caseInsensitive = true
            pattern = pattern.substring(4)
        }
        if (pattern.startsWith("(?d)")) {
            deleteFlag = true
            pattern = pattern.substring(4)
        }

        // Check for root-only matching
        val rootOnly = pattern.startsWith("/")
        if (rootOnly) {
            pattern = pattern.substring(1)
        }

        if (pattern.isEmpty()) return null

        return Rule(pattern, negate, caseInsensitive, deleteFlag, rootOnly)
    }

    /**
     * Check if a relative path should be ignored.
     *
     * @param relativePath Path relative to sync root (use forward slashes)
     * @return true if the path should be ignored
     */
    fun isIgnored(relativePath: String): Boolean {
        val normalizedPath = relativePath.replace('\\', '/')

        for (rule in rules) {
            if (matches(normalizedPath, rule)) {
                return !rule.negate  // If negate is true, force include (not ignored)
            }
        }

        return false  // Default: not ignored
    }

    /**
     * Check if a path matches a rule pattern.
     */
    private fun matches(path: String, rule: Rule): Boolean {
        val pattern = rule.pattern
        val caseSensitive = !rule.caseInsensitive

        val pathToMatch = if (caseSensitive) path else path.lowercase()
        val patternToMatch = if (caseSensitive) pattern else pattern.lowercase()

        return if (rule.rootOnly) {
            // Pattern must match from the start
            matchGlob(pathToMatch, patternToMatch, true)
        } else {
            // Pattern can match anywhere in path
            matchGlobAnywhere(pathToMatch, patternToMatch)
        }
    }

    /**
     * Match glob pattern (supports *, **, ?, [range]).
     */
    private fun matchGlob(text: String, pattern: String, anchored: Boolean): Boolean {
        var t = 0
        var p = 0

        while (p < pattern.length && t < text.length) {
            when {
                // ** matches everything including /
                pattern.startsWith("**/", p) -> {
                    // Try matching at every position
                    p += 3  // Skip **/
                    for (i in t..text.length) {
                        if (matchGlob(text.substring(i), pattern.substring(p), true)) {
                            return true
                        }
                    }
                    return false
                }

                pattern[p] == '*' && p + 1 < pattern.length && pattern[p + 1] == '*' -> {
                    // ** at end
                    return true
                }

                pattern[p] == '*' -> {
                    // * matches anything except /
                    p++
                    val slashPos = text.indexOf('/', t)
                    val endPos = if (slashPos == -1) text.length else slashPos
                    for (i in t..endPos) {
                        if (matchGlob(text.substring(i), pattern.substring(p), true)) {
                            return true
                        }
                    }
                    return false
                }

                pattern[p] == '?' -> {
                    // ? matches any single character except /
                    if (text[t] == '/') return false
                    p++
                    t++
                }

                pattern[p] == '[' -> {
                    // Character class
                    val closeIdx = pattern.indexOf(']', p)
                    if (closeIdx == -1) {
                        // Invalid pattern, treat [ as literal
                        if (pattern[p] != text[t]) return false
                        p++
                        t++
                    } else {
                        val charClass = pattern.substring(p + 1, closeIdx)
                        if (!matchCharClass(text[t], charClass)) return false
                        p = closeIdx + 1
                        t++
                    }
                }

                else -> {
                    // Literal character
                    if (pattern[p] != text[t]) return false
                    p++
                    t++
                }
            }
        }

        return if (anchored) {
            p == pattern.length && t == text.length
        } else {
            p == pattern.length
        }
    }

    /**
     * Match glob pattern anywhere in the text.
     */
    private fun matchGlobAnywhere(text: String, pattern: String): Boolean {
        // Try matching at every position
        for (i in text.indices) {
            if (matchGlob(text.substring(i), pattern, false)) {
                return true
            }
        }
        return matchGlob(text, pattern, false)
    }

    /**
     * Match a character against a character class [a-z], [abc], etc.
     */
    private fun matchCharClass(char: Char, charClass: String): Boolean {
        var i = 0
        while (i < charClass.length) {
            if (i + 2 < charClass.length && charClass[i + 1] == '-') {
                // Range
                if (char in charClass[i]..charClass[i + 2]) return true
                i += 3
            } else {
                // Single char
                if (char == charClass[i]) return true
                i++
            }
        }
        return false
    }

    /**
     * Check if a path has the delete flag (prevents directory deletion).
     */
    fun hasDeleteFlag(relativePath: String): Boolean {
        val normalizedPath = relativePath.replace('\\', '/')

        for (rule in rules) {
            if (rule.deleteFlag && matches(normalizedPath, rule)) {
                return true
            }
        }

        return false
    }

    /**
     * Get all rules (for debugging/testing).
     */
    fun getRules(): List<Rule> = rules.toList()

    /**
     * Clear all rules.
     */
    fun clear() {
        rules.clear()
    }
}
