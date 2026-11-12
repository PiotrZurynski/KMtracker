package com.example.trackercar

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF1B1B1B)
private val Accent = Color(0xFFE9204F)
private val Ink = Color(0xFFF3F3F3)
private val Card = Ink.copy(alpha = 0.06f)

// Cache rodzin czcionek: t() jest wołane w composition, nie twórz FontFamily przy każdej rekompozycji.
private val fontCache = HashMap<Int, FontFamily>()
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun osw(w: Int): FontFamily = fontCache.getOrPut(w) {
    FontFamily(Font(R.font.oswald, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))))
}
private fun t(size: Int, w: Int = 400, c: Color = Ink) =
    TextStyle(fontFamily = osw(w), fontSize = size.sp, color = c)

private val pl = Locale("pl", "PL")
private fun km(m: Double) = String.format(pl, "%.1f", m / 1000)
private fun zl(v: Double) = String.format(pl, "%.2f zł", v)

private val isXiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

private fun openBattery(ctx: Context) {
    val uri = Uri.parse("package:${ctx.packageName}")
    val tries = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
    )
    for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

private fun openAutostart(ctx: Context) {
    val miui = Intent().setComponent(ComponentName(
        "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    for (i in listOf(miui, fallback)) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tracker.init(this)
        setContent { App() }
    }
}

// ---------- animacja wejścia (jednorazowa, krótka) ----------

@Composable
private fun Modifier.rise(delayMs: Int = 0): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); a.animateTo(1f, tween(500)) }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }
}

// ---------- główny ekran ----------

