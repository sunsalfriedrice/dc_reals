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

    private var imageUrls: List<String> = emptyList()

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
            val allImageUrls = imageUrls
            val index = allImageUrls.indexOf(clickedUrl).takeIf { it >= 0 } ?: 0
            openImageViewer(allImageUrls, index)
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
            imageUrls = blocks.mapNotNull { it.imageUrl }.distinct()
            contentAdapter.submitList(blocks)
        }
    }

    private fun openImageViewer(imageUrls: List<String>, startIndex: Int) {
        if (imageUrls.isEmpty()) return
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URLS, ArrayList(imageUrls))
            putExtra(ImageViewerActivity.EXTRA_START_INDEX, startIndex)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_POST_URL = "extra_post_url"
        const val EXTRA_POST_TITLE = "extra_post_title"
    }
}
