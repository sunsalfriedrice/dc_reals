package com.project.dc_reels.model

data class DcComment(
    val writer: String,
    val text: String,
    val dateText: String,
    val imageUrl: String? = null,
    val isGif: Boolean = false
)
