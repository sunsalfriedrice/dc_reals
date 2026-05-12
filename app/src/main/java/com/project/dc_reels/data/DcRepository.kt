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
        return fetchConceptPosts(galleryId, 1)
    }

    fun fetchConceptPosts(galleryId: String, page: Int): List<DcPost> {
        // DC concept feed is most stable on mobile endpoint with recommend=1.
        politeDelay()
        val mobileDoc = Jsoup.connect(mobileConceptListUrl(galleryId, page))
            .userAgent(MOBILE_USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()
        val mobilePosts = parseMobilePosts(mobileDoc)
        if (mobilePosts.isNotEmpty()) {
            return enrichPostsForReels(mobilePosts)
        }

        // Fallback to PC list only when mobile parsing fails.
        politeDelay()
        val pcDoc = Jsoup.connect(listUrl(galleryId, page))
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
                val detail = fetchPostPreview(post.url)
                post.copy(
                    preview = detail.preview.ifBlank { post.title },
                    imageUrl = detail.imageUrls.firstOrNull(),
                    imageUrls = detail.imageUrls
                )
            }.getOrDefault(post)
        }
    }

    fun fetchPostPreview(postUrl: String): PostPreview {
        val detail = fetchPostDetail(postUrl)
        val preview = detail.bodyText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(PREVIEW_MAX_CHARS)
            .orEmpty()

        return PostPreview(
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
            select(".spoiler, [class*=spoiler], [id*=spoiler], [class*=spoil], [id*=spoil]").remove()
            select("img").filter { img -> isSpoilerImage(img) }.forEach { it.remove() }
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

    private fun parseSrcsetUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val firstCandidate = raw.split(",").firstOrNull().orEmpty().trim()
        val url = firstCandidate.split(" ").firstOrNull().orEmpty().trim()
        return url.ifBlank { null }
    }

    private fun readImageUrl(element: Element, baseUrl: String): String? {
        val raw = element.attr("data-original")
            .ifBlank { element.attr("data-src") }
            .ifBlank { element.attr("data-srcset") }
            .ifBlank { element.attr("srcset") }
            .ifBlank { element.attr("src") }
        val candidate = parseSrcsetUrl(raw) ?: raw
        val normalized = normalizeImageUrl(baseUrl, candidate).orEmpty()
        if (normalized.isBlank()) return null
        if (normalized.contains("/m_webp.png") || normalized.contains("dccon_loading_nobg")) {
            return null
        }
        if (normalized.contains("gallview_loading_ori.gif") || normalized.contains("dccon_loading")) {
            return null
        }
        return normalized
    }

    private fun normalizeVideoUrl(raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return null
        val resolved = when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            else -> return null
        }

        val uri = runCatching { URI(resolved) }.getOrNull() ?: return resolved
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val params = parseQuery(query)

        if (host.contains("gall.dcinside.com") && path.contains("/board/movie/movie_view")) {
            val no = params["no"].orEmpty()
            if (no.isNotBlank()) {
                return "https://m.dcinside.com/movie/player?no=$no&mobile=M&is_copy=1"
            }
        }

        val youtubeId = when {
            host.contains("youtu.be") -> path.trim('/').ifBlank { null }
            host.contains("youtube.com") || host.contains("m.youtube.com") -> when {
                path.startsWith("/watch") -> params["v"]
                path.startsWith("/embed/") -> path.removePrefix("/embed/").trim('/').ifBlank { null }
                path.startsWith("/shorts/") -> path.removePrefix("/shorts/").trim('/').ifBlank { null }
                else -> null
            }
            else -> null
        }

        if (!youtubeId.isNullOrBlank()) {
            return "https://www.youtube.com/embed/$youtubeId"
        }

        return resolved
    }

    private fun readEmbeddedVideoUrl(element: Element): String? {
        val attrs = listOf(
            element.attr("data-src"),
            element.attr("data-ytid"),
            element.attr("data-youtube"),
            element.attr("data-video"),
            element.attr("data-vid"),
            element.attr("data-embed")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        if (attrs.isNotBlank()) {
            return normalizeVideoUrl(attrs)
        }
        return null
    }

    private fun isVideoLink(raw: String): Boolean {
        val url = raw.lowercase()
        return url.contains("youtube.com") ||
            url.contains("youtu.be") ||
            url.contains("/shorts/") ||
            url.endsWith(".mp4") ||
            url.contains(".mp4?") ||
            url.endsWith(".webm") ||
            url.contains(".webm?") ||
            url.endsWith(".m3u8") ||
            url.contains(".m3u8?")
    }

    private fun extractImageUrls(body: Element?, doc: Document, baseUrl: String): List<String> {
        if (body == null) return emptyList()
        val urls = body.select("img[data-original], img[src], img[srcset], img[data-srcset], source[srcset], source[data-srcset]")
            .mapNotNull { element ->
                if (isSpoilerImage(element)) return@mapNotNull null
                if (element.normalName() == "source" && !isImageSource(element)) {
                    return@mapNotNull null
                }
                readImageUrl(element, baseUrl)
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
                val text = sanitizeSpoilerText(node.wholeText)
                if (text.isNotBlank()) {
                    out += PostContentBlock(type = PostContentBlock.Type.TEXT, text = text)
                }
            }

            is Element -> {
                val classInfo = node.className().lowercase()
                val idInfo = node.id().lowercase()
                if (
                    classInfo.contains("spoiler") ||
                    classInfo.contains("spoil") ||
                    idInfo.contains("spoiler") ||
                    idInfo.contains("spoil")
                ) {
                    return
                }

                val tag = node.normalName()
                if (tag == "br") {
                    out += PostContentBlock(type = PostContentBlock.Type.TEXT, text = "\n")
                    return
                }

                if (tag == "picture") {
                    val source = node.selectFirst("source[srcset], source[data-srcset], img[src], img[srcset], img[data-srcset]")
                    if (source != null) {
                        if (source.normalName() != "source" || isImageSource(source)) {
                            val normalized = readImageUrl(source, baseUrl).orEmpty()
                            if (normalized.isNotBlank()) {
                                out += PostContentBlock(type = PostContentBlock.Type.IMAGE, imageUrl = normalized)
                                return
                            }
                        }
                    }
                }

                if (tag == "iframe") {
                    val raw = node.attr("src").ifBlank { node.attr("data-src") }
                    val normalized = normalizeVideoUrl(raw).orEmpty()
                    if (normalized.isNotBlank()) {
                        out += PostContentBlock(type = PostContentBlock.Type.VIDEO, videoUrl = normalized)
                        return
                    }
                }

                if (tag == "video") {
                    val raw = node.attr("src").ifBlank {
                        node.selectFirst("source[src], source[data-src]")?.attr("data-src")
                            ?.ifBlank { node.selectFirst("source[src]")?.attr("src") }.orEmpty()
                    }
                    val normalized = normalizeVideoUrl(raw).orEmpty()
                    if (normalized.isNotBlank()) {
                        out += PostContentBlock(type = PostContentBlock.Type.VIDEO, videoUrl = normalized)
                        return
                    }
                }

                if (tag == "a") {
                    val raw = node.attr("href")
                    if (raw.isNotBlank() && isVideoLink(raw)) {
                        val normalized = normalizeVideoUrl(raw).orEmpty()
                        if (normalized.isNotBlank()) {
                            out += PostContentBlock(type = PostContentBlock.Type.VIDEO, videoUrl = normalized)
                        }
                    }
                }

                val embeddedVideoUrl = readEmbeddedVideoUrl(node)
                if (embeddedVideoUrl != null) {
                    val hasInlineMedia = node.selectFirst("iframe, video") != null
                    if (!hasInlineMedia) {
                        out += PostContentBlock(type = PostContentBlock.Type.VIDEO, videoUrl = embeddedVideoUrl)
                    }
                }

                if (tag == "source") {
                    return
                }

                if (tag == "img") {
                    if (isSpoilerImage(node)) return
                    val normalized = readImageUrl(node, baseUrl).orEmpty()
                    if (normalized.isBlank()) return
                    out += PostContentBlock(type = PostContentBlock.Type.IMAGE, imageUrl = normalized)
                    return
                }

                node.childNodes().forEach { child ->
                    collectContentBlocks(child, baseUrl, out)
                }

                if (tag in BLOCK_BREAK_TAGS) {
                    out += PostContentBlock(type = PostContentBlock.Type.TEXT, text = "\n\n")
                }
            }
        }
    }

    private fun mergeAdjacentTextBlocks(blocks: List<PostContentBlock>): List<PostContentBlock> {
        if (blocks.isEmpty()) return blocks
        val merged = mutableListOf<PostContentBlock>()
        val textBuffer = StringBuilder()

        fun flushText() {
            var text = textBuffer.toString()
            text = text.replace('\u00A0', ' ')
            text = text.replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            text = text.replace(Regex(" *\\n+ *"), "\n")
            text = text.replace(Regex("\\n{3,}"), "\n\n")
            val hasVisible = text.any { it != ' ' && it != '\n' }
            if (hasVisible) {
                merged += PostContentBlock(type = PostContentBlock.Type.TEXT, text = text)
            }
            textBuffer.clear()
        }

        blocks.forEach { block ->
            if (block.type == PostContentBlock.Type.TEXT) {
                textBuffer.append(block.text)
            } else {
                flushText()
                merged += block
            }
        }
        flushText()
        return merged
    }

    private fun isImageSource(element: Element): Boolean {
        val type = element.attr("type").lowercase().trim()
        if (type.startsWith("image/")) return true
        val raw = element.attr("data-srcset")
            .ifBlank { element.attr("srcset") }
            .ifBlank { element.attr("src") }
        return isLikelyImageUrl(raw)
    }

    private fun isLikelyImageUrl(raw: String): Boolean {
        val cleaned = raw.trim().lowercase()
        if (cleaned.startsWith("data:image/")) return true
        val url = parseSrcsetUrl(cleaned).orEmpty().ifBlank { cleaned }
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".png") ||
            path.endsWith(".gif") ||
            path.endsWith(".webp") ||
            path.endsWith(".bmp") ||
            path.endsWith(".svg")
    }

    private fun isSpoilerImage(img: Element): Boolean {
        val structuralSignals = listOf(
            img.attr("alt"),
            img.attr("title"),
            img.attr("class"),
            img.attr("id"),
            img.parents().joinToString(" ") { it.className() },
            img.parents().joinToString(" ") { it.id() },
            img.parents().joinToString(" ") { it.attr("data-type") },
            img.parents().joinToString(" ") { it.text() }
        ).joinToString(" ").lowercase()

        val urlSignals = listOf(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-original")
        ).joinToString(" ").lowercase()

        return structuralSignals.contains("spoiler") ||
            structuralSignals.contains("spoil") ||
            structuralSignals.contains("스포") ||
            structuralSignals.contains("스포일러") ||
            urlSignals.contains("spoiler") ||
            urlSignals.contains("spoil") ||
            urlSignals.contains("스포")
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
            val writer = readWriter(block)
            val date = readDate(block)
            val text = sanitizeCommentBody(writer, date, readCommentText(block))
            val imageInfo = readCommentImageInfo(block, mobileUrl)
            if (text.isBlank() && imageInfo == null) return@mapNotNull null
            DcComment(
                writer = writer.ifBlank { "익명" },
                text = text,
                dateText = date,
                imageUrl = imageInfo?.url,
                isGif = imageInfo?.isGif == true
            )
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

    private fun sanitizeCommentBody(writer: String, dateText: String, raw: String): String {
        if (raw.isBlank()) return raw
        val normalized = raw.trim()
        if (normalized.isBlank()) return ""

        var result = normalized
        val writerTrimmed = writer.trim()
        if (writerTrimmed.isNotBlank()) {
            val escaped = Regex.escape(writerTrimmed)
            if (result == writerTrimmed) return ""
            val withColon = Regex("^${escaped}\\s*[:：]\\s*")
            result = result.replace(withColon, "").trim()
            result = result.replace(Regex("^${escaped}\\s+"), "").trim()
        }

        val dateTrimmed = dateText.trim()
        if (dateTrimmed.isNotBlank()) {
            val escapedDate = Regex.escape(dateTrimmed)
            result = result.replace(Regex("^${escapedDate}\\s+"), "").trim()
            result = result.replace(Regex("\\s+${escapedDate}$"), "").trim()
        }

        // Remove common timestamp patterns that leak into comment bodies.
        result = result.replace(
            Regex("\\b(20\\d{2}[./-]\\d{1,2}[./-]\\d{1,2})(\\s+\\d{1,2}:\\d{2}(:\\d{2})?)?\\b"),
            ""
        ).trim()
        result = result.replace(
            Regex("\\b\\d{1,2}[./-]\\d{1,2}\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\b"),
            ""
        ).trim()

        return result
    }

    private fun readCommentImageInfo(block: Element, baseUrl: String): CommentImage? {
        val img = block.selectFirst(
            "img.dccon, img[class*=dccon], img[alt*=디시콘], img[data-type*=dccon], img"
        ) ?: return null
        val rawGif = img.attr("data-gif")
        val raw = rawGif
            .ifBlank { img.attr("data-original") }
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("data-srcset") }
            .ifBlank { img.attr("srcset") }
            .ifBlank { img.attr("src") }
        val src = parseSrcsetUrl(raw).orEmpty().ifBlank { raw }
        val normalized = normalizeImageUrl(baseUrl, src).orEmpty()
        if (normalized.isBlank()) return null
        if (normalized.contains("dccon_loading")) return null
        return CommentImage(url = normalized, isGif = rawGif.isNotBlank())
    }

    private data class CommentImage(
        val url: String,
        val isGif: Boolean
    )

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
        return listUrl(galleryId, 1)
    }

    private fun listUrl(galleryId: String, page: Int): String {
        val safePage = if (page < 1) 1 else page
        return "https://gall.dcinside.com/board/lists/?id=$galleryId&page=$safePage"
    }

    private fun mobileListUrl(galleryId: String): String {
        return mobileListUrl(galleryId, 1)
    }

    private fun mobileListUrl(galleryId: String, page: Int): String {
        val safePage = if (page < 1) 1 else page
        return "https://m.dcinside.com/board/$galleryId?page=$safePage"
    }

    private fun mobileConceptListUrl(galleryId: String): String {
        return mobileConceptListUrl(galleryId, 1)
    }

    private fun mobileConceptListUrl(galleryId: String, page: Int): String {
        val safePage = if (page < 1) 1 else page
        return "https://m.dcinside.com/board/$galleryId?recommend=1&page=$safePage"
    }

    companion object {
        private const val TIMEOUT_MS = 10000
        private const val DETAIL_PREVIEW_LIMIT = 4
        private const val PREVIEW_MAX_CHARS = 140
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        private val BLOCK_BREAK_TAGS = setOf(
            "p", "div", "li", "blockquote", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6"
        )
    }

    data class PostPreview(
        val preview: String,
        val imageUrls: List<String>
    )
}
