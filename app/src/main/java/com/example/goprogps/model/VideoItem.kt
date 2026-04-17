package com.example.goprogps.model

import android.net.Uri

data class VideoItem(
    val id: Long,
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateTaken: Long
)
