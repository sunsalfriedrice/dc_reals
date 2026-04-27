package com.project.dc_reels.model

data class PostContentBlock(
    val type: Type,
    val text: String = "",
    val imageUrl: String? = null
) {
    enum class Type {
        TEXT,
        IMAGE
    }
}

