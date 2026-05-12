package com.project.dc_reels.ui.detail

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.project.dc_reels.R

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var indexText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        pager = findViewById(R.id.imageViewerPager)
        indexText = findViewById(R.id.imageIndexText)
        findViewById<ImageButton>(R.id.closeViewerButton).setOnClickListener { finish() }

        val legacyUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS).orEmpty()
        val mediaUrls = intent.getStringArrayListExtra(EXTRA_MEDIA_URLS)
        val mediaTypes = intent.getStringArrayListExtra(EXTRA_MEDIA_TYPES)
        val refererUrl = intent.getStringExtra(EXTRA_REFERER_URL).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0)

        val mediaItems = if (!mediaUrls.isNullOrEmpty() && !mediaTypes.isNullOrEmpty() &&
            mediaUrls.size == mediaTypes.size
        ) {
            mediaUrls.mapIndexed { index, url ->
                val type = if (mediaTypes[index].equals(TYPE_VIDEO, true)) {
                    ViewerMediaItem.Type.VIDEO
                } else {
                    ViewerMediaItem.Type.IMAGE
                }
                ViewerMediaItem(type = type, url = url)
            }
        } else {
            legacyUrls.map { url -> ViewerMediaItem(type = ViewerMediaItem.Type.IMAGE, url = url) }
        }

        if (mediaItems.isEmpty()) {
            finish()
            return
        }

        pager.adapter = MediaViewerPagerAdapter(
            items = mediaItems,
            refererUrl = refererUrl
        )
        pager.setCurrentItem(startIndex.coerceAtMost(mediaItems.lastIndex), false)
        updateIndex(pager.currentItem, mediaItems.size)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndex(position, mediaItems.size)
            }
        })
    }

    private fun updateIndex(position: Int, total: Int) {
        indexText.text = getString(R.string.image_index_format, position + 1, total)
    }

    companion object {
        const val EXTRA_IMAGE_URLS = "extra_image_urls"
        const val EXTRA_MEDIA_URLS = "extra_media_urls"
        const val EXTRA_MEDIA_TYPES = "extra_media_types"
        const val EXTRA_REFERER_URL = "extra_referer_url"
        const val EXTRA_START_INDEX = "extra_start_index"
        private const val TYPE_VIDEO = "video"
    }
}
