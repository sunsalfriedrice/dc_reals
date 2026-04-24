package com.project.dc_reels

import com.project.dc_reels.data.CommentPagingSource
import com.project.dc_reels.model.CommentSortOrder
import com.project.dc_reels.model.DcComment
import com.project.dc_reels.util.GalleryUrlNormalizer
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun normalize_gallery_id_from_pc_link() {
        val id = GalleryUrlNormalizer.normalizeGalleryIdFromUrl(
            "https://gall.dcinside.com/board/lists/?id=baseball_new11"
        )
        assertEquals("baseball_new11", id)
    }

    @Test
    fun normalize_gallery_id_from_mobile_link() {
        val id = GalleryUrlNormalizer.normalizeGalleryIdFromUrl(
            "https://m.dcinside.com/board/baseball_new11"
        )
        assertEquals("baseball_new11", id)
    }

    @Test
    fun normalize_gallery_id_without_scheme() {
        val id = GalleryUrlNormalizer.normalizeGalleryIdFromUrl(
            "gall.dcinside.com/board/lists/?id=baseball_new11"
        )
        assertEquals("baseball_new11", id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalize_gallery_id_invalid_link_throws() {
        GalleryUrlNormalizer.normalizeGalleryIdFromUrl("https://example.com")
    }

    @Test
    fun paginate_comments_oldest_order() {
        val comments = (1..20).map { i ->
            DcComment(writer = "u$i", text = "t$i", dateText = "$i")
        }
        val page = CommentPagingSource.paginate(
            comments = comments,
            page = 1,
            pageSize = 15,
            sortOrder = CommentSortOrder.OLDEST
        )
        assertEquals(15, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("t1", page.items.first().text)
    }
}