package com.project.dc_reels.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.project.dc_reels.R
import com.project.dc_reels.model.PostContentBlock

class PostContentAdapter(
    private val refererUrl: String,
    private val onImageClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<PostContentBlock>()

    fun submitList(blocks: List<PostContentBlock>) {
        items.clear()
        items.addAll(blocks)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            PostContentBlock.Type.IMAGE -> TYPE_IMAGE
            PostContentBlock.Type.VIDEO -> TYPE_VIDEO
            else -> TYPE_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_IMAGE -> {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_post_content_image, parent, false)
            ImageViewHolder(view)
            }
            TYPE_VIDEO -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_post_content_video, parent, false)
                VideoViewHolder(view)
            }
            else -> {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_post_content_text, parent, false)
            TextViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is TextViewHolder -> holder.bind(item)
            is ImageViewHolder -> holder.bind(item)
            is VideoViewHolder -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private inner class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView = itemView.findViewById<TextView>(R.id.contentText)

        fun bind(item: PostContentBlock) {
            textView.text = item.text
        }
    }

    private inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<ImageView>(R.id.contentImage)

        fun bind(item: PostContentBlock) {
            val url = item.imageUrl.orEmpty()
            val isDccon = isDcconUrl(url)
            applyImageSize(imageView, isDccon)

            val glideUrl = GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader(HEADER_USER_AGENT, USER_AGENT)
                    .addHeader(HEADER_REFERER, REFERER)
                    .addHeader(HEADER_ACCEPT, ACCEPT_IMAGE)
                    .build()
            )
            Glide.with(imageView)
                .load(glideUrl)
                .placeholder(R.drawable.ic_launcher_foreground)
                .error(R.drawable.ic_launcher_foreground)
                .into(imageView)

            imageView.setOnClickListener {
                if (url.isNotBlank()) onImageClick(url)
            }
        }
    }

    private fun applyImageSize(imageView: ImageView, isDccon: Boolean) {
        val density = imageView.resources.displayMetrics.density
        val sizePx = (80f * density).toInt()
        val params = imageView.layoutParams
        if (isDccon) {
            params.width = sizePx
            params.height = sizePx
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        imageView.layoutParams = params
    }

    private fun isDcconUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("dccon.php") || normalized.contains("/dccon/")
    }

    private inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val webView = itemView.findViewById<WebView>(R.id.contentVideoWebView)

        fun bind(item: PostContentBlock) {
            val url = item.videoUrl.orEmpty()
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
                    <html><body style="margin:0;background:#000;">
                    <video controls style="width:100%;height:100%;" src="$url"></video>
                    </body></html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } else {
                val headers = mapOf(
                    HEADER_USER_AGENT to USER_AGENT,
                    HEADER_REFERER to refererUrl
                )
                webView.loadUrl(url, headers)
            }
        }
    }

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_IMAGE = 1
        private const val TYPE_VIDEO = 2
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        private const val REFERER = "https://m.dcinside.com/"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_REFERER = "Referer"
        private const val HEADER_ACCEPT = "Accept"
        private const val ACCEPT_IMAGE = "image/webp,image/*;q=0.8,*/*;q=0.5"
    }
}
