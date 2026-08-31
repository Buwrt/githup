package com.githubclient.app.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object TimeUtils {
    fun formatRelativeTime(dateString: String?): String {
        if (dateString.isNullOrBlank()) return ""
        val instant = try {
            Instant.parse(dateString)
        } catch (e: DateTimeParseException) {
            return dateString
        }
        val now = Instant.now()
        val duration = Duration.between(instant, now)
        val seconds = duration.seconds
        if (seconds < 0) return "刚刚"
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60}分钟前"
            seconds < 86_400 -> "${seconds / 3600}小时前"
            seconds < 604_800 -> "${seconds / 86_400}天前"
            seconds < 2_592_000 -> "${seconds / 604_800}周前"
            seconds < 31_536_000 -> "${seconds / 2_592_000}个月前"
            else -> "${seconds / 31_536_000}年前"
        }
    }

    fun formatDate(dateString: String?): String {
        if (dateString.isNullOrBlank()) return ""
        return try {
            val instant = Instant.parse(dateString)
            val zoned = instant.atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).format(zoned)
        } catch (e: DateTimeParseException) {
            dateString
        }
    }
}
