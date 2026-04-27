package com.project.dc_reels.data

import com.project.dc_reels.model.DcComment
import com.project.dc_reels.model.DcPost
import com.project.dc_reels.model.DcPostDetail
import com.project.dc_reels.model.PostContentBlock
import com.project.dc_reels.parser.GalleryMetaParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URLDecoder
import java.net.URI
import java.nio.charset.StandardCharsets
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
            val title = sanitizeSpoilerText(titleNode.text().trim())
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
            val titleNode = item.selectFirst(".subjectin")?.clone()
            titleNode?.select(".spoiler")?.remove()
            val title = sanitizeSpoilerText(titleNode?.text()?.trim().orEmpty())
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
                imageUrl = null,
                imageUrls = emptyList()
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
                    imageUrl = detail.imageUrls.firstOrNull(),
                    imageUrls = detail.imageUrls
                )
            }.getOrDefault(post)
        }
    }

    private fun fetchPostDetailPreview(postUrl: String): PostDetailPreview {
        val detail = fetchPostDetail(postUrl)
        val preview = detail.bodyText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(PREVIEW_MAX_CHARS)
            .orEmpty()

        return PostDetailPreview(
            preview = preview,
            imageUrls = detail.imageUrls
        )
    }

    fun fetchPostDetail(postUrl: String): DcPostDetail {
        val mobileUrl = toMobilePostUrl(postUrl)
        politeDelay()
        val doc = Jsoup.connect(mobileUrl)
            .userAgent(MOBILE_USER_AGENT)
            .timeout(TIMEOUT_MS)
            .ignoreHttpErrors(true)
            .get()

        val body = doc.selectFirst(".thum-txtin, .write_div, .writing_view_box")
        val bodyClone = body?.clone()?.apply {
            select(".spoiler, [class*=spoiler], [id*=spoiler]").remove()
        }
        val bodyText = sanitizeSpoilerText(bodyClone?.text()?.trim().orEmpty())

        val imageUrls = extractImageUrls(bodyClone, doc, mobileUrl)
        val contentBlocks = buildContentBlocks(bodyClone, mobileUrl)
        val title = doc.selectFirst(".gallview-tit, .title-subject, .title_subject, .title")
            ?.text()
            ?.trim()
            .orEmpty()
            .let(::sanitizeSpoilerText)
        val writer = doc.selectFirst(".ginfo li.list-nick, .gall_writer, .nickname")
            ?.text()
            ?.trim()
            .orEmpty()
        val dateText = doc.selectFirst(".ginfo li:nth-child(3), .gall_date, .date")
            ?.text()
            ?.trim()
            .orEmpty()

        return DcPostDetail(
            title = title,
            writer = writer,
            dateText = dateText,
            bodyText = bodyText,
            imageUrls = imageUrls,
            contentBlocks = contentBlocks
        )
    }

    fun toMobilePostUrl(rawPostUrl: String): String {
        val normalized = normalizeUrl(rawPostUrl)
        if (normalized.contains("m.dcinside.com/board/")) {
            return normalized.substringBefore("#")
        }

        val uri = runCatching { URI(normalized) }.getOrNull()
        val query = uri?.rawQuery.orEmpty()
        val params = parseQuery(query)
        val id = params["id"].orEmpty()
        val no = params["no"].orEmpty()
        if (id.isNotBlank() && no.isNotBlank()) {
            return "https://m.dcinside.com/board/$id/$no"
        }

        val match = Regex("/board/([^/?#]+)/([0-9]+)").find(normalized)
        if (match != null) {
            return "https://m.dcinside.com/board/${match.groupValues[1]}/${match.groupValues[2]}"
        }

        return normalized
    }

    private fun normalizeImageUrl(baseUrl: String, raw: String): String? {
        val cleaned = raw.trim().removePrefix("\"").removeSuffix("\"")
        if (cleaned.isBlank()) return null
        if (cleaned.startsWith("//")) return "https:$cleaned"
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        return runCatching {
            URI(baseUrl).resolve(cleaned).toString()
        }.getOrNull()
    }

    private fun extractImageUrls(body: Element?, doc: Document, baseUrl: String): List<String> {
        if (body == null) return emptyList()
        val urls = body.select("img[data-original], img[src]")
            .mapNotNull { img ->
                if (isSpoilerImage(img)) return@mapNotNull null
                val raw = img.attr("data-original")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("src") }
                val normalized = normalizeImageUrl(baseUrl, raw).orEmpty()
                if (normalized.isBlank()) return@mapNotNull null
                if (normalized.contains("gallview_loading_ori.gif") || normalized.contains("dccon_loading")) {
                    return@mapNotNull null
                }
                normalized
            }

        if (urls.isNotEmpty()) return urls.distinct()

        // Fallback for rare pages where body image tags are rewritten but og:image is present.
        val ogImage = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            .orEmpty()
        val fallback = normalizeImageUrl(baseUrl, ogImage)
        return listOfNotNull(fallback)
    }

    private fun buildContentBlocks(body: Element?, baseUrl: String): List<PostContentBlock> {
        if (body == null) return emptyList()
        val blocks = mutableListOf<PostContentBlock>()
        collectContentBlocks(body, baseUrl, blocks)
        return mergeAdjacentTextBlocks(blocks)
    }

    private fun collectContentBlocks(node: Node, baseUrl: String, out: MutableList<PostContentBlock>) {
        when (node) {
            is TextNode -> {
                val text = sanitizeSpoilerText(node.text().trim())
                if (text.isNotBlank()) {
                    out += PostContentBlock(type = PostContentBlock.Type.TEXT, text = text)
                }
            }

            is Element -> {
                val classInfo = node.className().lowercase()
                val idInfo = node.id().lowercase()
                if (classInfo.contains("spoiler") || idInfo.contains("spoiler")) {
                    return
                }

                if (node.normalName() == "img") {
                    if (isSpoilerImage(node)) return
                    val raw = node.attr("data-original")
                        .ifBlank { node.attr("data-src") }
                        .ifBlank { node.attr("src") }
                    val normalized = normalizeImageUrl(baseUrl, raw).orEmpty()
                    if (
                        normalized.isBlank() ||
                        normalized.contains("gallview_loading_ori.gif") ||
                        normalized.contains("dccon_loading")
                    ) {
                        return
                    }
                    out += PostContentBlock(type = PostContentBlock.Type.IMAGE, imageUrl = normalized)
                    return
                }

                node.childNodes().forEach { child ->
                    collectContentBlocks(child, baseUrl, out)
                }

                if (node.normalName() == "p" || node.normalName() == "div") {
                    if (out.lastOrNull()?.type == PostContentBlock.Type.TEXT) {
                        out += PostContentBlock(type = PostContentBlock.Type.TEXT, text = "\n")
                    }
                }
            }
        }
    }

    private fun mergeAdjacentTextBlocks(blocks: List<PostContentBlock>): List<PostContentBlock> {
        if (blocks.isEmpty()) return blocks
        val merged = mutableListOf<PostContentBlock>()
        val textBuffer = StringBuilder()

        fun flushText() {
            val text = textBuffer.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
            if (text.isNotBlank()) {
                merged += PostContentBlock(type = PostContentBlock.Type.TEXT, text = text)
            }
            textBuffer.clear()
        }

        blocks.forEach { block ->
            if (block.type == PostContentBlock.Type.TEXT) {
                if (textBuffer.isNotEmpty()) textBuffer.append(' ')
                textBuffer.append(block.text)
            } else {
                flushText()
                merged += block
            }
        }
        flushText()
        return merged
    }

    private fun isSpoilerImage(img: Element): Boolean {
        val textSignals = listOf(
            img.attr("alt"),
            img.attr("title"),
            img.attr("class"),
            img.parents().joinToString(" ") { it.className() },
            img.parents().joinToString(" ") { it.id() }
        ).joinToString(" ").lowercase()

        return textSignals.contains("spoiler") ||
            textSignals.contains("스포") ||
            textSignals.contains("스포일러")
    }

    private fun sanitizeSpoilerText(raw: String): String {
        if (raw.isBlank()) return raw
        var text = raw
        text = text.replace(Regex("\\[[^\\]]*스포[^\\]]*\\]", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("\\([^)]*스포[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("스포일러\\s*주의", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("스포\\s*주의", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("\\b스포\\b", RegexOption.IGNORE_CASE), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun fetchAllComments(postUrl: String): List<DcComment> {
        val mobileUrl = toMobilePostUrl(postUrl)
        politeDelay()
        val doc = Jsoup.connect(mobileUrl)
            .userAgent(MOBILE_USER_AGENT)
            .timeout(TIMEOUT_MS)
            .ignoreHttpErrors(true)
            .get()

        val blocks = doc.select(
            ".all-comment-lst li.comment, .comment_box, .comment_row, .reply_list li, .cmt_list li, .comment_wrap li"
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
        return block.selectFirst(".nick, .nickname, .name, .writer")?.text()?.trim().orEmpty()
    }

    private fun readDate(block: Element): String {
        return block.selectFirst(".fr, .date_time, .date, .time")?.text()?.trim().orEmpty()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
            .toMap()
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
        private const val DETAIL_PREVIEW_LIMIT = 60
        private const val PREVIEW_MAX_CHARS = 140
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    }

    private data class PostDetailPreview(
        val preview: String,
        val imageUrls: List<String>
    )
}

