package com.example.trackercar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID

data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val start: Long,
    val end: Long? = null,
    val meters: Double = 0.0,
    val price: Double,
    val cons: Double,
    val split: Int,
    val settled: Boolean = false,
) {
    val cost: Double get() = meters / 1000 * cons / 100 * price / split.coerceAtLeast(1)
}

data class Settlement(val date: Long, val meters: Double, val amount: Double, val count: Int)

data class Pt(val lat: Double, val lon: Double)

/** Jedyne źródło prawdy: lista przejazdów + stan trackingu. Dane tylko lokalnie (prywatne pliki apki). */
object Tracker {
    val trips = MutableStateFlow<List<Trip>>(emptyList())
    val settlements = MutableStateFlow<List<Settlement>>(emptyList())
    val tracking = MutableStateFlow(false)
    /** Przerzedzona trasa trwającego przejazdu (do mapy na żywo), publikowana rzadko. */
    val liveRoute = MutableStateFlow<List<Pt>>(emptyList())

    private lateinit var app: Context
    private var ready = false
    private var unsaved = 0.0
    private var liveBuf = ArrayList<Pt>()
    private var lastPublish = 0L

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        ready = true
        // przejazd przerwany ubiciem apki: domknij go (albo wyrzuć, jeśli pusty)
        val orphans = ArrayList<String>()
        trips.value = load().mapNotNull {
            if (it.end == null) {
                if (it.meters < 50) { orphans.add(it.id); null } else it.copy(end = System.currentTimeMillis())
            } else it
        }
        if (orphans.isNotEmpty()) io.execute { orphans.forEach { id -> runCatching { routeFile(id).delete() } } }
        settlements.value = loadSettlements()
        save()
    }

    private val prefs get() = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    // Double jako String (Float psuł wartości: 6.19 -> 6.190000057...). Stare klucze Float zostają jako fallback.
    private fun getD(key: String, def: Double): Double =
        prefs.getString("${key}_d", null)?.toDoubleOrNull()
            ?: runCatching { Math.round(prefs.getFloat(key, def.toFloat()) * 100.0) / 100.0 }.getOrDefault(def)
    private fun setD(key: String, v: Double) { prefs.edit().putString("${key}_d", v.toString()).apply() }
    var price: Double
        get() = getD("price", 6.0)
        set(v) { setD("price", v) }
    var cons: Double
        get() = getD("cons", 7.0)
        set(v) { setD("cons", v) }
    var split: Int
        get() = prefs.getInt("split", 2)
        set(v) { prefs.edit().putInt("split", v.coerceAtLeast(1)).apply() }

    var hintSeen: Boolean
        get() = prefs.getBoolean("hintSeen", false)
        set(v) { prefs.edit().putBoolean("hintSeen", v).apply() }

    fun begin() {
        if (tracking.value) return   // podwójny Start nie tworzy drugiego przejazdu
        trips.value = listOf(Trip(start = System.currentTimeMillis(), price = price, cons = cons, split = split)) + trips.value
        liveBuf = ArrayList(); liveRoute.value = emptyList(); unsaved = 0.0; lastPublish = 0L
        tracking.value = true
        save()
    }

    fun add(meters: Double) {
        val list = trips.value
        val i = list.indexOfFirst { it.end == null }
        if (i < 0) return
        trips.value = list.toMutableList().also { it[i] = list[i].copy(meters = list[i].meters + meters) }
        unsaved += meters
        if (unsaved >= 100) { save(); unsaved = 0.0 }
    }

    fun finish() {
        val open = trips.value.firstOrNull { it.end == null }
        trips.value = trips.value.mapNotNull {
            if (it.end == null) (if (it.meters < 50) null else it.copy(end = System.currentTimeMillis())) else it
        }
        closeRoute(if (open != null && open.meters < 50) open.id else null)
        liveBuf = ArrayList(); liveRoute.value = emptyList(); lastPublish = 0L
        tracking.value = false
        save()
    }

    fun settleAll() {
        val now = System.currentTimeMillis()
        val due = trips.value.filter { it.end != null && !it.settled }
        if (due.isEmpty()) return
        settlements.value = listOf(Settlement(now, due.sumOf { it.meters }, due.sumOf { it.cost }, due.size)) + settlements.value
        trips.value = trips.value.map { if (it.end != null) it.copy(settled = true) else it }
        save(); saveSettlements()
    }

    fun delete(id: String) {
        trips.value = trips.value.filterNot { it.id == id && it.end != null }
        io.execute { runCatching { routeFile(id).delete() } }
        save()
    }

    fun edit(id: String, meters: Double, price: Double, cons: Double, split: Int) {
        trips.value = trips.value.map {
            if (it.id == id && it.end != null) it.copy(meters = meters, price = price, cons = cons, split = split.coerceAtLeast(1)) else it
        }
        save()
    }

    // --- trasa: osobny plik binarny na przejazd (pary Double), dopisywanie bez przepisywania ---
    private fun routeFile(id: String): File {
        val dir = File(app.filesDir, "routes"); if (!dir.exists()) dir.mkdirs()
        return File(dir, "$id.bin")
    }

    // Zapis trasy: jeden otwarty, buforowany strumień na przejazd, obsługiwany wyłącznie przez jeden wątek IO
    // (zero I/O na głównym wątku). Flush co 8 punktów – po ubiciu apki tracimy najwyżej kilka ostatnich punktów.
    private val io = Executors.newSingleThreadExecutor()
    private var out: DataOutputStream? = null     // tylko wątek io
    private var outId: String? = null             // tylko wątek io
    private var pending = 0                       // tylko wątek io

    fun addPoint(lat: Double, lon: Double) {
        val cur = trips.value.firstOrNull { it.end == null } ?: return
        val id = cur.id
        io.execute {
            runCatching {
                val existing = out
                val o: DataOutputStream = if (existing != null && outId == id) existing else {
                    existing?.close()
                    DataOutputStream(BufferedOutputStream(FileOutputStream(routeFile(id), true), 4096))
                        .also { out = it; outId = id; pending = 0 }
                }
                o.writeDouble(lat); o.writeDouble(lon)
                if (++pending >= 8) { o.flush(); pending = 0 }
            }
        }
        liveBuf.add(Pt(lat, lon))
        if (liveBuf.size > 600) {
            val thin = ArrayList<Pt>(liveBuf.size / 2 + 1)
            for (i in liveBuf.indices step 2) thin.add(liveBuf[i])
            liveBuf = thin
        }
        val now = System.currentTimeMillis()
        if (now - lastPublish > 4000) { lastPublish = now; liveRoute.value = ArrayList(liveBuf) }
    }

    /** Zamyka strumień trasy (po zapisaniu zbuforowanych punktów); opcjonalnie kasuje plik. */
    private fun closeRoute(deleteId: String?) {
        io.execute {
            runCatching { out?.close() }
            out = null; outId = null; pending = 0
            if (deleteId != null) runCatching { routeFile(deleteId).delete() }
        }
    }

    fun readRoute(id: String, limit: Int = 1500): List<Pt> = runCatching {
        val f = routeFile(id)
        if (!f.exists()) return emptyList()
        val b = ByteBuffer.wrap(f.readBytes()).asDoubleBuffer()
        val n = b.remaining() / 2
        val step = if (n > limit) (n + limit - 1) / limit else 1
        val out = ArrayList<Pt>()
        var i = 0
        while (i < n) { out.add(Pt(b.get(2 * i), b.get(2 * i + 1))); i += step }
        if (step > 1 && n > 0) out.add(Pt(b.get(2 * (n - 1)), b.get(2 * (n - 1) + 1)))
        out
    }.getOrDefault(emptyList())

    // --- zapis (JSON w SharedPreferences prywatnych dla apki) ---
    // Zakończone przejazdy są niezmienne (instancje), więc ich JSON jest cache'owany –
    // przy zapisie co ~100 m serializowany jest praktycznie tylko trwający przejazd.
    private var jsonCache = java.util.IdentityHashMap<Trip, JSONObject>()

    private fun save() {
        val arr = JSONArray()
        val next = java.util.IdentityHashMap<Trip, JSONObject>()
        trips.value.forEach {
            val o = jsonCache[it] ?: JSONObject().put("id", it.id).put("start", it.start).put("end", it.end ?: JSONObject.NULL)
                .put("m", it.meters).put("p", it.price).put("c", it.cons).put("s", it.split).put("x", it.settled)
            if (it.end != null) next[it] = o
            arr.put(o)
        }
        jsonCache = next
        prefs.edit().putString("trips", arr.toString()).apply()
    }

    private fun load(): List<Trip> = runCatching {
        val arr = JSONArray(prefs.getString("trips", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Trip(o.getString("id"), o.getLong("start"), if (o.isNull("end")) null else o.getLong("end"),
                o.getDouble("m"), o.getDouble("p"), o.getDouble("c"), o.getInt("s"), o.getBoolean("x"))
        }
    }.getOrDefault(emptyList())

    private fun saveSettlements() {
        val arr = JSONArray()
        settlements.value.forEach { arr.put(JSONObject().put("d", it.date).put("m", it.meters).put("a", it.amount).put("n", it.count)) }
        prefs.edit().putString("settlements", arr.toString()).apply()
    }

    private fun loadSettlements(): List<Settlement> = runCatching {
        val arr = JSONArray(prefs.getString("settlements", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Settlement(o.getLong("d"), o.getDouble("m"), o.getDouble("a"), o.getInt("n"))
        }
    }.getOrDefault(emptyList())
}
