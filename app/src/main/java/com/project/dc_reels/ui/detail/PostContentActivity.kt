package com.project.dc_reels.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.project.dc_reels.R
import com.project.dc_reels.data.DcRepository
import com.project.dc_reels.model.PostContentBlock
import com.project.dc_reels.ui.comments.CommentsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PostContentActivity : AppCompatActivity() {
    private val repository by lazy { DcRepository() }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var loading: ProgressBar
    private lateinit var recycler: RecyclerView
    private lateinit var commentsButton: Button

    private var viewerItems: List<ViewerMediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_content)

        val root = findViewById<View>(R.id.postContentRoot)
        toolbar = findViewById(R.id.postContentToolbar)
        loading = findViewById(R.id.postContentLoading)
        recycler = findViewById(R.id.postContentRecycler)
        commentsButton = findViewById(R.id.postContentCommentsButton)

        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        toolbar.setNavigationContentDescription(R.string.close)
        toolbar.setNavigationOnClickListener { finish() }

        val initialTitle = intent.getStringExtra(EXTRA_POST_TITLE).orEmpty()
        toolbar.title = if (initialTitle.isNotBlank()) initialTitle else getString(R.string.post_detail)

        val url = intent.getStringExtra(EXTRA_POST_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }

        val contentAdapter = PostContentAdapter(url) { clickedUrl ->
            val index = viewerItems.indexOfFirst {
                it.type == ViewerMediaItem.Type.IMAGE && it.url == clickedUrl
            }.takeIf { it >= 0 } ?: 0
            openImageViewer(viewerItems, index, url)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = contentAdapter

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, view.paddingTop, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        commentsButton.setOnClickListener {
            val intent = Intent(this, CommentsActivity::class.java).apply {
                putExtra(CommentsActivity.EXTRA_POST_URL, url)
                putExtra(CommentsActivity.EXTRA_POST_TITLE, initialTitle)
            }
            startActivity(intent)
        }

        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            val detail = withContext(Dispatchers.IO) {
                runCatching { repository.fetchPostDetail(url) }.getOrNull()
            }
            loading.visibility = View.GONE

            if (detail == null) {
                contentAdapter.submitList(emptyList())
                Toast.makeText(this@PostContentActivity, getString(R.string.post_load_failed), Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }

            if (detail.title.isNotBlank()) {
                toolbar.title = detail.title
            }

            val blocks = if (detail.contentBlocks.isNotEmpty()) {
                detail.contentBlocks
            } else {
                listOf(
                    PostContentBlock(
                        type = PostContentBlock.Type.TEXT,
                        text = detail.bodyText.ifBlank { initialTitle }
                    )
                )
            }
            viewerItems = blocks.mapNotNull { block ->
                when (block.type) {
                    PostContentBlock.Type.IMAGE -> block.imageUrl?.takeIf { it.isNotBlank() }
                        ?.let { ViewerMediaItem(ViewerMediaItem.Type.IMAGE, it) }
                    PostContentBlock.Type.VIDEO -> block.videoUrl?.takeIf { it.isNotBlank() }
                        ?.let { ViewerMediaItem(ViewerMediaItem.Type.VIDEO, it) }
                    else -> null
                }
            }
            contentAdapter.submitList(blocks)
        }
    }

    private fun openImageViewer(mediaItems: List<ViewerMediaItem>, startIndex: Int, refererUrl: String) {
        if (mediaItems.isEmpty()) return
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putStringArrayListExtra(
                ImageViewerActivity.EXTRA_MEDIA_URLS,
                ArrayList(mediaItems.map { it.url })
            )
            putStringArrayListExtra(
                ImageViewerActivity.EXTRA_MEDIA_TYPES,
                ArrayList(mediaItems.map { it.type.name.lowercase() })
            )
            putExtra(ImageViewerActivity.EXTRA_REFERER_URL, refererUrl)
            putExtra(ImageViewerActivity.EXTRA_START_INDEX, startIndex)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_POST_URL = "extra_post_url"
        const val EXTRA_POST_TITLE = "extra_post_title"
    }
}