@Composable
fun App() {
    val ctx = LocalContext.current
    val trips by Tracker.trips.collectAsState()
    val tracking by Tracker.tracking.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(false) }

    val perms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    fun startSvc() = ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) startSvc()
    }
    fun proceed() {
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == 0 }) startSvc() else launcher.launch(perms)
    }
    fun toggle() {
        if (tracking) ctx.startService(Intent(ctx, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        else if (isXiaomi && !Tracker.hintSeen) hint = true   // pierwszy Start na Xiaomi: najpierw ustawienia baterii
        else proceed()
    }

    val owed = remember(trips) { trips.filter { !it.settled } }
    val owedM = remember(owed) { owed.sumOf { it.meters } }
    val owedC = remember(owed) { owed.sumOf { it.cost } }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(tracking, owed.isNotEmpty(), owedM, owedC, ::toggle, { confirm = true }, { settings = true })
                    1 -> TripsScreen(trips)
                    else -> StatsScreen(trips)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                listOf("START", "PRZEJAZDY", "STATYSTYKI").forEachIndexed { i, label ->
                    Box(Modifier.weight(1f).clickable { tab = i }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = t(13, 500, if (tab == i) Accent else Ink.copy(alpha = .55f)), letterSpacing = 2.sp)
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Rozliczyć ${zl(owedC)} (${km(owedM)} km)?") },
        confirmButton = { TextButton(onClick = { Tracker.settleAll(); confirm = false }) { Text("Rozlicz") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Anuluj") } },
    )
    if (hint) AlertDialog(
        onDismissRequest = { hint = false },
        title = { Text("Xiaomi / HyperOS: ważne") },
        text = {
            Column {
                Text("Telefony Xiaomi domyślnie zabijają apki w tle, przez co kilometry przestają się liczyć po wygaszeniu ekranu. Ustaw raz:")
                Spacer(Modifier.height(8.dp))
                Text("1. Bateria → TrackerCar → „Bez ograniczeń”.\n2. Autostart → włącz dla TrackerCar.\n3. W widoku ostatnich aplikacji przytrzymaj kartę TrackerCar i wybierz kłódkę (zablokuj).")
                Spacer(Modifier.height(8.dp))
                Row { TextButton({ openBattery(ctx) }) { Text("Bateria") }; TextButton({ openAutostart(ctx) }) { Text("Autostart") } }
            }
        },
        confirmButton = { TextButton(onClick = { Tracker.hintSeen = true; hint = false; proceed() }) { Text("Gotowe, startuj") } },
        dismissButton = { TextButton(onClick = { hint = false }) { Text("Anuluj") } },
    )
    if (settings) SettingsDialog(onHelp = { settings = false; hint = true }) { settings = false }
}

// ---------- START ----------

@Composable
private fun HomeScreen(
    tracking: Boolean, hasOwed: Boolean, owedM: Double, owedC: Double,
    onToggle: () -> Unit, onSettle: () -> Unit, onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TRACKERCAR", style = t(14, 500, Ink.copy(alpha = .55f)), letterSpacing = 5.sp)
            Text("USTAWIENIA", style = t(13, 500, Ink.copy(alpha = .7f)), modifier = Modifier.clickable { onSettings() })
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.rise(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(km(owedM), style = t(96, 700))
            Text("KM DO ROZLICZENIA", style = t(13, 500, Ink.copy(alpha = .45f)), letterSpacing = 4.sp)
            Spacer(Modifier.height(8.dp))
            Text("${zl(owedC)} · Twoja część", style = t(26, 600, Accent))
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.rise(120)) { StartButton(tracking, onToggle) }
        Spacer(Modifier.height(24.dp))
        if (hasOwed && !tracking) {
            OutlinedButton(onClick = onSettle, border = BorderStroke(1.5.dp, Accent), shape = CircleShape) {
                Text("ROZLICZ ${zl(owedC)}", style = t(17, 500, Accent), letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (tracking) {
            val live by Tracker.liveRoute.collectAsState()
            RouteMap(live, true, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Minimalistyczny przycisk: krótka fala i rysowanie obrysu po każdym przełączeniu (kończą się same). */
@Composable
private fun StartButton(tracking: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (tracking) Accent else Color.Transparent, tween(250), label = "fill")
    val ripple = remember { Animatable(1f) }
    val draw = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(tracking) {
        if (first) { first = false; return@LaunchedEffect }
        ripple.snapTo(0f); draw.snapTo(0f)
        ripple.animateTo(1f, tween(900))
    }
    LaunchedEffect(tracking) { if (!first) draw.animateTo(1f, tween(700)) }
    val ring = if (tracking) Accent else Ink.copy(alpha = .85f)
    Box(Modifier.size(170.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(170.dp)
            .graphicsLayer { scaleX = 1f + .45f * ripple.value; scaleY = 1f + .45f * ripple.value; alpha = .7f * (1f - ripple.value) }
            .border(2.dp, Accent, CircleShape))
        Box(Modifier.size(170.dp).clip(CircleShape).background(fill))
        Canvas(Modifier.size(170.dp)) {
            val w = 2.dp.toPx()
            drawArc(ring, -90f, 360f * draw.value, false, topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(if (tracking) "STOP" else "START", style = t(26, 500), letterSpacing = 5.sp)
    }
}

// ---------- PRZEJAZDY ----------

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (selected) Accent else Color.Transparent)
        .border(1.dp, if (selected) Color.Transparent else Ink.copy(alpha = .25f), CircleShape)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, style = t(13, 500, if (selected) Ink else Ink.copy(alpha = .7f)), letterSpacing = 2.sp)
    }
}

@Composable
private fun TripsScreen(trips: List<Trip>) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Trip?>(null) }
    val settlements by Tracker.settlements.collectAsState()
    val days = remember(trips) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", pl)
        trips.groupBy { fmt.format(Date(it.start)) }.toSortedMap(compareByDescending<String> { it }).entries.toList()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("PRZEJAZDY", section == 0) { section = 0 }
                Chip("ROZLICZENIA", section == 1) { section = 1 }
            }
        }
        if (section == 0) {
            if (days.isEmpty()) item { Text("Brak przejazdów", style = t(15, 400, Ink.copy(alpha = .5f)), modifier = Modifier.padding(top = 40.dp)) }
            items(days, key = { it.key }) { (_, list) -> DayCard(list) { editing = it } }
        } else {
            if (settlements.isEmpty()) item { Text("Brak rozliczeń", style = t(15, 400, Ink.copy(alpha = .5f)), modifier = Modifier.padding(top = 40.dp)) }
            items(settlements, key = { it.date }) { s ->
                val fmt = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", pl) }
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(fmt.format(Date(s.date)), style = t(16, 500))
                        Text("${s.count} przejazdów · ${km(s.meters)} km", style = t(13, 400, Ink.copy(alpha = .5f)))
                    }
                    Text(zl(s.amount), style = t(22, 700, Accent))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    editing?.let { cur ->
        // aktualna wersja przejazdu z listy (po edycji / zakończeniu)
        val live = trips.firstOrNull { it.id == cur.id }
        if (live == null) editing = null else TripDialog(live) { editing = null }
    }
}

@Composable
private fun DayCard(list: List<Trip>, onTrip: (Trip) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val hm = remember { SimpleDateFormat("HH:mm", pl) }
    val dayFmt = remember { SimpleDateFormat("EEEE d MMMM", pl) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card)
            .alpha(if (list.all { it.settled }) .4f else 1f)
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(dayFmt.format(Date(list.first().start)).uppercase(pl), style = t(16, 500), letterSpacing = 1.sp)
                Text("${list.size} ${if (list.size == 1) "przejazd" else "przejazdy"}", style = t(13, 400, Ink.copy(alpha = .5f)))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${km(list.sumOf { it.meters })} km", style = t(18, 600))
                Text(zl(list.sumOf { it.cost }), style = t(14, 400, Accent))
            }
        }
        if (open) list.sortedByDescending { it.start }.forEach { tr ->
            Row(Modifier.fillMaxWidth().clickable { onTrip(tr) }.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${hm.format(Date(tr.start))}–${tr.end?.let { hm.format(Date(it)) } ?: "trwa"}", style = t(14, 400, Ink.copy(alpha = .6f)))
                Text("${km(tr.meters)} km", style = t(15, 500))
            }
        }
    }
}

