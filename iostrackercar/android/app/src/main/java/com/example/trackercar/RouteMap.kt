package com.example.trackercar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Mapa OpenStreetMap (bez klucza API). Linię trasy rysuje apka; kafelki wymagają internetu. */
@Composable
fun RouteMap(points: List<Pt>, live: Boolean, modifier: Modifier = Modifier, lineColor: Int = 0xFFE9204F.toInt()) {
    val ctx = LocalContext.current
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = "TrackerCar/1.0 (personal Android app)"   // czytelny, nie domyślny User-Agent
            osmdroidBasePath = File(ctx.cacheDir, "osm")           // bez uprawnień do pamięci
            osmdroidTileCache = File(ctx.cacheDir, "osm/tiles")
            tileFileSystemCacheMaxBytes = 100L * 1024 * 1024       // domyślnie 600 MB – za dużo
            tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
        }
        MapView(ctx).apply {
            // Ciemne kafelki CARTO (dane © OpenStreetMap contributors) – serwer OSM blokuje apki (HTTP 403).
            setTileSource(XYTileSource(
                "CartoDark", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/dark_all/",
                    "https://b.basemaps.cartocdn.com/dark_all/",
                    "https://c.basemaps.cartocdn.com/dark_all/",
                ),
                "© OpenStreetMap contributors © CARTO",
            ))
            setMultiTouchControls(!live)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    // update{} odpala się przy każdej rekompozycji rodzica – przebudowuj overlaye tylko dla nowej listy punktów
    val applied = remember { arrayOfNulls<Any>(1) }
    val appliedColor = remember { IntArray(1) }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        if (applied[0] === points && appliedColor[0] == lineColor) return@AndroidView
        applied[0] = points
        appliedColor[0] = lineColor
        m.overlays.clear()
        m.overlays.add(CopyrightOverlay(m.context))   // wymagana atrybucja źródła mapy
        val gp = points.map { GeoPoint(it.lat, it.lon) }
        if (gp.size > 1) {
            m.overlays.add(Polyline().apply {
                setPoints(gp)
                outlinePaint.color = lineColor
                outlinePaint.strokeWidth = 10f
            })
        }
        if (gp.isNotEmpty()) {
            if (live) { m.controller.setZoom(17.0); m.controller.setCenter(gp.last()) }
            else if (gp.size > 1) m.post { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(gp), false, 80) }
            else m.controller.setCenter(gp.first())
        }
        m.invalidate()
    })
}
