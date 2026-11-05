package com.galeria.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Locale-aware formatting. Uses the app's current language (English or Hebrew). */
class Fmt(val locale: Locale) {
    private val zone = ZoneId.systemDefault()
    private val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val long = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val monthYear = DateTimeFormatter.ofPattern("LLL y", locale)

    private fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    fun medium(ms: Long): String = medium.format(d(ms))
    fun long(ms: Long): String = long.format(d(ms))
    fun time(ms: Long): String = time.format(d(ms))
    fun monthYearShort(ms: Long): String = monthYear.format(d(ms))
    fun month(m: Int): String = java.time.Month.of(m).getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun monthShort(m: Int): String = java.time.Month.of(m).getDisplayName(TextStyle.SHORT_STANDALONE, locale)
    fun weekday(ms: Long): String = d(ms).dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    fun dayNumber(ms: Long): Int = d(ms).dayOfMonth
    fun isToday(ms: Long): Boolean = d(ms).toLocalDate() == LocalDate.now(zone)
    fun isYesterday(ms: Long): Boolean = d(ms).toLocalDate() == LocalDate.now(zone).minusDays(1)
    fun yearOf(ms: Long): Int = d(ms).year
    fun monthKey(ms: Long): Int = d(ms).let { it.year * 12 + (it.monthValue - 1) }
    fun yearMonthText(key: Int): Pair<String, String> = month(key % 12 + 1) to (key / 12).toString()
}

@Composable
fun rememberFmt(): Fmt {
    val l = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(l) { Fmt(l) }
}

fun duration(ms: Long): String {
    val t = (ms / 1000).toInt()
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fileSize(ctx: Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(ctx, bytes)

@Composable
fun plural(id: Int, n: Int): String = LocalContext.current.resources.getQuantityString(id, n, n)

@Composable
fun countLine(photos: Int, videos: Int): String {
    val r = LocalContext.current.resources
    val parts = buildList {
        if (photos > 0) add(r.getQuantityString(com.galeria.app.R.plurals.photos_count, photos, photos))
        if (videos > 0) add(r.getQuantityString(com.galeria.app.R.plurals.videos_count, videos, videos))
    }
    return parts.joinToString(", ")
}
