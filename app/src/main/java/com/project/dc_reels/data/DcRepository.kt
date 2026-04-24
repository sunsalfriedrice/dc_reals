package com.project.dc_reels.data

import com.project.dc_reels.model.DcComment
import com.project.dc_reels.model.DcPost
import com.project.dc_reels.parser.GalleryMetaParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.random.Random

class DcRepository {
    fun resolveFinalUrl(rawUrl: String): String {
        val normalized = normalizeUrl(rawUrl)
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return normalized
        }
        politeDelay()
        return Jsoup.connect(normalized)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .execute()
            .url()
            .toString()
    }

    fun fetchGalleryName(galleryId: String): String {
        politeDelay()
        val doc = Jsoup.connect(listUrl(galleryId))
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()
        return GalleryMetaParser.extractGalleryNameOrId(doc, galleryId)
    }

    fun fetchConceptPosts(galleryId: String): List<DcPost> {
        // DC concept feed is most stable on mobile endpoint with recommend=1.
        politeDelay()
        val mobileDoc = Jsoup.connect(mobileConceptListUrl(galleryId))
            .userAgent(MOBILE_USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()
        val mobilePosts = parseMobilePosts(mobileDoc)
        if (mobilePosts.isNotEmpty()) {
            return enrichPostsForReels(mobilePosts)
        }

        // Fallback to PC list only when mobile parsing fails.
        politeDelay()
        val pcDoc = Jsoup.connect(listUrl(galleryId))
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()
        val pcPosts = parsePcPosts(pcDoc).filter { it.recommend > 0 }
        if (pcPosts.isNotEmpty()) {
            return enrichPostsForReels(pcPosts)
        }

        throw IllegalStateException("no parsable posts from pc/mobile list")
    }

    private fun parsePcPosts(doc: Document): List<DcPost> {
        val rows = doc.select("tr.ub-content, tr.us-post")
        val posts = rows.mapNotNull { row ->
            val numberText = row.selectFirst("td.gall_num")?.text()?.trim().orEmpty()
            val dataType = row.attr("data-type")
            val numberValue = numberText.toIntOrNull()

            // Skip pinned/notice/survey/ad rows and keep normal numbered posts.
            val isPinned = row.hasClass("notice") || dataType == "icon_notice" || numberValue == null
            if (isPinned) return@mapNotNull null

            val titleNode = row.selectFirst("td.gall_tit a") ?: return@mapNotNull null
            val title = titleNode.text().trim()
            val href = titleNode.absUrl("href")
            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            val writer = row.selectFirst("td.gall_writer")?.text()?.trim().orEmpty()
            val date = row.selectFirst("td.gall_date")?.attr("title")
                ?.ifBlank { row.selectFirst("td.gall_date")?.text().orEmpty() }
                .orEmpty()
            val recommend = row.selectFirst("td.gall_recommend")?.text()
                ?.replace(",", "")
                ?.toIntOrNull() ?: 0
            val views = row.selectFirst("td.gall_count")?.text()
                ?.replace(",", "")
                ?.toIntOrNull() ?: 0

            DcPost(
                title = title,
                url = href,
                writer = writer,
                dateText = date,
                recommend = recommend,
                viewCount = views,
                preview = title,
                imageUrl = null
            )
        }
        return posts
    }

    private fun parseMobilePosts(doc: Document): List<DcPost> {
        return doc.select("ul.gall-detail-lst > li").mapNotNull { item ->
            val link = item.selectFirst("a.lt") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val title = item.selectFirst(".subjectin")?.text()?.trim().orEmpty()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null

            val writer = item.selectFirst(".ginfo .list-nick")?.text()?.trim().orEmpty()
            val infoItems = item.select(".ginfo li")
            val date = infoItems.getOrNull(1)?.text()?.trim().orEmpty()
            val views = parseNumber(infoItems.firstOrNull { it.text().contains("조회") }?.text().orEmpty())
            val recommend = parseNumber(
                item.selectFirst(".ginfo li.up-add span, .ginfo li.up span")?.text().orEmpty()
            )

            DcPost(
                title = title,
                url = href,
                writer = writer,
                dateText = date,
                recommend = recommend,
                viewCount = views,
                preview = title,
                imageUrl = null
            )
        }
    }

    private fun parseNumber(text: String): Int {
        val normalized = text.replace(",", "")
        val digitsOnly = normalized.filter { it.isDigit() }
        return digitsOnly.toIntOrNull() ?: 0
    }

    private fun enrichPostsForReels(posts: List<DcPost>): List<DcPost> {
        return posts.mapIndexed { index, post ->
            // Limit expensive detail crawls; remaining cards still open full detail on tap.
            if (index >= DETAIL_PREVIEW_LIMIT) return@mapIndexed post

            runCatching {
                val detail = fetchPostDetailPreview(post.url)
                post.copy(
                    preview = detail.preview.ifBlank { post.title },
                    imageUrl = detail.imageUrl
                )
            }.getOrDefault(post)
        }
    }

    private fun fetchPostDetailPreview(postUrl: String): PostDetailPreview {
        politeDelay()
        val doc = Jsoup.connect(postUrl)
            .userAgent(MOBILE_USER_AGENT)
            .timeout(TIMEOUT_MS)
            .ignoreHttpErrors(true)
            .get()

        val body = doc.selectFirst(".thum-txtin, .write_div, .writing_view_box")
        val preview = body?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(PREVIEW_MAX_CHARS)
            .orEmpty()

        val imageNode = doc.selectFirst(
            ".thum-txtin img[data-original], .thum-txtin img[src], .write_div img[data-original], .write_div img[src], .writing_view_box img[src]"
        )
        val imageRaw = imageNode?.attr("data-original")
            ?.ifBlank { imageNode.attr("src") }
            .orEmpty()

        return PostDetailPreview(
            preview = preview,
            imageUrl = normalizeImageUrl(postUrl, imageRaw)
        )
    }

    private fun normalizeImageUrl(baseUrl: String, raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return runCatching {
            URI(baseUrl).resolve(raw).toString()
        }.getOrNull()
    }

    fun fetchAllComments(postUrl: String): List<DcComment> {
        politeDelay()
        val doc = Jsoup.connect(postUrl)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .ignoreHttpErrors(true)
            .get()

        val blocks = doc.select(
            ".comment_box, .comment_row, .reply_list li, .cmt_list li, .comment_wrap li"
        )
        val comments = blocks.mapNotNull { block ->
            val text = readCommentText(block)
            if (text.isBlank()) return@mapNotNull null
            val writer = readWriter(block)
            val date = readDate(block)
            DcComment(writer = writer.ifBlank { "익명" }, text = text, dateText = date)
        }

        return comments.distinctBy { "${it.writer}|${it.text}|${it.dateText}" }
    }

    private fun readCommentText(block: Element): String {
        val first = block.selectFirst(".usertxt, .txt, .comment, .memo")?.text()?.trim().orEmpty()
        if (first.isNotBlank()) return first

        val fallback = block.ownText().trim()
        if (fallback.isNotBlank() && !fallback.contains("삭제") && !fallback.contains("차단")) {
            return fallback
        }
        return ""
    }

    private fun readWriter(block: Element): String {
        return block.selectFirst(".nickname, .name, .writer")?.text()?.trim().orEmpty()
    }

    private fun readDate(block: Element): String {
        return block.selectFirst(".fr, .date_time, .date, .time")?.text()?.trim().orEmpty()
    }

    private fun politeDelay() {
        val delayMs = Random.nextLong(300L, 1401L)
        Thread.sleep(delayMs)
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else if (trimmed.contains("dcinside.com", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    private fun listUrl(galleryId: String): String {
        return "https://gall.dcinside.com/board/lists/?id=$galleryId"
    }

    private fun mobileListUrl(galleryId: String): String {
        return "https://m.dcinside.com/board/$galleryId"
    }

    private fun mobileConceptListUrl(galleryId: String): String {
        return "https://m.dcinside.com/board/$galleryId?recommend=1"
    }

    companion object {
        private const val TIMEOUT_MS = 10000
        private const val DETAIL_PREVIEW_LIMIT = 12
        private const val PREVIEW_MAX_CHARS = 140
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    }

    private data class PostDetailPreview(
        val preview: String,
        val imageUrl: String?
    )
}

