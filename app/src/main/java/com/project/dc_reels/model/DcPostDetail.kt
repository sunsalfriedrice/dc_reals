package com.project.dc_reels.model

data class DcPostDetail(
    val title: String,
    val writer: String,
    val dateText: String,
    val bodyText: String,
    val imageUrls: List<String>,
    val contentBlocks: List<PostContentBlock> = emptyList()
)

