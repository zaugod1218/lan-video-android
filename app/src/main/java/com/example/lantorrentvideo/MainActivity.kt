package com.example.lantorrentvideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private val Ink = Color(0xFF0B0F14)
private val Card = Color(0xFF151B23)
private val Mint = Color(0xFF5CE1A3)
private val Muted = Color(0xFFA7B0BE)
private val AppColors = darkColorScheme(
    primary = Mint,
    background = Ink,
    surface = Card,
    onPrimary = Ink,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val server by vm.server.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<VideoItem?>(null) }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val discovery = LanDiscovery(context, vm::discovered)
        discovery.start()
        onDispose { discovery.stop() }
    }
    LaunchedEffect(Unit) {
        if (server.isNotBlank()) vm.connect()
    }

    MaterialTheme(colorScheme = AppColors) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            if (playing != null) {
                PlayerScreen(playing!!, token) { playing = null }
            } else {
                LibraryScreen(server, token, state, vm::connect) { playing = it }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    initialServer: String,
    initialToken: String,
    state: UiState,
    onConnect: (String, String) -> Unit,
    onPlay: (VideoItem) -> Unit
) {
    var server by remember(initialServer) { mutableStateOf(initialServer) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("局域网种子影院", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text("播放你拥有或获授权的共享视频", color = Muted)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址，例如 192.168.1.10:8787") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("访问令牌（可选）") },
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onConnect(server, token) },
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
        ) { Text("连接并刷新") }
        Spacer(Modifier.height(18.dp))
        when (state) {
            UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Mint)
            }
            is UiState.Error -> Text(state.message, color = Color(0xFFFF8A80))
            is UiState.Ready -> {
                if (state.videos.isEmpty()) {
                    Text("尚未发现视频。请确认电脑服务已启动，且手机与电脑在同一局域网。", color = Muted)
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.videos, key = { it.id }) { video ->
                            VideoRow(video) { onPlay(video) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRow(video: VideoItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Card, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(video.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(video.torrent, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(formatBytes(video.size), color = Mint, style = MaterialTheme.typography.labelMedium)
        }
        Text("播放", color = Mint)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(video: VideoItem, token: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(video.streamUrl, token) {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (token.isNotBlank()) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
            val item = MediaItem.Builder().setUri(video.streamUrl).build()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", color = Mint, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Text(video.name, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
        }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}
