package com.project.dc_reels.ui.detail

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

object DcImageLoader {
    fun refererCandidates(postUrl: String): List<String> {
        val candidates = linkedSetOf<String>()
        val normalized = postUrl.trim()
        if (normalized.isNotBlank()) {
            candidates += normalized
        }

        val mobileMatch = MOBILE_POST_URL_REGEX.find(normalized)
        if (mobileMatch != null) {
            val galleryId = mobileMatch.groupValues[1]
            val no = mobileMatch.groupValues[2]
            candidates += "https://gall.dcinside.com/mgallery/board/view/?id=$galleryId&no=$no"
            candidates += "https://gall.dcinside.com/board/view/?id=$galleryId&no=$no"
        }

        candidates += DEFAULT_REFERER
        return candidates.toList()
    }

    fun loadImage(
        imageView: ImageView,
        imageUrl: String,
        refererCandidates: List<String>,
        placeholderRes: Int,
        errorRes: Int,
        isFullSize: Boolean = false,
        onImageLoaded: (() -> Unit)? = null
    ) {
        val candidates = refererCandidates.ifEmpty { listOf(DEFAULT_REFERER) }
        loadWithReferer(imageView, imageUrl, candidates, 0, placeholderRes, errorRes, isFullSize, onImageLoaded)
    }

    private fun loadWithReferer(
        imageView: ImageView,
        imageUrl: String,
        referers: List<String>,
        index: Int,
        placeholderRes: Int,
        errorRes: Int,
        isFullSize: Boolean = false,
        onImageLoaded: (() -> Unit)? = null
    ) {
        if (index >= referers.size) {
            Glide.with(imageView)
                .load(errorRes)
                .into(imageView)
            return
        }

        val glideUrl = GlideUrl(
            imageUrl,
            LazyHeaders.Builder()
                .addHeader(HEADER_USER_AGENT, USER_AGENT)
                .addHeader(HEADER_REFERER, referers[index])
                .addHeader(HEADER_ACCEPT, ACCEPT_IMAGE)
                .build()
        )

        val builder = Glide.with(imageView)
            .load(glideUrl)
            .placeholder(placeholderRes)
            .error(errorRes)

        // 본문 이미지 vs 전체보기 이미지에 따라 다르게 처리
        val displayWidth = imageView.context.resources.displayMetrics.widthPixels
        if (isFullSize) {
            // 전체 이미지 뷰어: 고해상도 유지
            // 화면 너비의 2배까지 허용해서 확대에 대비
            builder.override(displayWidth * 2, Target.SIZE_ORIGINAL)
                .downsample(DownsampleStrategy.AT_MOST)
        } else {
            // 본문 이미지: 화면 너비에 맞추되 높이는 원본 비율 유지
            // 극단적인 세로 비율의 이미지도 화질 열화 없이 표시
            builder.override(displayWidth, Target.SIZE_ORIGINAL)
                .downsample(DownsampleStrategy.AT_MOST)
        }

        builder.listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    val nextIndex = index + 1
                    if (nextIndex < referers.size) {
                        imageView.post {
                            loadWithReferer(imageView, imageUrl, referers, nextIndex, placeholderRes, errorRes, isFullSize, onImageLoaded)
                        }
                        return true
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    onImageLoaded?.invoke()
                    return false
                }
            })
            .into(imageView)
    }

    private const val DEFAULT_REFERER = "https://m.dcinside.com/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    private const val HEADER_USER_AGENT = "User-Agent"
    private const val HEADER_REFERER = "Referer"
    private const val HEADER_ACCEPT = "Accept"
    private const val ACCEPT_IMAGE = "image/webp,image/*;q=0.8,*/*;q=0.5"
    private val MOBILE_POST_URL_REGEX = Regex("https?://m\\.dcinside\\.com/board/([^/?#]+)/([0-9]+)")
}

