package com.project.dc_reels.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.project.dc_reels.R

class MediaViewerPagerAdapter(
    private val items: List<ViewerMediaItem>,
    private val refererUrl: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // LRU 캐시: 현재 글의 이미지 갯수를 최대 크기로 설정 (Position 기반)
    private val zoomStates = object : LinkedHashMap<Int, Float>(items.size + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, Float>?): Boolean {
            return size > items.size
        }
    }

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
            is ImageViewHolder -> holder.bind(position, item.url)
            is VideoViewHolder -> holder.bind(item.url)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is VideoViewHolder) {
            holder.recycle()
        } else if (holder is ImageViewHolder) {
            holder.saveZoomState()
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    private inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<PhotoView>(R.id.viewerImageView)
        private var currentPosition: Int = -1

        fun bind(position: Int, url: String) {
            currentPosition = position

            // PhotoView 확대 설정 (최대 16배까지 확대 가능하도록 개선)
            imageView.maximumScale = 16.0f
            imageView.mediumScale = 5.0f
            
            // 먼저 줌 상태를 초기값으로 리셋
            imageView.setScale(1.0f, false)
            
            // 저장된 줌 상태가 있으면 복원, 없으면 1.0f로 초기화
            val savedZoom = zoomStates[position] ?: 1.0f

            DcImageLoader.loadImage(
                imageView = imageView,
                imageUrl = url,
                refererCandidates = DcImageLoader.refererCandidates(refererUrl),
                placeholderRes = R.drawable.ic_launcher_foreground,
                errorRes = R.drawable.ic_launcher_foreground,
                isFullSize = true,
                onImageLoaded = {
                    // 이미지 로드 완료 후 저장된 줌 상태 복원
                    if (savedZoom != 1.0f) {
                        imageView.setScale(savedZoom, false)
                    }
                }
            )
        }

        fun saveZoomState() {
            if (currentPosition != -1) {
                zoomStates[currentPosition] = imageView.scale
            }
        }
    }

    private inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val webView = itemView.findViewById<WebView>(R.id.viewerVideoWebView)

        @SuppressLint("SetJavaScriptEnabled")
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

