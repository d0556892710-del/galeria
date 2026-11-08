package com.galeria.app.data

import android.content.res.Resources
import com.galeria.app.R
import java.util.Calendar

/** A Memory: a titled, played-as-a-movie selection of photos (made on the device). */
data class Memory(val id: String, val title: String, val subtitle: String, val items: List<Media>) {
    val cover: Media? get() = items.firstOrNull { it.isFavorite && !it.isVideo } ?: items.firstOrNull { !it.isVideo } ?: items.firstOrNull()
}

/**
 * Builds Memories and Featured Photos from what the phone understood in the library:
 * On This Day, recent months, years, pets, food, beach, nature and nights out.
 */
object Memories {
    private val PETS = setOf("Dog", "Cat", "Pet", "Bird", "Horse")
    private val FOOD = setOf("Food", "Pizza", "Cake", "Dessert", "Hamburger", "Sushi", "Pasta", "Salad", "Ice cream", "Cookie", "Bread", "Fruit")
    private val BEACH = setOf("Beach", "Sea", "Ocean", "Swimming pool", "Pool", "Surfing")
    private val NATURE = setOf("Mountain", "Forest", "Waterfall", "Lake", "River", "Canyon", "Desert", "Jungle", "Sunset", "Flower", "Garden")
    private val NIGHTS = setOf("Party", "Concert", "Fireworks", "Night", "Stadium", "Crowd")
    private val NICE = setOf("Smile", "Selfie", "Party", "Sunset", "Beach", "Fun", "Dance", "Wedding", "Baby", "Pet", "Dog", "Cat")

    private fun score(m: Media, smart: Map<Long, SmartEntry>): Float {
        var s = 0f
        if (m.isFavorite) s += 3f
        smart[m.id]?.labels?.let { l -> s += l.count { it in NICE } * .6f; if (l.any { it == "Text" || it == "Paper" }) s -= 2f }
        if (m.width * m.height >= 3_000_000) s += .4f
        return s
    }

    /** Best [n] photos of [items], at most [perDay] a day, back in time order. */
    private fun best(items: List<Media>, n: Int, smart: Map<Long, SmartEntry>, perDay: Int = 3): List<Media> {
        val perDayCount = HashMap<Long, Int>()
        return items.sortedByDescending { score(it, smart) }.filter { m ->
            val d = m.dateTaken / 86_400_000L
            val c = perDayCount[d] ?: 0
            if (c >= perDay) false else { perDayCount[d] = c + 1; true }
        }.take(n).sortedBy { it.dateTaken }
    }

    fun build(lib: List<Media>, smart: Map<Long, SmartEntry>, res: Resources, locale: java.util.Locale): List<Memory> {
        val photos = lib.filter { !it.isScreenshot && !it.isScreenRecording && it.hiddenFile == null }
        if (photos.size < 8) return emptyList()
        val out = ArrayList<Memory>()
        val monthFmt = java.text.SimpleDateFormat("LLLL yyyy", locale)
        val dayFmt = java.text.SimpleDateFormat("d MMMM", locale)
        fun labelled(set: Set<String>) = photos.filter { m -> smart[m.id]?.labels?.any { it in set } == true }

        // Featured Photos
        val featured = best(photos, 40, smart, perDay = 2)
        if (featured.size >= 6) out += Memory("featured", res.getString(R.string.featured_photos), "", featured.sortedByDescending { it.dateTaken })

        // On This Day (earlier years)
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance()
        val onDay = photos.filter { m ->
            cal.timeInMillis = m.dateTaken
            cal.get(Calendar.YEAR) < now.get(Calendar.YEAR) && cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                kotlin.math.abs(cal.get(Calendar.DAY_OF_MONTH) - now.get(Calendar.DAY_OF_MONTH)) <= 1
        }
        if (onDay.size >= 3) out += Memory("onthisday", res.getString(R.string.on_this_day), dayFmt.format(now.time), best(onDay, 30, smart, 6))

        // Themes
        listOf(
            Triple("pets", R.string.mem_pets, PETS), Triple("food", R.string.mem_food, FOOD), Triple("beach", R.string.mem_beach, BEACH),
            Triple("nature", R.string.mem_nature, NATURE), Triple("nights", R.string.mem_nights, NIGHTS),
        ).forEach { (id, title, set) ->
            val items = labelled(set)
            if (items.size >= 8) out += Memory(id, res.getString(title), "", best(items, 30, smart))
        }

        // Recent months
        photos.groupBy { m -> cal.timeInMillis = m.dateTaken; cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH) }
            .entries.sortedByDescending { it.key }.take(6)
            .filter { it.value.size >= 15 }
            .forEach { (k, items) ->
                cal.set(k / 12, k % 12, 1)
                out += Memory("month_$k", monthFmt.format(cal.time).replaceFirstChar { it.titlecase(locale) }, "", best(items, 30, smart))
            }

        // Past years
        photos.groupBy { m -> cal.timeInMillis = m.dateTaken; cal.get(Calendar.YEAR) }
            .filter { (y, items) -> y < now.get(Calendar.YEAR) && items.size >= 50 }
            .entries.sortedByDescending { it.key }
            .forEach { (y, items) -> out += Memory("year_$y", res.getString(R.string.mem_year, y), "", best(items, 40, smart)) }
        return out
    }
}
