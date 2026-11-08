package com.galeria.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Point
import android.os.Bundle
import android.os.Process
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/** What the phone found in a photo: things (English labels) and any text. */
data class SmartEntry(val modified: Long, val labels: List<String>, val text: String)

/**
 * Understands photos on the device (nothing is uploaded), slowly in the background, newest
 * first, and remembers it: powers search by content ("dog", "beach", "כלב") and by text in photos.
 */
class SmartIndex(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "photos-understand")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> android.util.Log.w("Photos", "index", e) })
    private val _index = MutableStateFlow<Map<Long, SmartEntry>>(emptyMap())
    val index: StateFlow<Map<Long, SmartEntry>> = _index.asStateFlow()
    private val file get() = File(context.filesDir, "smart_v1.txt")
    private var loaded = false
    private var job: Job? = null

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(.62f).build()) }
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun scan(lib: List<Media>) {
        job?.cancel()
        job = scope.launch {
            if (!loaded) { load(); loaded = true }
            // People (face grouping) was removed: clear anything an earlier version stored.
            runCatching { File(context.filesDir, "faces").deleteRecursively() }
            delay(4000) // let the library settle and the first screens load first
            val map = HashMap(_index.value)
            var since = 0
            for (m in lib.asReversed()) {
                if (!isActive) break
                if (m.isVideo || m.hiddenFile != null) continue
                val e = map[m.id]
                if (e != null && e.modified == m.dateModified) continue
                val bmp = runCatching { decode(m) }.getOrNull() ?: continue
                val img = InputImage.fromBitmap(bmp, 0)
                val labels = runCatching { Tasks.await(labeler.process(img)).map { it.text } }.getOrDefault(emptyList())
                val texty = m.isScreenshot || labels.any { it in TEXTY }
                val text = if (texty) runCatching { Tasks.await(reader.process(img)).text }.getOrDefault("") else ""
                map[m.id] = SmartEntry(m.dateModified, labels, text.replace(Regex("\\s+"), " ").take(600))
                bmp.recycle()
                if (++since >= 40) { since = 0; _index.value = HashMap(map); save(map) }
            }
            val existing = lib.mapTo(HashSet()) { it.id }
            map.keys.retainAll(existing)
            _index.value = map
            save(map)
        }
    }

    private fun decode(m: Media): Bitmap {
        val opts = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(640, 640)) }
        val src = ImageDecoder.createSource {
            context.contentResolver.openTypedAssetFileDescriptor(m.uri, "image/*", opts, null) ?: throw java.io.FileNotFoundException()
        }
        return ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = 640f / max(info.size.width, info.size.height)
            if (s < 1f) d.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
        }
    }

    private fun load() = runCatching {
        val map = HashMap<Long, SmartEntry>()
        if (file.exists()) file.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3) {
                val id = p[0].toLongOrNull() ?: return@forEachLine
                map[id] = SmartEntry(p[1].toLongOrNull() ?: 0L, p[2].split('|').filter { it.isNotBlank() }, p.getOrElse(3) { "" })
            }
        }
        _index.value = map
    }

    private fun save(map: Map<Long, SmartEntry>) = runCatching {
        val tmp = File(context.filesDir, "smart_v1.tmp")
        tmp.bufferedWriter().use { w ->
            for ((id, e) in map) w.write("$id\t${e.modified}\t${e.labels.joinToString("|")}\t${e.text.replace('\t', ' ').replace('\n', ' ')}\n")
        }
        tmp.renameTo(file)
    }

    companion object {
        /** Labels that suggest a photo holds readable text. */
        val TEXTY = setOf("Poster", "Paper", "Menu", "Receipt", "Text", "Screenshot", "Book", "Newspaper", "Handwriting", "Whiteboard", "Sign", "Document")

        /** Hebrew names for the things the phone recognises, for searching in Hebrew. */
        val HE: Map<String, String> = mapOf(
            "Dog" to "כלב", "Cat" to "חתול", "Bird" to "ציפור", "Horse" to "סוס", "Fish" to "דג", "Insect" to "חרק", "Butterfly" to "פרפר",
            "Pet" to "חיית מחמד", "Animal" to "חיה", "Food" to "אוכל", "Pizza" to "פיצה", "Cake" to "עוגה", "Bread" to "לחם", "Fruit" to "פרי",
            "Vegetable" to "ירק", "Coffee" to "קפה", "Drink" to "שתייה", "Wine" to "יין", "Beer" to "בירה", "Cookie" to "עוגייה", "Dessert" to "קינוח",
            "Hamburger" to "המבורגר", "Sushi" to "סושי", "Pasta" to "פסטה", "Salad" to "סלט", "Ice cream" to "גלידה", "Egg" to "ביצה",
            "Sky" to "שמיים", "Cloud" to "ענן", "Sunset" to "שקיעה", "Moon" to "ירח", "Star" to "כוכב", "Rainbow" to "קשת", "Lightning" to "ברק",
            "Snow" to "שלג", "Rain" to "גשם", "Beach" to "חוף", "Sea" to "ים", "Ocean" to "אוקיינוס", "Lake" to "אגם", "River" to "נהר",
            "Waterfall" to "מפל", "Mountain" to "הר", "Desert" to "מדבר", "Forest" to "יער", "Jungle" to "ג׳ונגל", "Tree" to "עץ", "Plant" to "צמח",
            "Flower" to "פרח", "Grass" to "דשא", "Garden" to "גינה", "Leaf" to "עלה", "Rock" to "סלע", "Cave" to "מערה", "Canyon" to "קניון",
            "Car" to "מכונית", "Vehicle" to "רכב", "Bicycle" to "אופניים", "Motorcycle" to "אופנוע", "Bus" to "אוטובוס", "Train" to "רכבת",
            "Airplane" to "מטוס", "Boat" to "סירה", "Truck" to "משאית", "Wheel" to "גלגל", "Road" to "כביש", "Bridge" to "גשר",
            "Building" to "בניין", "Skyscraper" to "גורד שחקים", "House" to "בית", "Tower" to "מגדל", "Castle" to "טירה", "Church" to "כנסייה",
            "Temple" to "מקדש", "Statue" to "פסל", "Monument" to "אנדרטה", "Stadium" to "אצטדיון", "City" to "עיר", "Room" to "חדר",
            "Kitchen" to "מטבח", "Bed" to "מיטה", "Couch" to "ספה", "Chair" to "כיסא", "Table" to "שולחן", "Desk" to "שולחן עבודה", "Lamp" to "מנורה",
            "Window" to "חלון", "Door" to "דלת", "Stairs" to "מדרגות", "Shelf" to "מדף", "Curtain" to "וילון", "Pool" to "בריכה", "Swimming pool" to "בריכה",
            "Computer" to "מחשב", "Laptop" to "מחשב נייד", "Television" to "טלוויזיה", "Mobile phone" to "טלפון", "Camera" to "מצלמה",
            "Musical instrument" to "כלי נגינה", "Guitar" to "גיטרה", "Piano" to "פסנתר", "Toy" to "צעצוע", "Balloon" to "בלון", "Umbrella" to "מטריה",
            "Flag" to "דגל", "Fireworks" to "זיקוקים", "Christmas" to "חג המולד", "Party" to "מסיבה", "Wedding" to "חתונה", "Concert" to "הופעה",
            "Crowd" to "קהל", "Team" to "קבוצה", "Sports" to "ספורט", "Soccer" to "כדורגל", "Basketball" to "כדורסל", "Dance" to "ריקוד",
            "Selfie" to "סלפי", "Smile" to "חיוך", "Baby" to "תינוק", "Child" to "ילד", "Person" to "אדם", "Beard" to "זקן", "Hair" to "שיער",
            "Hand" to "יד", "Eyelash" to "ריסים", "Tattoo" to "קעקוע", "Muscle" to "שריר", "Glasses" to "משקפיים", "Sunglasses" to "משקפי שמש",
            "Hat" to "כובע", "Shoe" to "נעל", "Jeans" to "ג׳ינס", "Dress" to "שמלה", "Shirt" to "חולצה", "Jacket" to "מעיל", "Bag" to "תיק",
            "Handbag" to "תיק יד", "Watch" to "שעון", "Jewelry" to "תכשיט", "Necklace" to "שרשרת", "Ring" to "טבעת", "Book" to "ספר",
            "Paper" to "נייר", "Poster" to "פוסטר", "Menu" to "תפריט", "Receipt" to "קבלה", "Screenshot" to "צילום מסך", "Text" to "טקסט",
            "Night" to "לילה", "Light" to "אור", "Pattern" to "דוגמה", "Art" to "אמנות", "Painting" to "ציור", "Sculpture" to "פיסול",
        )
    }
}
