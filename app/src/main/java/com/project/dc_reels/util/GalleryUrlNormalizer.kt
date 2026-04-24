package com.project.dc_reels.util

import java.net.URI

object GalleryUrlNormalizer {

    fun normalizeGalleryIdFromUrl(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty())

        val candidate = toHttpUrl(trimmed)
        val uri = URI(candidate)
        val host = uri.host?.lowercase().orEmpty()

        val queryMap = parseQuery(uri.query)
        val idFromQuery = queryMap["id"]
        if (!idFromQuery.isNullOrBlank()) {
            return idFromQuery
        }

        // Mobile gallery URL formats like /board/{id} or /mgallery/board/{id}
        if (host == "m.dcinside.com" || host.endsWith(".dcinside.com")) {
            val segments = uri.path
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }
            val boardIndex = segments.indexOf("board")
            if (boardIndex >= 0 && segments.size > boardIndex + 1) {
                return segments[boardIndex + 1]
            }
        }

        throw IllegalArgumentException("invalid gallery link")
    }

    private fun toHttpUrl(raw: String): String {
        return when {
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            raw.contains("dcinside.com", ignoreCase = true) -> "https://$raw"
            else -> throw IllegalArgumentException("invalid gallery link")
        }
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query
            .split('&')
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
            }
            .toMap()
    }
}

