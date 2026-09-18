package com.example.igp_cycling_heatmap.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileNameGenerator {
    private val illegalChars = Regex("[\\\\/:*?\"<>|\\s]+")

    fun generate(record: RideRecord): String {
        val time = formatTime(record.startTime)
        val sport = extractSportType(record.title)
        return "iGPSPORT_${time}_${clean(sport)}_igp${clean(record.id)}.fit"
    }

    private fun clean(value: String): String =
        illegalChars.replace(value, "_").trim('_').ifEmpty { "unknown" }

    private fun formatTime(startTime: String): String {
        if (startTime.isBlank()) return "unknown"
        val raw = startTime.trim()
        if (raw.startsWith("/Date(") && raw.endsWith(")/")) {
            try {
                val number = raw.removePrefix("/Date(")
                    .removeSuffix(")/")
                    .substringBefore("+")
                    .substringBefore("-")
                    .toLong()
                val date = Date(if (number > 1e12) number else number * 1000)
                return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)
            } catch (_: Exception) {
            }
        }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyyMMdd HH:mm:ss",
            "yyyyMMdd'T'HH:mm:ss",
        )
        for (pattern in patterns) {
            try {
                val date = SimpleDateFormat(pattern, Locale.US).parse(raw)
                if (date != null) {
                    return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)
                }
            } catch (_: Exception) {
            }
        }
        raw.toLongOrNull()?.let {
            val date = Date(if (it > 1e12) it else it * 1000)
            return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)
        }
        return "unknown"
    }

    private fun extractSportType(title: String): String {
        if (title.isBlank()) return "运动"
        val keywords = listOf(
            "室内骑行", "户外骑行", "公路骑行", "山地骑行", "室内跑步", "户外跑步",
            "骑行", "跑步", "游泳", "徒步", "登山", "滑雪", "滑冰", "健身", "瑜伽",
            "划船机", "椭圆机", "动感单车", "铁人三项", "竞走", "越野跑",
        )
        for (keyword in keywords) {
            if (title.contains(keyword)) return keyword
        }
        return title.split(Regex("[\\s,，、]+")).lastOrNull()?.take(10) ?: "运动"
    }
}
