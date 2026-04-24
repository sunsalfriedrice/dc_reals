package com.project.dc_reels.parser

import org.jsoup.nodes.Document

object GalleryMetaParser {
    fun extractGalleryNameOrId(document: Document, fallbackId: String): String {
        val nameFromMeta = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
        if (nameFromMeta.isNotBlank()) {
            return cleanupName(nameFromMeta)
        }

        val nameFromTitle = document.title().trim()
        if (nameFromTitle.isNotBlank()) {
            return cleanupName(nameFromTitle)
        }

        return fallbackId
    }

    private fun cleanupName(raw: String): String {
        return raw
            .replace(" - dc official App", "")
            .replace(" - DC Inside", "", ignoreCase = true)
            .trim()
            .ifBlank { raw }
    }
}

