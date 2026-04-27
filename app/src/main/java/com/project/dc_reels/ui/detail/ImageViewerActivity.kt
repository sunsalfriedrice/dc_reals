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

        val urls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0)
        if (urls.isEmpty()) {
            finish()
            return
        }

        pager.adapter = SimpleImagePagerAdapter(
            imageUrls = urls,
            onTap = { },
            useCenterCrop = false
        )
        pager.setCurrentItem(startIndex.coerceAtMost(urls.lastIndex), false)
        updateIndex(pager.currentItem, urls.size)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndex(position, urls.size)
            }
        })
    }

    private fun updateIndex(position: Int, total: Int) {
        indexText.text = getString(R.string.image_index_format, position + 1, total)
    }

    companion object {
        const val EXTRA_IMAGE_URLS = "extra_image_urls"
        const val EXTRA_START_INDEX = "extra_start_index"
    }
}

