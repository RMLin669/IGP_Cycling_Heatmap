package com.example.igp_cycling_heatmap.map

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.igp_cycling_heatmap.data.FitPoint
import com.example.igp_cycling_heatmap.data.LoadedRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

internal data class RouteHeatState(
    val heatData: HeatData?,
    val rendering: Boolean,
)

enum class HeatColorMode {
    BLUE_TO_RED,
    SINGLE_COLOR,
}

enum class BaseMapStyle {
    DARK,
    LIGHT,
}

@Composable
internal fun rememberRouteHeatState(routes: List<LoadedRoute>): RouteHeatState {
    var heatData by remember { mutableStateOf<HeatData?>(null) }
    var rendering by remember { mutableStateOf(false) }

    LaunchedEffect(routes) {
        if (routes.isEmpty()) {
            heatData = null
            rendering = false
            return@LaunchedEffect
        }
        rendering = true
        val convertedRoutes = routes.map { route ->
            route.points.map(::toRoutePoint)
        }
        val rendered = try {
            withContext(Dispatchers.Default) {
                RouteHeatRenderer.render(convertedRoutes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("RouteMapContainer", "路线密度计算失败，使用预览路线", error)
            withContext(Dispatchers.Default) {
                RouteHeatRenderer.renderPreview(convertedRoutes)
            }
        }
        heatData = rendered
        Log.d(MAP_LOG_TAG, "热力数据生成完成: segments=${rendered?.starts?.size ?: 0}")
        rendering = false
    }
    return RouteHeatState(heatData = heatData, rendering = rendering)
}

@Composable
fun OsmMapContainer(
    heatData: HeatData?,
    renderingHeat: Boolean,
    colorMode: HeatColorMode = HeatColorMode.BLUE_TO_RED,
    baseMapStyle: BaseMapStyle = BaseMapStyle.DARK,
    singleRouteColor: Int = DEFAULT_SINGLE_ROUTE_COLOR,
    heatVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) { MapLibreController(context) }

    DisposableEffect(controller, lifecycleOwner) {
        val observer = controller.lifecycleObserver
        lifecycleOwner.lifecycle.addObserver(observer)
        observer.syncWith(lifecycleOwner.lifecycle)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.destroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { controller.view },
            update = {
                controller.setDisplayState(
                    heatData = heatData,
                    colorMode = colorMode,
                    baseMapStyle = baseMapStyle,
                    singleRouteColor = singleRouteColor,
                    heatVisible = heatVisible,
                )
            },
        )
        if (renderingHeat) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private class MapLibreController(context: Context) {
    val view: MapView
    val lifecycleObserver = MapViewLifecycleObserver()

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pendingHeatData: HeatData? = null
    private var pendingColorMode = HeatColorMode.BLUE_TO_RED
    private var pendingBaseMapStyle = BaseMapStyle.DARK
    private var pendingSingleRouteColor = DEFAULT_SINGLE_ROUTE_COLOR
    private var pendingHeatVisible = true
    private var renderedHeatData: HeatData? = null
    private var renderedColorMode: HeatColorMode? = null
    private var renderedSingleRouteColor: Int? = null
    private var renderedHeatVisible: Boolean? = null
    private var hasRenderedHeatData = false
    private var destroyed = false

    init {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                    .zoom(DEFAULT_ZOOM)
                    .build(),
            )
            .minZoomPreference(MIN_ZOOM)
            .maxZoomPreference(MAX_ZOOM)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(true)
            .attributionTintColor(Color.LTGRAY)
            .foregroundLoadColor(DARK_BACKGROUND)

        view = MapView(context, options).apply {
            setBackgroundColor(DARK_BACKGROUND)
            alpha = 0f
            onCreate(Bundle())
            addOnDidFailLoadingMapListener { errorMessage ->
                Log.e(MAP_LOG_TAG, "MapLibre 地图加载失败: $errorMessage")
            }
        }
        lifecycleObserver.mapView = view
        view.getMapAsync { readyMap ->
            if (destroyed) return@getMapAsync
            map = readyMap
            readyMap.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                attributionGravity = Gravity.BOTTOM or Gravity.START
                val margin = (view.resources.displayMetrics.density * ATTRIBUTION_MARGIN_DP)
                    .toInt()
                setAttributionMargins(margin, 0, 0, margin)
            }
            applyBaseMapStyle()
        }
    }

    fun setDisplayState(
        heatData: HeatData?,
        colorMode: HeatColorMode,
        baseMapStyle: BaseMapStyle,
        singleRouteColor: Int,
        heatVisible: Boolean,
    ) {
        val mapStyleChanged = pendingBaseMapStyle != baseMapStyle
        pendingHeatData = heatData
        pendingColorMode = colorMode
        pendingBaseMapStyle = baseMapStyle
        pendingSingleRouteColor = singleRouteColor
        pendingHeatVisible = heatVisible
        if (mapStyleChanged && map != null) {
            applyBaseMapStyle()
        } else {
            renderPendingHeatData()
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        lifecycleObserver.destroy()
        map = null
        style = null
    }

    private fun applyBaseMapStyle() {
        val readyMap = map ?: return
        val requestedStyle = pendingBaseMapStyle
        style = null
        renderedHeatData = null
        renderedColorMode = null
        renderedSingleRouteColor = null
        renderedHeatVisible = null
        hasRenderedHeatData = false
        view.alpha = 0f
        view.setBackgroundColor(requestedStyle.backgroundColor())
        readyMap.uiSettings.setAttributionTintColor(requestedStyle.attributionColor())
        readyMap.setStyle(Style.Builder().fromUri(requestedStyle.styleUri())) { readyStyle ->
            if (destroyed || requestedStyle != pendingBaseMapStyle) return@setStyle
            removeAllLabels(readyStyle)
            style = readyStyle
            Log.d(MAP_LOG_TAG, "MapLibre 无字${requestedStyle.logLabel()}样式加载完成")
            renderPendingHeatData()
            view.alpha = 1f
        }
    }

    private fun renderPendingHeatData() {
        val readyStyle = style ?: return
        val heatData = pendingHeatData
        val heatDataChanged = !hasRenderedHeatData || renderedHeatData !== heatData
        val colorModeChanged = renderedColorMode != pendingColorMode
        val singleColorChanged = renderedSingleRouteColor != pendingSingleRouteColor
        val heatVisibilityChanged = renderedHeatVisible != pendingHeatVisible
        if (!heatDataChanged && !colorModeChanged && !singleColorChanged &&
            !heatVisibilityChanged
        ) {
            return
        }
        if (!hasRenderedHeatData && heatData == null) return

        var source = readyStyle.getSourceAs<GeoJsonSource>(HEAT_SOURCE_ID)
        if (source == null) {
            if (heatData == null) return
            source = GeoJsonSource(
                HEAT_SOURCE_ID,
                heatData.geoJson,
                GeoJsonOptions()
                    .withMaxZoom(MAX_ZOOM.toInt())
                    .withTolerance(0f),
            )
            readyStyle.addSource(source)
            installHeatLayer(readyStyle)
        } else if (heatDataChanged) {
            source.setGeoJson(heatData?.geoJson ?: EMPTY_FEATURE_COLLECTION)
        }
        if (colorModeChanged || singleColorChanged || heatVisibilityChanged) {
            readyStyle.getLayerAs<LineLayer>(HEAT_LAYER_ID)?.setProperties(
                PropertyFactory.lineColor(routeColorExpression()),
            )
        }
        renderedHeatData = heatData
        renderedColorMode = pendingColorMode
        renderedSingleRouteColor = pendingSingleRouteColor
        renderedHeatVisible = pendingHeatVisible
        hasRenderedHeatData = true
        if (heatDataChanged && heatData != null) fitHeatBounds(heatData)
    }

    private fun fitHeatBounds(heatData: HeatData) {
        val readyMap = map ?: return
        val bounds = heatBounds(heatData) ?: return
        val padding = (view.resources.displayMetrics.density * CAMERA_PADDING_DP).toInt()
        view.post {
            if (!destroyed && view.width > 0 && view.height > 0) {
                readyMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            }
        }
    }

    private fun installHeatLayer(style: Style) {
        style.addLayer(
            LineLayer(HEAT_LAYER_ID, HEAT_SOURCE_ID).withProperties(
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
                PropertyFactory.lineColor(routeColorExpression()),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(3, 2.5),
                        Expression.stop(8, 4.0),
                        Expression.stop(12, 6.0),
                        Expression.stop(16, 8.0),
                        Expression.stop(20, 10.0),
                    ),
                ),
                PropertyFactory.lineSortKey(
                    Expression.toNumber(Expression.get("count")),
                ),
            ),
        )
    }

    private fun routeColorExpression(): Expression {
        if (!pendingHeatVisible) {
            return Expression.color(
                if (pendingColorMode == HeatColorMode.BLUE_TO_RED) {
                    BLUE_ROUTE_COLOR
                } else {
                    lightRouteColor(pendingSingleRouteColor)
                },
            )
        }
        if (pendingColorMode == HeatColorMode.BLUE_TO_RED) {
            return Expression.get("color")
        }
        return Expression.interpolate(
            Expression.linear(),
            Expression.get("ratio"),
            Expression.stop(0, Expression.color(lightRouteColor(pendingSingleRouteColor))),
            Expression.stop(1, Expression.color(darkRouteColor(pendingSingleRouteColor))),
        )
    }
}

