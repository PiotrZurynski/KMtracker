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

    fun begin() {
        if (tracking.value) return   // podwójny Start nie tworzy drugiego przejazdu
        trips.value = listOf(Trip(start = System.currentTimeMillis(), price = price, cons = cons, split = split)) + trips.value
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
}
