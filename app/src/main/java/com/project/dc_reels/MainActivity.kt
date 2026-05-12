package com.project.dc_reels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.project.dc_reels.data.DcRepository
import com.project.dc_reels.data.GalleryStore
import com.project.dc_reels.model.DcPost
import com.project.dc_reels.model.GalleryConfig
import com.project.dc_reels.model.PostContentBlock
import com.project.dc_reels.ui.GalleryDrawerAdapter
import com.project.dc_reels.ui.comments.CommentsActivity
import com.project.dc_reels.ui.detail.ImageViewerActivity
import com.project.dc_reels.ui.detail.PostContentActivity
import com.project.dc_reels.ui.detail.PostDetailActivity
import com.project.dc_reels.ui.detail.ViewerMediaItem
import com.project.dc_reels.ui.reels.ReelsPagerAdapter
import com.project.dc_reels.util.GalleryUrlNormalizer
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val repository by lazy { DcRepository() }
    private val galleryStore by lazy { GalleryStore(this) }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var loading: ProgressBar
    private lateinit var pagingLoading: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var mainContent: View
    private lateinit var reelAdapter: ReelsPagerAdapter

    private val galleries = mutableListOf<GalleryConfig>()
    private var currentGalleryId: String? = null
    private var currentPage = 1
    private var isLoadingPage = false
    private var hasMorePages = true
    private var lastPageUrls: Set<String> = emptySet()
    private var consecutiveLoadFailures = 0
    private var repeatPageCount = 0

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            maybeLoadNextPage(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        loading = findViewById(R.id.mainLoading)
        pagingLoading = findViewById(R.id.pagingLoading)
        emptyText = findViewById(R.id.emptyPostsText)
        viewPager = findViewById(R.id.reelsViewPager)
        mainContent = findViewById(R.id.mainContent)

        val toolbar = findViewById<MaterialToolbar>(R.id.mainToolbar)
        val addButton = findViewById<Button>(R.id.addGalleryButton)
        val galleryRecycler = findViewById<RecyclerView>(R.id.galleryRecyclerView)

        reelAdapter = ReelsPagerAdapter(
            onOpenComments = { post ->
                val intent = Intent(this, CommentsActivity::class.java).apply {
                    putExtra(CommentsActivity.EXTRA_POST_URL, post.url)
                    putExtra(CommentsActivity.EXTRA_POST_TITLE, post.title)
                }
                startActivity(intent)
            },
            onOpenDetail = { post ->
                val intent = Intent(this, PostContentActivity::class.java).apply {
                    putExtra(PostContentActivity.EXTRA_POST_URL, post.url)
                    putExtra(PostContentActivity.EXTRA_POST_TITLE, post.title)
                }
                startActivity(intent)
            },
            onOpenImages = { post ->
                openImageViewerFromPost(post)
            },
            onOpenOriginal = { post ->
                val intent = Intent(this, PostDetailActivity::class.java).apply {
                    putExtra(PostDetailActivity.EXTRA_POST_URL, post.url)
                    putExtra(PostDetailActivity.EXTRA_POST_TITLE, post.title)
                }
                startActivity(intent)
            },
            onRequestPreview = { post, onResult ->
                lifecycleScope.launch {
                    val preview = withContext(Dispatchers.IO) {
                        runCatching { repository.fetchPostPreview(post.url) }.getOrNull()
                    }
                    if (preview != null) {
                        onResult(
                            post.copy(
                                preview = preview.preview.ifBlank { post.title },
                                imageUrl = preview.imageUrls.firstOrNull(),
                                imageUrls = preview.imageUrls
                            )
                        )
                    }
                }
            }
        )
        viewPager.adapter = reelAdapter
        viewPager.offscreenPageLimit = 4
        viewPager.registerOnPageChangeCallback(pageChangeCallback)

        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, view.paddingTop, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(mainContent)

        lateinit var drawerAdapter: GalleryDrawerAdapter
        drawerAdapter = GalleryDrawerAdapter(
            onSelect = { gallery ->
                drawerLayout.closeDrawers()
                loadPosts(gallery.id, reelAdapter)
            },
            onRename = { gallery ->
                showRenameGalleryDialog(gallery) { updated ->
                    val index = galleries.indexOfFirst { it.id == updated.id }
                    if (index >= 0) {
                        galleries[index] = updated
                        galleryStore.saveAll(galleries)
                        drawerAdapter.submitList(galleries)
                    }
                }
            },
            onDelete = { gallery ->
                val index = galleries.indexOfFirst { it.id == gallery.id }
                if (index < 0) return@GalleryDrawerAdapter
                galleries.removeAt(index)
                drawerAdapter.submitList(galleries)
                galleryStore.saveAll(galleries)
                Snackbar.make(drawerLayout, getString(R.string.gallery_deleted), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.undo)) {
                        galleries.add(index, gallery)
                        drawerAdapter.submitList(galleries)
                        galleryStore.saveAll(galleries)
                    }
                    .show()
            }
        )
        galleryRecycler.layoutManager = LinearLayoutManager(this)
        galleryRecycler.adapter = drawerAdapter

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        addButton.setOnClickListener {
            showAddGalleryDialog {
                addGalleryFromLink(it, drawerAdapter, reelAdapter)
            }
        }

        galleries.clear()
        galleries.addAll(galleryStore.loadAll())
        drawerAdapter.submitList(galleries)

        if (galleries.isNotEmpty()) {
            loadPosts(galleries.first().id, reelAdapter)
        } else {
            emptyText.visibility = View.VISIBLE
            drawerLayout.post { drawerLayout.openDrawer(GravityCompat.START) }
        }
    }

    override fun onDestroy() {
        viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    private fun addGalleryFromLink(
        link: String,
        drawerAdapter: GalleryDrawerAdapter,
        reelAdapter: ReelsPagerAdapter
    ) {
        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val finalUrl = repository.resolveFinalUrl(link)
                    val galleryId = GalleryUrlNormalizer.normalizeGalleryIdFromUrl(finalUrl)
                    val name = runCatching { repository.fetchGalleryName(galleryId) }.getOrElse { galleryId }
                    GalleryConfig(galleryId, name)
                }
            }
            loading.visibility = View.GONE

            result.onSuccess { gallery ->
                val index = galleries.indexOfFirst { it.id == gallery.id }
                if (index >= 0) {
                    galleries[index] = gallery
                } else {
                    galleries.add(gallery)
                }
                galleryStore.saveAll(galleries)
                drawerAdapter.submitList(galleries)
                loadPosts(gallery.id, reelAdapter)
                drawerLayout.closeDrawers()
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.invalid_gallery_link_message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadPosts(galleryId: String, reelAdapter: ReelsPagerAdapter) {
        currentGalleryId = galleryId
        currentPage = 1
        hasMorePages = true
        lastPageUrls = emptySet()
        consecutiveLoadFailures = 0
        repeatPageCount = 0
        loadPostsPage(galleryId, 1, reelAdapter, reset = true)
    }

    private fun maybeLoadNextPage(position: Int) {
        val total = reelAdapter.itemCount
        if (!hasMorePages || isLoadingPage || total == 0) return
        if (position >= total - PREFETCH_THRESHOLD) {
            loadNextPage()
        }
    }

    private fun loadNextPage() {
        val galleryId = currentGalleryId ?: return
        loadPostsPage(galleryId, currentPage + 1, reelAdapter, reset = false)
    }

    private fun loadPostsPage(
        galleryId: String,
        page: Int,
        reelAdapter: ReelsPagerAdapter,
        reset: Boolean
    ) {
        if (isLoadingPage) return
        isLoadingPage = true

        lifecycleScope.launch {
            if (reset) {
                reelAdapter.submitList(emptyList())
                emptyText.visibility = View.GONE
                viewPager.setCurrentItem(0, false)
                loading.visibility = View.VISIBLE
                pagingLoading.visibility = View.GONE
            } else {
                pagingLoading.visibility = View.VISIBLE
            }

            val result = withContext(Dispatchers.IO) {
                runCatching { repository.fetchConceptPosts(galleryId, page) }
            }

            if (reset) {
                loading.visibility = View.GONE
            }
            pagingLoading.visibility = View.GONE
            isLoadingPage = false

            val posts = result.getOrDefault(emptyList())
            val addedCount = if (reset) {
                reelAdapter.submitList(posts)
                posts.size
            } else {
                reelAdapter.appendList(posts)
            }

            if (result.isFailure) {
                consecutiveLoadFailures += 1
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.post_load_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (consecutiveLoadFailures >= 2 && !reset) {
                    hasMorePages = false
                }
                if (reset) {
                    emptyText.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
                }
                return@launch
            }

            consecutiveLoadFailures = 0
            val pageUrls = posts.mapNotNull { it.url.ifBlank { null } }.toSet()
            val isRepeatPage = pageUrls.isNotEmpty() && pageUrls == lastPageUrls
            val isPotentialEnd = posts.isEmpty() || addedCount == 0 || isRepeatPage

            if (isPotentialEnd) {
                repeatPageCount += 1
                if (repeatPageCount >= 2) {
                    hasMorePages = false
                }
            } else {
                repeatPageCount = 0
                currentPage = page
                lastPageUrls = pageUrls
            }

            if (reset) {
                emptyText.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
                if (posts.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.post_empty_for_gallery),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showAddGalleryDialog(onSubmit: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_gallery, null, false)
        val input = view.findViewById<EditText>(R.id.galleryLinkInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_gallery)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _
                -> onSubmit(input.text?.toString().orEmpty()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameGalleryDialog(
        gallery: GalleryConfig,
        onSubmit: (GalleryConfig) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(gallery.displayName)
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_gallery_name)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty().ifBlank { gallery.id }
                onSubmit(gallery.copy(displayName = newName))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun openImageViewerFromPost(post: DcPost) {
        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            val detail = withContext(Dispatchers.IO) {
                runCatching { repository.fetchPostDetail(post.url) }.getOrNull()
            }
            loading.visibility = View.GONE

            val mediaItems = detail?.contentBlocks
                ?.mapNotNull { block ->
                    when (block.type) {
                        PostContentBlock.Type.IMAGE -> block.imageUrl?.takeIf { it.isNotBlank() }
                            ?.let { ViewerMediaItem(ViewerMediaItem.Type.IMAGE, it) }
                        PostContentBlock.Type.VIDEO -> block.videoUrl?.takeIf { it.isNotBlank() }
                            ?.let { ViewerMediaItem(ViewerMediaItem.Type.VIDEO, it) }
                        else -> null
                    }
                }
                .orEmpty()

            if (mediaItems.isNotEmpty()) {
                openImageViewer(mediaItems, 0, post.url)
                return@launch
            }

            val images = post.imageUrls
            if (images.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.no_image), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val fallbackItems = images.map { ViewerMediaItem(ViewerMediaItem.Type.IMAGE, it) }
            openImageViewer(fallbackItems, 0, post.url)
        }
    }

    private fun openImageViewer(mediaItems: List<ViewerMediaItem>, startIndex: Int, refererUrl: String) {
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
        private const val PREFETCH_THRESHOLD = 2
    }
}