private class MapViewLifecycleObserver : DefaultLifecycleObserver {
    var mapView: MapView? = null
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun syncWith(lifecycle: Lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }

    override fun onStart(owner: LifecycleOwner) = start()

    override fun onResume(owner: LifecycleOwner) = resume()

    override fun onPause(owner: LifecycleOwner) = pause()

    override fun onStop(owner: LifecycleOwner) = stop()

    override fun onDestroy(owner: LifecycleOwner) = destroy()

    fun destroy() {
        if (destroyed) return
        pause()
        stop()
        mapView?.onDestroy()
        mapView = null
        destroyed = true
    }

    private fun start() {
        if (!started && !destroyed) {
            mapView?.onStart()
            started = true
        }
    }

    private fun resume() {
        if (!resumed && !destroyed) {
            if (!started) start()
            mapView?.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed) {
            mapView?.onPause()
            resumed = false
        }
    }

    private fun stop() {
        if (started) {
            mapView?.onStop()
            started = false
        }
    }
}

private fun removeAllLabels(style: Style) {
    style.layers
        .filterIsInstance<SymbolLayer>()
        .map { layer -> layer.id }
        .forEach(style::removeLayer)
}

private fun toRoutePoint(point: FitPoint): RoutePoint = RoutePoint(point.lat, point.lng)

