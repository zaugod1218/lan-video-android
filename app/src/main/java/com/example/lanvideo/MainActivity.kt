package com.example.lanvideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class VideoItem(val name: String, val path: String, val url: String, val size: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4F46E5))) { VideoApp() } }
    }

    @Composable
    private fun VideoApp() {
        val prefs = remember { getSharedPreferences("connection", MODE_PRIVATE) }
        var address by remember { mutableStateOf(prefs.getString("address", "http://192.168.1.100:8787")!!) }
        var videos by remember { mutableStateOf(emptyList<VideoItem>()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf<VideoItem?>(null) }

        fun connect() {
            loading = true
            error = null
            val base = normalizeAddress(address)
            prefs.edit().putString("address", base).apply()
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { fetchVideos(base) } }
                    .onSuccess { videos = it }
                    .onFailure { error = "连接失败：${it.message ?: "请检查地址和防火墙"}" }
                loading = false
            }
        }

        playing?.let { item ->
            BackHandler { playing = null }
            PlayerScreen(item, normalizeAddress(address)) { playing = null }
            return
        }

        Scaffold(topBar = { TopAppBar(title = { Text("电脑视频") }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("电脑地址") },
                    placeholder = { Text("http://192.168.1.10:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { connect() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "正在连接…" else "连接并刷新")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (!loading && error == null && videos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("连接电脑后，视频会显示在这里", color = Color.Gray)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(videos, key = { it.path }) { video -> VideoRow(video) { playing = video } }
                    }
                }
            }
        }
    }

    @Composable
    private fun VideoRow(video: VideoItem, onClick: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("▶", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(video.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(video.path, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(video.size), color = Color.Gray)
                }
            }
        }
    }

    @Composable
    private fun PlayerScreen(video: VideoItem, base: String, onBack: () -> Unit) {
        val context = LocalContext.current
        val player = remember(video.url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(URI(base).resolve(video.url).toString()))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            TextButton(onClick = onBack) { Text("← 返回视频列表") }
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Text(video.name, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
        }
    }

    private fun normalizeAddress(value: String): String {
        var result = value.trim().trimEnd('/')
        if (!result.startsWith("http://") && !result.startsWith("https://")) result = "http://$result"
        return "$result/"
    }

    private fun fetchVideos(base: String): List<VideoItem> {
        val connection = URL(URI(base).resolve("api/videos").toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode != 200) error("服务器返回 ${connection.responseCode}")
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return (0 until array.length()).map { index ->
                array.getJSONObject(index).let {
                    VideoItem(it.getString("name"), it.getString("path"), it.getString("url"), it.getLong("size"))
                }
            }
        } finally { connection.disconnect() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.1f KB".format(bytes / 1024.0)
    }
}
