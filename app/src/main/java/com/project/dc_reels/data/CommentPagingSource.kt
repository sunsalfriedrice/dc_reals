package com.project.dc_reels.data

import com.project.dc_reels.model.CommentSortOrder
import com.project.dc_reels.model.DcComment

data class CommentPage(
    val items: List<DcComment>,
    val hasMore: Boolean,
    val nextPage: Int
)

object CommentPagingSource {
    fun paginate(
        comments: List<DcComment>,
        page: Int,
        pageSize: Int,
        sortOrder: CommentSortOrder
    ): CommentPage {
        val sorted = when (sortOrder) {
            CommentSortOrder.NEWEST -> comments.reversed()
            CommentSortOrder.OLDEST -> comments
        }

        val from = (page - 1) * pageSize
        if (from >= sorted.size) {
            return CommentPage(emptyList(), hasMore = false, nextPage = page)
        }

        val to = minOf(from + pageSize, sorted.size)
        return CommentPage(
            items = sorted.subList(from, to),
            hasMore = to < sorted.size,
            nextPage = page + 1
        )
    }
}