private fun heatBounds(heatData: HeatData): LatLngBounds? {
    if (heatData.starts.isEmpty()) return null
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLng = Double.MAX_VALUE
    var maxLng = -Double.MAX_VALUE

    heatData.starts.indices.forEach { index ->
        updateBounds(heatData.starts[index]) { lat, lng ->
            minLat = minOf(minLat, lat)
            maxLat = maxOf(maxLat, lat)
            minLng = minOf(minLng, lng)
            maxLng = maxOf(maxLng, lng)
        }
        updateBounds(heatData.ends[index]) { lat, lng ->
            minLat = minOf(minLat, lat)
            maxLat = maxOf(maxLat, lat)
            minLng = minOf(minLng, lng)
            maxLng = maxOf(maxLng, lng)
        }
    }
    if (maxLat - minLat < MIN_BOUNDS_SPAN) {
        minLat -= MIN_BOUNDS_SPAN / 2.0
        maxLat += MIN_BOUNDS_SPAN / 2.0
    }
    if (maxLng - minLng < MIN_BOUNDS_SPAN) {
        minLng -= MIN_BOUNDS_SPAN / 2.0
        maxLng += MIN_BOUNDS_SPAN / 2.0
    }
    return LatLngBounds.from(maxLat, maxLng, minLat, minLng)
}

private inline fun updateBounds(packed: Long, update: (Double, Double) -> Unit) {
    update(RouteHeatRenderer.latitude(packed), RouteHeatRenderer.longitude(packed))
}

private fun BaseMapStyle.styleUri(): String = when (this) {
    BaseMapStyle.DARK -> DARK_STYLE_URI
    BaseMapStyle.LIGHT -> LIGHT_STYLE_URI
}

private fun BaseMapStyle.backgroundColor(): Int = when (this) {
    BaseMapStyle.DARK -> DARK_BACKGROUND
    BaseMapStyle.LIGHT -> LIGHT_BACKGROUND
}

private fun BaseMapStyle.attributionColor(): Int = when (this) {
    BaseMapStyle.DARK -> Color.LTGRAY
    BaseMapStyle.LIGHT -> Color.DKGRAY
}

private fun BaseMapStyle.logLabel(): String = when (this) {
    BaseMapStyle.DARK -> "暗色"
    BaseMapStyle.LIGHT -> "亮色"
}

private fun lightRouteColor(color: Int): Int = blendColor(color, Color.WHITE, 0.65f)

private fun darkRouteColor(color: Int): Int = blendColor(color, Color.BLACK, 0.25f)

private fun blendColor(start: Int, end: Int, endRatio: Float): Int {
    val startRatio = 1f - endRatio
    return Color.rgb(
        (Color.red(start) * startRatio + Color.red(end) * endRatio).toInt(),
        (Color.green(start) * startRatio + Color.green(end) * endRatio).toInt(),
        (Color.blue(start) * startRatio + Color.blue(end) * endRatio).toInt(),
    )
}

private const val DARK_STYLE_URI = "asset://openfreemap_dark_nolabels.json"
private const val LIGHT_STYLE_URI = "asset://openfreemap_light_nolabels.json"
private const val MAP_LOG_TAG = "RouteMapContainer"
private const val HEAT_SOURCE_ID = "route-heat-source"
private const val HEAT_LAYER_ID = "route-heat-layer"
private const val EMPTY_FEATURE_COLLECTION =
    "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val DEFAULT_LATITUDE = 35.0
private const val DEFAULT_LONGITUDE = 105.0
private const val DEFAULT_ZOOM = 3.5
private const val MIN_ZOOM = 2.0
private const val MAX_ZOOM = 20.0
private const val CAMERA_PADDING_DP = 28f
private const val ATTRIBUTION_MARGIN_DP = 8f
private const val MIN_BOUNDS_SPAN = 0.0001
internal const val DEFAULT_SINGLE_ROUTE_COLOR = -1754827
private val BLUE_ROUTE_COLOR = Color.rgb(0, 80, 255)
private val DARK_BACKGROUND = Color.rgb(11, 13, 15)
private val LIGHT_BACKGROUND = Color.rgb(239, 242, 245)
