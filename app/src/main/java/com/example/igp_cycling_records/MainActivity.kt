package com.example.igp_cycling_heatmap

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.igp_cycling_heatmap.data.IgpsportLoginActivity
import com.example.igp_cycling_heatmap.data.LoadedRoute
import com.example.igp_cycling_heatmap.data.LocalRouteStore
import com.example.igp_cycling_heatmap.data.PrefsManager
import com.example.igp_cycling_heatmap.data.RideSyncService
import com.example.igp_cycling_heatmap.data.RouteLibrary
import com.example.igp_cycling_heatmap.data.RouteLoader
import com.example.igp_cycling_heatmap.data.RouteSourceMode
import com.example.igp_cycling_heatmap.map.BaseMapStyle
import com.example.igp_cycling_heatmap.map.DEFAULT_SINGLE_ROUTE_COLOR
import com.example.igp_cycling_heatmap.map.HeatData
import com.example.igp_cycling_heatmap.map.HeatColorMode
import com.example.igp_cycling_heatmap.map.OsmMapContainer
import com.example.igp_cycling_heatmap.map.rememberRouteHeatState
import com.example.igp_cycling_heatmap.ui.theme.igp_cycling_heatmapTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            igp_cycling_heatmapTheme {
                MainScreen()
            }
        }
    }

}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsManager(context) }
    val store = remember { LocalRouteStore(context) }
    val syncService = remember { RideSyncService(context) }
    val routeLoader = remember { RouteLoader(context) }

    var loggedIn by remember { mutableStateOf(prefs.isLoggedIn()) }
    var username by remember { mutableStateOf(prefs.username()) }
    var routes by remember { mutableStateOf<List<LoadedRoute>>(emptyList()) }
    var routeLibrary by remember { mutableStateOf<RouteLibrary?>(null) }
    var importedCount by remember { mutableStateOf(0) }
    var mapFullscreen by remember { mutableStateOf(false) }
    var heatColorMode by remember {
        mutableStateOf(
            if (prefs.isSingleColorMode()) {
                HeatColorMode.SINGLE_COLOR
            } else {
                HeatColorMode.BLUE_TO_RED
            },
        )
    }
    var baseMapStyle by remember {
        mutableStateOf(if (prefs.isDarkMap()) BaseMapStyle.DARK else BaseMapStyle.LIGHT)
    }
    var singleRouteColor by remember { mutableStateOf(prefs.singleRouteColor()) }
    var heatVisible by remember { mutableStateOf(prefs.isHeatVisible()) }
    var syncing by remember { mutableStateOf(false) }
    var loadingRoutes by remember { mutableStateOf(false) }
    var clearRecordsOnly by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    val logListState = rememberLazyListState()
    val routeHeatState = rememberRouteHeatState(routes)
    val sourceMode = when {
        clearRecordsOnly -> RouteSourceMode.IMPORTED
        !loggedIn -> RouteSourceMode.IMPORTED
        importedCount > 0 -> RouteSourceMode.ALL
        else -> RouteSourceMode.DOWNLOADED
    }

    fun postMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$timestamp] $message")
        if (logs.size > 200) {
            logs.removeRange(0, logs.size - 200)
        }
    }

    fun refreshLibrary(mode: RouteSourceMode) {
        if (loadingRoutes) return
        loadingRoutes = true
        appendLog("开始加载本地 FIT 路线")
        scope.launch(Dispatchers.IO) {
            val library = routeLoader.loadLibrary(mode) { message ->
                postMain { appendLog(message) }
            }
            postMain {
                routes = library.routes
                routeLibrary = library
                importedCount = library.importedCount
                loadingRoutes = false
                appendLog(
                    "路线统计：账户同步 ${library.downloadedCount}，导入 ${library.importedCount}，" +
                        "去重后 ${library.deduplicatedCount}，" +
                        "总里程 ${String.format(Locale.US, "%.2f", library.totalDistanceKm)} km",
                )
            }
        }
    }

    LaunchedEffect(sourceMode) {
        refreshLibrary(sourceMode)
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.lastIndex)
        }
    }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val token = result.data?.getStringExtra(IgpsportLoginActivity.RESULT_TOKEN)
            if (!token.isNullOrBlank()) {
                val loginName = result.data
                    ?.getStringExtra(IgpsportLoginActivity.RESULT_USERNAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: "用户"
                prefs.saveLogin(token, loginName)
                loggedIn = true
                username = prefs.username()
                clearRecordsOnly = false
                appendLog("iGPSPORT 登录成功：$username")
            }
        } else {
            appendLog("已取消登录")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) {
            appendLog("已取消导入 FIT 文件")
        } else {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                appendLog("未能持久化目录访问权限，本次仍会尝试读取")
            }
            appendLog("开始扫描所选目录中的 FIT 文件")
            scope.launch(Dispatchers.IO) {
                val uris = store.collectFitUris(treeUri)
                if (uris.isEmpty()) {
                    postMain { appendLog("所选目录中没有找到 .fit 文件") }
                } else {
                    postMain { appendLog("找到 ${uris.size} 个 FIT 文件，开始导入") }
                    val result = store.importFiles(uris) { message ->
                        postMain { appendLog(message) }
                    }
                    val currentImportedCount = store.importedFiles().size
                    postMain {
                        importedCount = currentImportedCount
                        appendLog(result.message)
                        val nextMode = when {
                            !loggedIn -> RouteSourceMode.IMPORTED
                            currentImportedCount > 0 -> RouteSourceMode.ALL
                            else -> RouteSourceMode.DOWNLOADED
                        }
                        refreshLibrary(nextMode)
                    }
                }
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (mapFullscreen) {
            BackHandler {
                mapFullscreen = false
            }
            FullscreenMapCard(
                onToggleMapStyle = {
                    baseMapStyle = when (baseMapStyle) {
                        BaseMapStyle.DARK -> BaseMapStyle.LIGHT
                        BaseMapStyle.LIGHT -> BaseMapStyle.DARK
                    }
                    prefs.saveMapStyle(baseMapStyle == BaseMapStyle.DARK)
                },
                onToggleRouteStyle = {
                    heatColorMode = when (heatColorMode) {
                        HeatColorMode.BLUE_TO_RED -> HeatColorMode.SINGLE_COLOR
                        HeatColorMode.SINGLE_COLOR -> HeatColorMode.BLUE_TO_RED
                    }
                    prefs.saveSingleColorMode(heatColorMode == HeatColorMode.SINGLE_COLOR)
                },
                onSingleRouteColorChanged = { color ->
                    singleRouteColor = color
                    prefs.saveSingleRouteColor(color)
                },
                onToggleHeat = {
                    heatVisible = !heatVisible
                    prefs.saveHeatVisible(heatVisible)
                },
                modifier = Modifier.padding(innerPadding),
                heatData = routeHeatState.heatData,
                renderingHeat = routeHeatState.rendering,
                colorMode = heatColorMode,
                baseMapStyle = baseMapStyle,
                singleRouteColor = singleRouteColor,
                heatVisible = heatVisible,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            AccountCard(
                loggedIn = loggedIn,
                syncing = syncing,
                loadingRoutes = loadingRoutes,
                logs = logs,
                logListState = logListState,
                onLogin = {
                    appendLog("打开 iGPSPORT 登录页")
                    loginLauncher.launch(
                        android.content.Intent(context, IgpsportLoginActivity::class.java),
                    )
                },
                onClearRecords = {
                    if (!loggedIn) {
                        appendLog("清空记录失败：请先登录 iGPSPORT")
                    } else {
                        syncing = true
                        appendLog("开始清空本地下载的 FIT 记录")
                        scope.launch(Dispatchers.IO) {
                            val deleted = runCatching {
                                store.clearDownloadedFiles()
                            }.getOrDefault(0)
                            val currentImportedCount = store.importedFiles().size
                            postMain {
                                syncing = false
                                clearRecordsOnly = true
                                importedCount = currentImportedCount
                                appendLog(
                                    "已清空 $deleted 个本地下载 FIT 文件，热力图仅使用手动导入记录",
                                )
                                refreshLibrary(RouteSourceMode.IMPORTED)
                            }
                        }
                    }
                },
                onContinueSync = {
                    if (!loggedIn) {
                        appendLog("继续同步失败：请先登录 iGPSPORT")
                    } else {
                        syncing = true
                        clearRecordsOnly = false
                        appendLog("开始继续同步 iGPSPORT 骑行记录")
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                syncService.sync(
                                    onProgress = { message ->
                                        postMain { appendLog(message) }
                                    },
                                )
                            }
                            val message = result.fold(
                                onSuccess = { it.message },
                                onFailure = { it.message ?: "继续同步失败" },
                            )
                            postMain {
                                syncing = false
                                appendLog(message)
                                val nextMode = if (importedCount > 0) {
                                    RouteSourceMode.ALL
                                } else {
                                    RouteSourceMode.DOWNLOADED
                                }
                                refreshLibrary(nextMode)
                            }
                        }
                    }
                },
                onResync = {
                    if (loggedIn) {
                        syncing = true
                        clearRecordsOnly = false
                        appendLog("开始重新同步，将清空本地下载 FIT 文件并重新下载")
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                syncService.sync(
                                    onProgress = { message ->
                                        postMain { appendLog(message) }
                                    },
                                    clearDownloadedFirst = true,
                                )
                            }
                            val message = result.fold(
                                onSuccess = { it.message },
                                onFailure = { it.message ?: "重新同步失败" },
                            )
                            postMain {
                                syncing = false
                                appendLog(message)
                                val nextMode = if (importedCount > 0) {
                                    RouteSourceMode.ALL
                                } else {
                                    RouteSourceMode.DOWNLOADED
                                }
                                refreshLibrary(nextMode)
                            }
                        }
                    } else {
                        appendLog("重新同步失败：请先登录 iGPSPORT")
                    }
                },
                onLogout = {
                    showLogoutDialog = true
                },
                onShowHelp = { showHelpDialog = true },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        appendLog("打开系统目录选择器")
                        importLauncher.launch(null)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = !loadingRoutes && !syncing,
                ) {
                    Text(
                        text = "从本地导入 FIT 文件夹",
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (importedCount <= 0) {
                            appendLog("没有可清除的导入 FIT 文件")
                        } else {
                            appendLog("开始清除导入的 FIT 文件")
                            scope.launch(Dispatchers.IO) {
                                val deleted = store.clearImportedFiles()
                                postMain {
                                    importedCount = 0
                                    appendLog("已清除 $deleted 个导入的 FIT 文件")
                                    refreshLibrary(
                                        if (loggedIn) RouteSourceMode.DOWNLOADED
                                        else RouteSourceMode.IMPORTED,
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = !loadingRoutes && !syncing && importedCount > 0,
                ) {
                    Text(
                        text = "清除导入的 fit 文件",
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }

            StatisticsCard(
                library = routeLibrary,
                sourceMode = sourceMode,
                loadingRoutes = loadingRoutes,
            )

                MapCard(
                    routes = routes,
                    modifier = Modifier.weight(1f),
                    onExpand = { mapFullscreen = true },
                    heatData = routeHeatState.heatData,
                    renderingHeat = routeHeatState.rendering,
                    colorMode = heatColorMode,
                    baseMapStyle = baseMapStyle,
                    singleRouteColor = singleRouteColor,
                    heatVisible = heatVisible,
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("确认登出") },
            text = { Text("登出后会清空账号信息和 authorization，本地下载的 FIT 文件会保留。") },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.clearLogin()
                        loggedIn = false
                        username = ""
                        clearRecordsOnly = false
                        showLogoutDialog = false
                        appendLog("已登出并清空账号与 authorization 信息，本地 FIT 文件保留")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("确认登出")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("软件信息与使用说明") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = """
                                隐私与联网提示

                                本软件的联网功能仅用于登录账号、同步骑行记录与下载背景地图。无需登录账号，也可以只从本地加载 FIT 文件生成路线。

                                如仍担心隐私问题，可在系统设置中禁用本 App 的联网权限。禁用后，导入 FIT 文件仍可生成热力图，只是不会显示背景地图。

                                账号登录功能仅用于下载骑行记录，不会上传任何信息。由于开发者目前只有迹驰账号，因此只开发了迹驰路线同步功能。如需导入其他平台的骑行记录，可批量导入 FIT 文件生成热力图。
                            """.trimIndent(),
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = """
                            骑行记录 ${BuildConfig.VERSION_NAME}

                            一、账号与同步
                            • 点击“登录迹驰账号”打开 iGPSPORT 登录页面。登录成功后软件会自动返回并保存登录状态。
                            • “继续同步”会在保留现有记录的基础上获取骑行记录。
                            • “重新同步”会先清除本地已下载的账号记录，再重新下载；手动导入的 FIT 文件不受影响。
                            • 登出会清除账号和 authorization 信息，但不会删除本地 FIT 文件。

                            二、本地 FIT 文件
                            • 点击“从本地导入 FIT 文件夹”，选择包含 .fit 文件的目录。
                            • 下载记录和导入记录会按文件内容去重，同一文件不会重复计入统计和地图。
                            • “清除导入的 fit 文件”只删除手动导入的副本，不影响账号同步下载的记录。

                            三、数据统计
                            • “账号同步的记录”是本地保存的账号下载文件数量。
                            • “导入的记录”是手动导入文件数量。
                            • “去重后的记录”是两种来源按内容去重后的有效记录数量。
                            • 总里程按 FIT 原始轨迹计算，单位为 km。

                            四、路线密度地图
                            • 地图直接沿 FIT 路线绘制，不再把相近路线拟合或合并成中心线。
                            • 每段路线统计其 20 米范围内出现的不同骑行路线数量，附近路线越多，密度越高。
                            • 蓝红样式中，低密度为蓝色，密度升高后逐渐变为红色。
                            • 单色样式中，低密度使用浅色，密度升高后颜色逐渐加深。
                            • 关闭“热度显示”后，蓝红样式统一显示蓝色，单色样式统一显示最浅颜色。

                            五、全屏地图按钮
                            • 叠放图层按钮：切换亮色或暗色背景地图。
                            • 调色盘按钮：选择单色路线的颜色；蓝红样式下保持显示但不可点击。
                            • “切换样式”：在蓝红样式与单色样式之间切换。
                            • “热度显示”：开启或关闭密度造成的颜色变化。
                            • 地图样式、路线样式、单色颜色和热度显示状态都会自动保存，下次启动继续使用。
                            • 使用系统返回键退出全屏地图；地图左下角的 i 按钮可查看地图数据来源。

                            六、使用提示
                            • 地图底图、账号登录和在线同步需要网络连接；已保存的 FIT 路线解析在本地完成。
                            • 路线较多时密度计算需要一些时间，计算期间地图中央会显示加载指示。
                            • 如果地图没有路线，请检查当前统计范围，并确认已成功同步或导入有效 FIT 文件。
                            • “运行日志”会显示导入、同步、解析和错误信息，可用于判断当前处理进度。
                        """.trimIndent(),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }) {
                    Text("知道了")
                }
            },
        )
    }
}