@Composable
private fun TripDialog(trip: Trip, onClose: () -> Unit) {
    var kmText by remember { mutableStateOf(String.format(Locale.US, "%.1f", trip.meters / 1000)) }
    var priceText by remember { mutableStateOf(trip.price.toString()) }
    var consText by remember { mutableStateOf(trip.cons.toString()) }
    var split by remember { mutableIntStateOf(trip.split) }
    val route by produceState<List<Pt>>(emptyList(), trip.id) { value = withContext(Dispatchers.IO) { Tracker.readRoute(trip.id) } }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val ongoing = trip.end == null
    val preview = (num(kmText) ?: 0.0) * 1000 / 1000 * (num(consText) ?: 0.0) / 100 * (num(priceText) ?: 0.0) / split.coerceAtLeast(1)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("PRZEJAZD", style = t(14, 500, Ink.copy(alpha = .55f)), letterSpacing = 5.sp)
                Spacer(Modifier.height(14.dp))
                if (route.size > 1) {
                    RouteMap(route, false, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.height(14.dp))
                }
                OutlinedTextField(kmText, { kmText = it }, label = { Text("Dystans (km)") }, singleLine = true, enabled = !ongoing, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(priceText, { priceText = it }, label = { Text("Cena za litr (zł)") }, singleLine = true, enabled = !ongoing, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(consText, { consText = it }, label = { Text("Spalanie (l/100 km)") }, singleLine = true, enabled = !ongoing, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (split == 1) "Płacę sam" else "Dzielone na $split", style = t(16), modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (split > 1) split-- }, enabled = !ongoing) { Text("–") }
                    TextButton(onClick = { if (split < 6) split++ }, enabled = !ongoing) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Twoja część: ${zl(preview)}", style = t(22, 600, Accent))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ongoing) {
                        OutlinedButton(onClick = {
                            val m = num(kmText); val p = num(priceText); val c = num(consText)
                            if (m != null && p != null && c != null && m >= 0 && p > 0 && c > 0) {
                                Tracker.edit(trip.id, m * 1000, p, c, split); onClose()
                            }
                        }, border = BorderStroke(1.5.dp, Accent), shape = CircleShape) { Text("ZAPISZ", style = t(15, 500, Accent), letterSpacing = 2.sp) }
                        TextButton(onClick = { Tracker.delete(trip.id); onClose() }) { Text("Usuń") }
                    }
                    TextButton(onClick = onClose) { Text("Zamknij") }
                }
            }
        }
    }
}

