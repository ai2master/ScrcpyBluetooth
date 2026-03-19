package com.scrcpybt.common.codec.color

/**
 * 中位切割颜色量化器。
 *
 * 使用中位切割 (Median Cut) 算法将 ARGB_8888 像素的颜色空间
 * 缩减为 256 色调色板。算法流程：
 *
 * 1. 统计所有像素的 RGB 颜色直方图
 * 2. 将所有颜色放入一个初始颜色立方体 (ColorCube)
 * 3. 循环选取体积最大的立方体，沿最长轴中位数位置切割为两半
 * 4. 重复直到获得 256 个立方体
 * 5. 每个立方体的加权平均色作为调色板中的一个颜色
 *
 * 该算法比均匀量化效果好，能更好地保留画面中的主要颜色。
 */
class MedianCutQuantizer : ColorQuantizer {

    /**
     * 从像素数组生成 256 色调色板。
     *
     * @param pixels ARGB_8888 像素数组
     * @param maxColors 最大颜色数（默认 256）
     * @return 生成的调色板
     */
    override fun generatePalette(pixels: IntArray, maxColors: Int): Palette {
        // 统计颜色直方图（去掉 alpha 通道）
        // 使用 HashMap 预设容量减少扩容开销
        val histogram = HashMap<Int, Int>(minOf(pixels.size / 4, 65536))
        for (px in pixels) {
            val rgb = px and 0x00FFFFFF
            histogram[rgb] = (histogram[rgb] ?: 0) + 1
        }
        // 初始化：所有颜色放入一个立方体
        val initial = ColorCube(histogram)
        val cubes = mutableListOf(initial)
        // 循环切割直到达到目标颜色数
        while (cubes.size < maxColors && cubes.any { it.canSplit() }) {
            // 选取体积最大的可切割立方体
            val largest = cubes.filter { it.canSplit() }.maxByOrNull { it.volume() } ?: break
            cubes.remove(largest)
            val (a, b) = largest.split()
            cubes.add(a); cubes.add(b)
        }
        // 每个立方体的加权平均色作为调色板颜色
        val paletteColors = IntArray(256)
        for (i in cubes.indices.take(256)) {
            paletteColors[i] = cubes[i].averageColor()
        }
        return Palette(paletteColors)
    }

    /**
     * 颜色立方体：表示 RGB 颜色空间中的一个子区域。
     *
     * @property colors 区域内的颜色→出现次数映射
     */
    private class ColorCube(val colors: Map<Int, Int>) {
        // RGB 各通道的最小值和最大值（定义立方体边界）
        private val minR: Int; private val maxR: Int
        private val minG: Int; private val maxG: Int
        private val minB: Int; private val maxB: Int

        init {
            // 遍历所有颜色，找到 RGB 各通道的极值
            var r0 = 255; var r1 = 0; var g0 = 255; var g1 = 0; var b0 = 255; var b1 = 0
            for (c in colors.keys) {
                val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                if (r < r0) r0 = r; if (r > r1) r1 = r
                if (g < g0) g0 = g; if (g > g1) g1 = g
                if (b < b0) b0 = b; if (b > b1) b1 = b
            }
            minR = r0; maxR = r1; minG = g0; maxG = g1; minB = b0; maxB = b1
        }

        /** 计算立方体体积（用于选取最大的进行切割） */
        fun volume() = (maxR - minR + 1).toLong() * (maxG - minG + 1) * (maxB - minB + 1)

        /** 是否可以继续切割（至少有 2 种颜色） */
        fun canSplit() = colors.size > 1

        /**
         * 沿最长轴的中位数位置将立方体切割为两半。
         * 选择 R/G/B 中范围最大的通道作为切割轴。
         */
        fun split(): Pair<ColorCube, ColorCube> {
            val rRange = maxR - minR; val gRange = maxG - minG; val bRange = maxB - minB
            // 选择范围最大的通道
            val extractor: (Int) -> Int = when {
                rRange >= gRange && rRange >= bRange -> { c -> c shr 16 and 0xFF }
                gRange >= bRange -> { c -> c shr 8 and 0xFF }
                else -> { c -> c and 0xFF }
            }
            // 按该通道排序，在中点切割；直接构建 HashMap 避免中间 List 分配
            val sorted = colors.entries.sortedBy { extractor(it.key) }
            val mid = sorted.size / 2
            val mapA = HashMap<Int, Int>(mid + 1)
            val mapB = HashMap<Int, Int>(sorted.size - mid + 1)
            for (idx in sorted.indices) {
                val e = sorted[idx]
                if (idx < mid) mapA[e.key] = e.value else mapB[e.key] = e.value
            }
            return Pair(ColorCube(mapA), ColorCube(mapB))
        }

        /** 计算立方体内所有颜色的加权平均色（按出现次数加权） */
        fun averageColor(): Int {
            var sr = 0L; var sg = 0L; var sb = 0L; var total = 0L
            for ((c, count) in colors) {
                sr += (c shr 16 and 0xFF).toLong() * count; sg += (c shr 8 and 0xFF).toLong() * count; sb += (c and 0xFF).toLong() * count; total += count
            }
            if (total == 0L) return 0
            return (0xFF shl 24) or ((sr / total).toInt() shl 16) or ((sg / total).toInt() shl 8) or (sb / total).toInt()
        }
    }
}