@Composable
private fun AccountCard(
    loggedIn: Boolean,
    syncing: Boolean,
    loadingRoutes: Boolean,
    logs: List<String>,
    logListState: androidx.compose.foundation.lazy.LazyListState,
    onLogin: () -> Unit,
    onClearRecords: () -> Unit,
    onContinueSync: () -> Unit,
    onResync: () -> Unit,
    onLogout: () -> Unit,
    onShowHelp: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = if (loggedIn) onLogout else onLogin,
                    modifier = Modifier.weight(1f),
                    enabled = !syncing && !loadingRoutes,
                    colors = if (loggedIn) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(if (loggedIn) "登出" else "登录迹驰账号")
                }
                Button(
                    onClick = onClearRecords,
                    modifier = Modifier.weight(1f),
                    enabled = loggedIn && !syncing && !loadingRoutes,
                ) {
                    Text("清空记录")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onContinueSync,
                    modifier = Modifier.weight(1f),
                    enabled = loggedIn && !syncing && !loadingRoutes,
                ) {
                    Text("继续同步")
                }
                Button(
                    onClick = onResync,
                    modifier = Modifier.weight(1f),
                    enabled = loggedIn && !syncing && !loadingRoutes,
                ) {
                    Text("重新同步")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onShowHelp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "暂无日志",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(logs.size) { index ->
                            Text(
                                text = logs[index],
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsCard(
    library: RouteLibrary?,
    sourceMode: RouteSourceMode,
    loadingRoutes: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "骑行数据统计",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "当前统计范围：${sourceModeLabel(sourceMode)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatItem(
                    label = "账户同步数",
                    value = if (loadingRoutes) "..." else library?.downloadedCount?.toString() ?: "0",
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = "手动导入数",
                    value = if (loadingRoutes) "..." else library?.importedCount?.toString() ?: "0",
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = "总记录数",
                    value = if (loadingRoutes) "..." else library?.deduplicatedCount?.toString() ?: "0",
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = "总里程(km)",
                    value = if (loadingRoutes) {
                        "..."
                    } else {
                        String.format(
                            Locale.US,
                            "%.2f",
                            library?.totalDistanceKm ?: 0.0,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun sourceModeLabel(mode: RouteSourceMode): String {
    return when (mode) {
        RouteSourceMode.DOWNLOADED -> "仅 iGPSPORT 下载路线"
        RouteSourceMode.IMPORTED -> "仅手动导入路线"
        RouteSourceMode.ALL -> "下载与导入路线去重后全部使用"
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MapCard(
    routes: List<LoadedRoute>,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    heatData: HeatData?,
    renderingHeat: Boolean,
    colorMode: HeatColorMode,
    baseMapStyle: BaseMapStyle,
    singleRouteColor: Int,
    heatVisible: Boolean,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "骑行热力图",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                OsmMapContainer(
                    heatData = heatData,
                    renderingHeat = renderingHeat,
                    colorMode = colorMode,
                    baseMapStyle = baseMapStyle,
                    singleRouteColor = singleRouteColor,
                    heatVisible = heatVisible,
                    modifier = Modifier.fillMaxSize(),
                )
                if (routes.isEmpty()) {
                    Text(
                        text = "暂无路线，请先同步或导入 FIT 文件",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = "⤢",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (baseMapStyle == BaseMapStyle.DARK) {
                            Color.White
                        } else {
                            Color.Black
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenMapCard(
    onToggleMapStyle: () -> Unit,
    onToggleRouteStyle: () -> Unit,
    onSingleRouteColorChanged: (Int) -> Unit,
    onToggleHeat: () -> Unit,
    modifier: Modifier = Modifier,
    heatData: HeatData?,
    renderingHeat: Boolean,
    colorMode: HeatColorMode,
    baseMapStyle: BaseMapStyle,
    singleRouteColor: Int,
    heatVisible: Boolean,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        OsmMapContainer(
            heatData = heatData,
            renderingHeat = renderingHeat,
            colorMode = colorMode,
            baseMapStyle = baseMapStyle,
            singleRouteColor = singleRouteColor,
            heatVisible = heatVisible,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapStyleButton(
                dark = baseMapStyle == BaseMapStyle.DARK,
                onClick = onToggleMapStyle,
            )
            Spacer(modifier = Modifier.weight(1f))
            PaletteButton(
                onClick = {
                    if (colorMode == HeatColorMode.SINGLE_COLOR) {
                        showColorPicker = true
                    } else {
                        Toast.makeText(
                            context,
                            "只有纯色模式才可修改颜色",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = onToggleRouteStyle,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ),
            ) {
                Text("切换样式", fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = onToggleHeat,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (heatVisible) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    },
                ),
            ) {
                Text("热度显示", fontSize = 13.sp)
            }
        }
    }

    if (showColorPicker) {
        ColorWheelDialog(
            selectedColor = singleRouteColor,
            onDismiss = { showColorPicker = false },
            onConfirm = { color ->
                onSingleRouteColorChanged(color)
                showColorPicker = false
            },
        )
    }
}

@Composable
private fun MapStyleButton(
    dark: Boolean,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .semantics {
                contentDescription = if (dark) "切换为亮色地图" else "切换为暗色地图"
            },
    ) {
        Canvas(modifier = Modifier.size(25.dp)) {
            fun layerPath(centerY: Float): Path = Path().apply {
                moveTo(size.width * 0.22f, centerY)
                lineTo(size.width * 0.60f, centerY - size.height * 0.16f)
                lineTo(size.width * 0.82f, centerY)
                lineTo(size.width * 0.44f, centerY + size.height * 0.16f)
                close()
            }
            drawPath(
                path = layerPath(size.height * 0.61f),
                color = tint.copy(alpha = 0.55f),
            )
            drawPath(
                path = layerPath(size.height * 0.39f),
                color = tint,
            )
        }
    }
}

@Composable
private fun PaletteButton(
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .semantics { contentDescription = "选择单色路线颜色" },
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val ringRadius = size.minDimension * 0.38f
            val ringWidth = size.minDimension * 0.22f
            val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
            val diameter = ringRadius * 2f
            for (degree in 0 until 360 step 3) {
                drawArc(
                    color = Color.hsv(degree.toFloat(), 0.9f, 0.95f),
                    startAngle = degree.toFloat() - 90f,
                    sweepAngle = 3.5f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = ringWidth),
                )
            }
        }
    }
}

@Composable
private fun ColorWheelDialog(
    selectedColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(selectedColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(selectedColor, it) }
    }
    var hue by remember(selectedColor) { mutableStateOf(initialHsv[0]) }
    val chosenColor = Color.hsv(hue, 0.86f, 0.9f)
    fun updateHue(position: Offset, width: Float, height: Float) {
        hue = (
            Math.toDegrees(
                atan2(
                    (position.y - height / 2f).toDouble(),
                    (position.x - width / 2f).toDouble(),
                ),
            ).toFloat() + 90f + 360f
            ) % 360f
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("单色路线颜色") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                updateHue(
                                    down.position,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                )
                                var pressed = true
                                while (pressed) {
                                    val event = awaitPointerEvent()
                                    event.changes.firstOrNull { it.pressed }?.let { change ->
                                        updateHue(
                                            change.position,
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                        )
                                        change.consume()
                                    }
                                    pressed = event.changes.any { it.pressed }
                                }
                            }
                        },
                ) {
                    val ringRadius = size.minDimension * 0.36f
                    val ringWidth = size.minDimension * 0.16f
                    val ringTopLeft = Offset(
                        x = center.x - ringRadius,
                        y = center.y - ringRadius,
                    )
                    val ringSize = Size(ringRadius * 2f, ringRadius * 2f)
                    for (degree in 0 until 360 step 2) {
                        drawArc(
                            color = Color.hsv(degree.toFloat(), 0.86f, 0.9f),
                            startAngle = degree - 91f,
                            sweepAngle = 3f,
                            useCenter = false,
                            topLeft = ringTopLeft,
                            size = ringSize,
                            style = Stroke(width = ringWidth),
                        )
                    }
                    val angle = Math.toRadians((hue - 90f).toDouble())
                    val selector = Offset(
                        x = center.x + cos(angle).toFloat() * ringRadius,
                        y = center.y + sin(angle).toFloat() * ringRadius,
                    )
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = selector)
                    drawCircle(
                        color = chosenColor,
                        radius = 6.dp.toPx(),
                        center = selector,
                    )
                    drawCircle(chosenColor, radius = size.minDimension * 0.18f)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "#%06X",
                            chosenColor.toArgb() and 0xFFFFFF,
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(chosenColor.toArgb()) }) {
                Text("确定")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
