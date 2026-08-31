package com.githubclient.app.util

object NumberUtils {
    fun formatCount(count: Int): String {
        return when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> {
                val v = count / 100.0
                "${trim(v)}k"
            }
            count < 1_000_000_000 -> {
                val v = count / 1_000_000.0
                "${trim(v)}M"
            }
            else -> {
                val v = count / 1_000_000_000.0
                "${trim(v)}B"
            }
        }
    }

    private fun trim(v: Double): String {
        return if (v % 1.0 == 0.0) v.toInt().toString()
        else String.format("%.1f", v).replace(".0", "")
    }

    fun formatFileSize(bytes: Int): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "${trim(size)} ${units[unitIndex]}"
    }
}
