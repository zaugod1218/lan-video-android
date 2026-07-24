package com.example.lantorrentvideo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class LanApi {
    suspend fun listVideos(server: String, token: String): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val base = normalizeServer(server)
            val connection = URL("$base/api/videos").openConnection() as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("服务器返回 HTTP $code")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(body)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            VideoItem(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                torrent = item.optString("torrent", "未归组"),
                                size = item.getLong("size"),
                                mime = item.optString("mime", "video/*"),
                                streamUrl = "$base${item.getString("stream_url")}"
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

    fun normalizeServer(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
