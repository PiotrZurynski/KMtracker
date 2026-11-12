package com.example.trackercar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
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

/** Jedyne źródło prawdy: lista przejazdów + stan trackingu. Dane tylko lokalnie (prywatny plik apki). */
object Tracker {
    val trips = MutableStateFlow<List<Trip>>(emptyList())
    val tracking = MutableStateFlow(false)

    private lateinit var app: Context
    private var ready = false
    private var unsaved = 0.0

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        ready = true
        // przejazd przerwany ubiciem apki: domknij go (albo wyrzuć, jeśli pusty)
        trips.value = load().mapNotNull {
            if (it.end == null) (if (it.meters < 50) null else it.copy(end = System.currentTimeMillis())) else it
        }
        save()
    }

    private val prefs get() = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var price: Double
        get() = prefs.getFloat("price", 6.0f).toDouble()
        set(v) { prefs.edit().putFloat("price", v.toFloat()).apply() }
    var cons: Double
        get() = prefs.getFloat("cons", 7.0f).toDouble()
        set(v) { prefs.edit().putFloat("cons", v.toFloat()).apply() }
    var split: Int
        get() = prefs.getInt("split", 2)
        set(v) { prefs.edit().putInt("split", v.coerceAtLeast(1)).apply() }

    fun begin() {
        trips.value = listOf(Trip(start = System.currentTimeMillis(), price = price, cons = cons, split = split)) + trips.value
        tracking.value = true
        save()
    }

    fun add(meters: Double) {
        val cur = trips.value.firstOrNull { it.end == null } ?: return
        trips.value = trips.value.map { if (it.id == cur.id) it.copy(meters = it.meters + meters) else it }
        unsaved += meters
        if (unsaved >= 100) { save(); unsaved = 0.0 }
    }

    fun finish() {
        trips.value = trips.value.mapNotNull {
            if (it.end == null) (if (it.meters < 50) null else it.copy(end = System.currentTimeMillis())) else it
        }
        tracking.value = false
        save()
    }

    fun settleAll() {
        trips.value = trips.value.map { if (it.end != null) it.copy(settled = true) else it }
        save()
    }

    fun delete(id: String) { trips.value = trips.value.filterNot { it.id == id }; save() }

    // --- zapis (JSON w SharedPreferences prywatnych dla apki) ---
    private fun save() {
        val arr = JSONArray()
        trips.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("start", it.start).put("end", it.end ?: JSONObject.NULL)
                .put("m", it.meters).put("p", it.price).put("c", it.cons).put("s", it.split).put("x", it.settled))
        }
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
}
