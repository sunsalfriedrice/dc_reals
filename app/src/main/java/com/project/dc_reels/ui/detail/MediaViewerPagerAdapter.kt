package com.project.dc_reels.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.project.dc_reels.R

class MediaViewerPagerAdapter(
    private val items: List<ViewerMediaItem>,
    private val refererUrl: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            ViewerMediaItem.Type.VIDEO -> TYPE_VIDEO
            ViewerMediaItem.Type.IMAGE -> TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_VIDEO -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_viewer_video, parent, false)
                VideoViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_viewer_image, parent, false)
                ImageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ImageViewHolder -> holder.bind(item.url)
            is VideoViewHolder -> holder.bind(item.url)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is VideoViewHolder) {
            holder.recycle()
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    private inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<ImageView>(R.id.viewerImageView)

        fun bind(url: String) {
            val glideUrl = GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader(HEADER_USER_AGENT, USER_AGENT)
                    .addHeader(HEADER_REFERER, DEFAULT_REFERER)
                    .addHeader(HEADER_ACCEPT, ACCEPT_IMAGE)
                    .build()
            )
            Glide.with(imageView)
                .load(glideUrl)
                .placeholder(R.drawable.ic_launcher_foreground)
                .error(R.drawable.ic_launcher_foreground)
                .into(imageView)
        }
    }

    private inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val webView = itemView.findViewById<WebView>(R.id.viewerVideoWebView)

        fun bind(url: String) {
            if (url.isBlank()) return
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.loadWithOverviewMode = true
            webView.settings.useWideViewPort = true
            webView.settings.userAgentString = USER_AGENT
            webView.webViewClient = WebViewClient()

            if (url.endsWith(".mp4", true) || url.endsWith(".webm", true) || url.endsWith(".m3u8", true)) {
                val html = """
                    <html><body style=\"margin:0;background:#000;\">
                    <video controls style=\"width:100%;height:100%;\" src=\"$url\"></video>
                    </body></html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } else {
                val headers = mapOf(
                    HEADER_USER_AGENT to USER_AGENT,
                    HEADER_REFERER to (if (refererUrl.isNotBlank()) refererUrl else DEFAULT_REFERER)
                )
                webView.loadUrl(url, headers)
            }
        }

        fun recycle() {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
        }
    }

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        private const val DEFAULT_REFERER = "https://m.dcinside.com/"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_REFERER = "Referer"
        private const val HEADER_ACCEPT = "Accept"
        private const val ACCEPT_IMAGE = "image/webp,image/*;q=0.8,*/*;q=0.5"
    }
}

data class ViewerMediaItem(
    val type: Type,
    val url: String
) {
    enum class Type {
        IMAGE,
        VIDEO
    }
}