// ---------- STATYSTYKI ----------

@Composable
private fun StatsScreen(trips: List<Trip>) {
    val finished = remember(trips) { trips.filter { it.end != null } }
    data class Month(val key: String, val label: String, val km: Double, val cost: Double, val count: Int)
    val months = remember(finished) {
        val keyF = SimpleDateFormat("yyyy-MM", pl); val labF = SimpleDateFormat("LLL", pl)
        finished.groupBy { keyF.format(Date(it.start)) }.toSortedMap().entries.map { (k, v) ->
            Month(k, labF.format(Date(v.first().start)), v.sumOf { it.meters } / 1000, v.sumOf { it.cost }, v.size)
        }.takeLast(6)
    }
    val cur = months.firstOrNull { it.key == SimpleDateFormat("yyyy-MM", pl).format(Date()) }
    val longest = finished.maxOfOrNull { it.meters } ?: 0.0
    val avg = if (finished.isEmpty()) 0.0 else finished.sumOf { it.meters } / finished.size

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("STATYSTYKI", style = t(14, 500, Ink.copy(alpha = .55f)), letterSpacing = 5.sp, modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("TEN MIESIĄC", "${km((cur?.km ?: 0.0) * 1000)} km", zl(cur?.cost ?: 0.0), Modifier.weight(1f))
            Tile("PRZEJAZDY", "${cur?.count ?: 0}", "w tym miesiącu", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("NAJDŁUŻSZY", "${km(longest)} km", "", Modifier.weight(1f))
            Tile("ŚREDNIO", "${km(avg)} km", "na przejazd", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp)) {
            Text("KM / MIESIĄC", style = t(13, 500, Ink.copy(alpha = .45f)), letterSpacing = 3.sp)
            Spacer(Modifier.height(12.dp))
            if (months.isEmpty()) {
                Text("Brak danych", style = t(15, 400, Ink.copy(alpha = .5f)), modifier = Modifier.padding(vertical = 30.dp))
            } else {
                val max = months.maxOf { it.km }.coerceAtLeast(1.0)
                Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                    val slot = size.width / months.size
                    val barW = slot * 0.5f
                    months.forEachIndexed { i, m ->
                        val h = (m.km / max).toFloat() * size.height
                        drawRoundRect(Accent, Offset(i * slot + (slot - barW) / 2, size.height - h), Size(barW, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    months.forEach { m ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(m.label, style = t(12, 400, Ink.copy(alpha = .6f)))
                            Text(String.format(pl, "%.0f", m.km), style = t(12, 500))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(title: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp)) {
        Text(title, style = t(12, 500, Ink.copy(alpha = .45f)), letterSpacing = 3.sp)
        Text(value, style = t(28, 700))
        Text(sub, style = t(13, 400, Accent))
    }
}

// ---------- USTAWIENIA ----------

@Composable
private fun SettingsDialog(onHelp: () -> Unit, onClose: () -> Unit) {
    var price by remember { mutableStateOf(Tracker.price.toString()) }
    var cons by remember { mutableStateOf(Tracker.cons.toString()) }
    var split by remember { mutableIntStateOf(Tracker.split) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ustawienia") },
        text = {
            Column {
                OutlinedTextField(price, { price = it }, label = { Text("Cena za litr (zł)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(cons, { cons = it }, label = { Text("Spalanie (l/100 km)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (split == 1) "Płacę sam" else "Dzielone na $split", modifier = Modifier.weight(1f))
                    TextButton({ if (split > 1) split-- }) { Text("–") }
                    TextButton({ if (split < 6) split++ }) { Text("+") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onHelp) { Text("Pomoc: bateria") } },
        confirmButton = {
            TextButton(onClick = {
                price.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Tracker.price = it }
                cons.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Tracker.cons = it }
                Tracker.split = split
                onClose()
            }) { Text("Gotowe") }
        },
    )
}
