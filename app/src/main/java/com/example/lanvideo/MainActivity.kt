package com.example.lanvideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.max

enum class LibraryType(val apiName: String, val label: String, val symbol: String) {
    VIDEO("video", "视频", "▶"),
    IMAGE("image", "图片", "▧"),
    PDF("pdf", "PDF", "PDF");

    companion object {
        fun fromApi(value: String) = entries.firstOrNull { it.apiName == value } ?: VIDEO
    }
}

data class LibraryItem(
    val name: String,
    val path: String,
    val url: String,
    val size: Long,
    val type: LibraryType,
)

data class PreviewResult(val bitmap: Bitmap? = null, val complete: Boolean = false)
data class PdfLoadResult(val file: File? = null, val pages: Int = 0, val error: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF5B4FE9),
                    secondary = Color(0xFF0F766E),
                    surfaceVariant = Color(0xFFF1F3FA),
                )
            ) { LibraryApp() }
        }
    }

    @Composable
    private fun LibraryApp() {
        val prefs = remember { getSharedPreferences("connection", MODE_PRIVATE) }
        var address by remember { mutableStateOf(prefs.getString("address", "http://192.168.1.16:8787")!!) }
        var media by remember { mutableStateOf(emptyList<LibraryItem>()) }
        var selectedType by remember { mutableStateOf<LibraryType?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var viewing by remember { mutableStateOf<LibraryItem?>(null) }

        fun connect() {
            loading = true
            error = null
            val base = normalizeAddress(address)
            prefs.edit().putString("address", base.trimEnd('/')).apply()
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { fetchMedia(base) } }
                    .onSuccess { media = it }
                    .onFailure { error = "连接失败：${it.message ?: "请检查电脑服务和 Wi-Fi"}" }
                loading = false
            }
        }

        viewing?.let { item ->
            BackHandler { viewing = null }
            ViewerScreen(item, normalizeAddress(address)) { viewing = null }
            return
        }

        val visibleMedia = remember(media, selectedType) {
            selectedType?.let { type -> media.filter { it.type == type } } ?: media
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("家庭媒体库", fontWeight = FontWeight.Bold)
                            Text("视频 · 图片 · PDF", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8F9FF)),
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).background(Color(0xFFF8F9FF)).padding(horizontal = 14.dp)
            ) {
                ConnectionCard(address, { address = it }, loading, ::connect)
                FilterRow(selectedType) { selectedType = it }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    visibleMedia.isEmpty() -> EmptyLibrary(error == null && media.isEmpty())
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(154.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        gridItems(visibleMedia, key = { it.path }) { item ->
                            MediaCard(item, normalizeAddress(address)) { viewing = item }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionCard(address: String, onAddress: (String) -> Unit, loading: Boolean, onConnect: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddress,
                    label = { Text("电脑地址") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConnect, enabled = !loading, contentPadding = PaddingValues(14.dp)) {
                    Text(if (loading) "…" else "刷新")
                }
            }
        }
    }

    @Composable
    private fun FilterRow(selected: LibraryType?, onSelect: (LibraryType?) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部") })
            LibraryType.entries.forEach { type ->
                FilterChip(selected = selected == type, onClick = { onSelect(type) }, label = { Text(type.label) })
            }
        }
    }

    @Composable
    private fun EmptyLibrary(showHint: Boolean) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▦", style = MaterialTheme.typography.displayMedium, color = Color(0xFF9CA3AF))
                Text(if (showHint) "媒体库还是空的" else "这个分类没有文件", fontWeight = FontWeight.SemiBold)
                if (showHint) Text("把文件放入电脑共享文件夹后点击刷新", color = Color.Gray)
            }
        }
    }

    @Composable
    private fun MediaCard(item: LibraryItem, base: String, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Color(0xFFE8EAF5))) {
                MediaPreview(item, absoluteUrl(base, item.url), Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                    color = Color(0xCC111827),
                    shape = RoundedCornerShape(7.dp),
                ) {
                    Text(item.type.label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp, 3.dp))
                }
            }
            Column(Modifier.padding(11.dp)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(formatSize(item.size), color = Color.Gray, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    @Composable
    private fun MediaPreview(item: LibraryItem, url: String, modifier: Modifier = Modifier) {
        val preview by produceState(PreviewResult(), url, item.type) {
            value = withContext(Dispatchers.IO) {
                PreviewResult(
                    bitmap = when (item.type) {
                        LibraryType.VIDEO -> loadVideoFrame(url)
                        LibraryType.IMAGE -> loadNetworkBitmap(url, sampleSize = 2)
                        LibraryType.PDF -> null
                    },
                    complete = true,
                )
            }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            preview.bitmap?.let {
                Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: if (!preview.complete && item.type != LibraryType.PDF) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            } else {
                Text(item.type.symbol, color = Color(0xFF5B4FE9), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }

    @Composable
    private fun ViewerScreen(item: LibraryItem, base: String, onBack: () -> Unit) {
        if (item.type == LibraryType.VIDEO) {
            VideoViewer(absoluteUrl(base, item.url), onBack)
            return
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (item.type) {
                    LibraryType.VIDEO -> Unit
                    LibraryType.IMAGE -> ImageViewer(item, absoluteUrl(base, item.url))
                    LibraryType.PDF -> PdfViewer(item, absoluteUrl(base, item.url))
                }
            }
        }
    }

    @Composable
    private fun VideoViewer(url: String, onBack: () -> Unit) {
        val context = LocalContext.current
        val activity = this@MainActivity
        val player = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose {
                player.release()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = true
                        controllerAutoShow = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color(0x99000000),
                    contentColor = Color.White,
                ),
            ) { Text("← 返回") }
        }
    }

    @Composable
    private fun ImageViewer(item: LibraryItem, url: String) {
        val result by produceState(PreviewResult(), url) {
            value = withContext(Dispatchers.IO) { PreviewResult(loadNetworkBitmap(url, 1), true) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            result.bitmap?.let {
                Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } ?: if (!result.complete) CircularProgressIndicator(color = Color.White)
            else Text("图片加载失败", color = Color.White)
        }
    }

    @Composable
    private fun PdfViewer(item: LibraryItem, url: String) {
        val context = LocalContext.current
        val result by produceState(PdfLoadResult(), url) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.cacheDir, "lan-pdf-${url.hashCode().toUInt()}.pdf")
                    if (!file.exists() || file.length() == 0L) downloadFile(url, file)
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val pages = PdfRenderer(descriptor).use { it.pageCount }
                    PdfLoadResult(file, pages)
                }.getOrElse { PdfLoadResult(error = it.message ?: "PDF 下载失败") }
            }
        }
        when {
            result.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法打开 PDF：${result.error}", color = MaterialTheme.colorScheme.error)
            }
            result.file == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("正在下载 ${item.name}")
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().background(Color(0xFFCED1D9)),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items((0 until result.pages).toList(), key = { it }) { page ->
                    PdfPage(result.file!!, page)
                }
            }
        }
    }

    @Composable
    private fun PdfPage(file: File, pageIndex: Int) {
        val bitmap by produceState<Bitmap?>(null, file, pageIndex) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val width = 1200
                            val height = max(1, width * page.height / page.width)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(AndroidColor.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }.getOrNull()
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
            bitmap?.let {
                Image(it.asImageBitmap(), "第 ${pageIndex + 1} 页", Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), contentScale = ContentScale.FillWidth)
            } ?: Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    private fun normalizeAddress(value: String): String {
        var result = value.trim().trimEnd('/')
        if (!result.startsWith("http://") && !result.startsWith("https://")) result = "http://$result"
        return "$result/"
    }

    private fun absoluteUrl(base: String, relative: String) = URI(base).resolve(relative).toString()

    private fun fetchMedia(base: String): List<LibraryItem> {
        val connection = URL(absoluteUrl(base, "api/media")).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 20000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode != 200) error("服务器返回 ${connection.responseCode}")
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return (0 until array.length()).map { index ->
                array.getJSONObject(index).let {
                    LibraryItem(
                        name = it.getString("name"),
                        path = it.getString("path"),
                        url = it.getString("url"),
                        size = it.getLong("size"),
                        type = LibraryType.fromApi(it.optString("type", "video")),
                    )
                }
            }
        } finally { connection.disconnect() }
    }

    private fun loadVideoFrame(url: String): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, emptyMap())
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 300)
            } else {
                retriever.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun loadNetworkBitmap(url: String, sampleSize: Int): Bitmap? = runCatching {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 7000
            readTimeout = 20000
        }
        connection.getInputStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }
    }.getOrNull()

    private fun downloadFile(url: String, target: File) {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 7000
            readTimeout = 60000
        }
        connection.getInputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.1f KB".format(bytes / 1024.0)
    }
}
