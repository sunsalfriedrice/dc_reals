package com.project.dc_reels.model

data class DcPost(
    val title: String,
    val url: String,
    val writer: String,
    val dateText: String,
    val recommend: Int,
    val viewCount: Int,
    val preview: String,
    val imageUrl: String?,
    val imageUrls: List<String> = emptyList()
)

