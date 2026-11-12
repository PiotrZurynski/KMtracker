package com.example.trackercar

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF1B1B1B)
private val Accent = Color(0xFFE9204F)
private val Ink = Color(0xFFF3F3F3)
private val Card = Ink.copy(alpha = 0.06f)

private fun osw(w: Int) = FontFamily(
    Font(R.font.oswald, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
)
private fun t(size: Int, w: Int = 400, c: Color = Ink) =
    TextStyle(fontFamily = osw(w), fontSize = size.sp, color = c)

private val pl = Locale("pl", "PL")
private fun km(m: Double) = String.format(pl, "%.1f", m / 1000)
private fun zl(v: Double) = String.format(pl, "%.2f zł", v)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tracker.init(this)
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val trips by Tracker.trips.collectAsState()
    val tracking by Tracker.tracking.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

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
    fun toggle() {
        if (tracking) ctx.startService(Intent(ctx, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        else if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == 0 }) startSvc()
        else launcher.launch(perms)
    }

    val owed = trips.filter { !it.settled }
    val owedM = owed.sumOf { it.meters }
    val owedC = owed.sumOf { it.cost }
    val days = trips.filter { it.end != null || it.id == trips.firstOrNull { x -> x.end == null }?.id }
        .groupBy { SimpleDateFormat("yyyy-MM-dd", pl).format(Date(it.start)) }
        .toSortedMap(compareByDescending<String> { it })

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TRACKERCAR", style = t(14, 500, Ink.copy(alpha = .55f)), letterSpacing = 5.sp)
                    Text("USTAWIENIA", style = t(13, 500, Ink.copy(alpha = .7f)), modifier = Modifier.clickable { settings = true })
                }
                Spacer(Modifier.height(20.dp))
                Text(km(owedM), style = t(96, 700))
                Text("KM DO ROZLICZENIA", style = t(13, 500, Ink.copy(alpha = .45f)), letterSpacing = 4.sp)
                Spacer(Modifier.height(8.dp))
                Text("${zl(owedC)} · Twoja ½", style = t(26, 600, Accent))
                Spacer(Modifier.height(26.dp))
                Box(
                    Modifier.size(170.dp).clip(CircleShape)
                        .background(if (tracking) Accent else Color.Transparent)
                        .border(2.dp, if (tracking) Accent else Ink.copy(alpha = .85f), CircleShape)
                        .clickable { toggle() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (tracking) "STOP" else "START", style = t(26, 500), letterSpacing = 5.sp) }
                Spacer(Modifier.height(24.dp))
                if (owed.isNotEmpty() && !tracking) {
                    OutlinedButton(onClick = { confirm = true }, border = BorderStroke(1.5.dp, Accent), shape = CircleShape) {
                        Text("ROZLICZ ${zl(owedC)}", style = t(17, 500, Accent), letterSpacing = 3.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            items(days.entries.toList()) { (day, list) -> DayCard(day, list) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Rozliczyć ${zl(owedC)} (${km(owedM)} km)?") },
        confirmButton = { TextButton(onClick = { Tracker.settleAll(); confirm = false }) { Text("Rozlicz") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Anuluj") } },
    )
    if (settings) SettingsDialog { settings = false }
}

@Composable
private fun DayCard(day: String, list: List<Trip>) {
    var open by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("HH:mm", pl)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card)
            .alpha(if (list.all { it.settled }) .4f else 1f)
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(day, style = t(16, 500))
                Text("${list.size} ${if (list.size == 1) "przejazd" else "przejazdy"}", style = t(13, 400, Ink.copy(alpha = .5f)))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${km(list.sumOf { it.meters })} km", style = t(18, 600))
                Text(zl(list.sumOf { it.cost }), style = t(14, 400, Accent))
            }
        }
        if (open) list.sortedByDescending { it.start }.forEach { tr ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${fmt.format(Date(tr.start))}–${tr.end?.let { fmt.format(Date(it)) } ?: "trwa"}", style = t(14, 400, Ink.copy(alpha = .6f)))
                Text("${km(tr.meters)} km", style = t(15, 500))
            }
        }
    }
}

@Composable
private fun SettingsDialog(onClose: () -> Unit) {
    var price by remember { mutableStateOf(Tracker.price.toString()) }
    var cons by remember { mutableStateOf(Tracker.cons.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ustawienia") },
        text = {
            Column {
                OutlinedTextField(price, { price = it }, label = { Text("Cena za litr (zł)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(cons, { cons = it }, label = { Text("Spalanie (l/100 km)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                price.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Tracker.price = it }
                cons.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Tracker.cons = it }
                onClose()
            }) { Text("Gotowe") }
        },
    )
}
