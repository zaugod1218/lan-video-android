package com.example.lantorrentvideo

data class VideoItem(
    val id: String,
    val name: String,
    val torrent: String,
    val size: Long,
    val mime: String,
    val streamUrl: String
)

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val videos: List<VideoItem>) : UiState
    data class Error(val message: String) : UiState
}
