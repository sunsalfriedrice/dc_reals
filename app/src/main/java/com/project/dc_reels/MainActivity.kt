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
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.project.dc_reels.data.DcRepository
import com.project.dc_reels.data.GalleryStore
import com.project.dc_reels.model.DcPost
import com.project.dc_reels.model.GalleryConfig
import com.project.dc_reels.ui.GalleryDrawerAdapter
import com.project.dc_reels.ui.comments.CommentsActivity
import com.project.dc_reels.ui.detail.ImageViewerActivity
import com.project.dc_reels.ui.detail.PostDetailActivity
import com.project.dc_reels.ui.detail.PostContentAdapter
import com.project.dc_reels.ui.detail.SimpleImagePagerAdapter
import com.project.dc_reels.ui.reels.ReelsPagerAdapter
import com.project.dc_reels.util.GalleryUrlNormalizer
import android.content.Intent
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val repository by lazy { DcRepository() }
    private val galleryStore by lazy { GalleryStore(this) }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var loading: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var viewPager: ViewPager2

    private val galleries = mutableListOf<GalleryConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        loading = findViewById(R.id.mainLoading)
        emptyText = findViewById(R.id.emptyPostsText)
        viewPager = findViewById(R.id.reelsViewPager)

        val toolbar = findViewById<MaterialToolbar>(R.id.mainToolbar)
        val addButton = findViewById<Button>(R.id.addGalleryButton)
        val galleryRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.galleryRecyclerView)

        val reelAdapter = ReelsPagerAdapter(
            onOpenComments = { post ->
                val intent = Intent(this, CommentsActivity::class.java).apply {
                    putExtra(CommentsActivity.EXTRA_POST_URL, post.url)
                    putExtra(CommentsActivity.EXTRA_POST_TITLE, post.title)
                }
                startActivity(intent)
            },
            onOpenDetail = { post ->
                showDetailBottomSheet(post)
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
            }
        )
        viewPager.adapter = reelAdapter

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
        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.fetchConceptPosts(galleryId) }
            }
            loading.visibility = View.GONE

            val posts = result.getOrDefault(emptyList())
            reelAdapter.submitList(posts)
            emptyText.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE

            if (result.isFailure) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.post_load_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } else if (posts.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.post_empty_for_gallery),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showAddGalleryDialog(onSubmit: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_gallery, null)
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

    private fun showDetailBottomSheet(post: DcPost) {
        val content = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_post_detail, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(content)

        val loadingView = content.findViewById<ProgressBar>(R.id.detailSheetLoading)
        val titleView = content.findViewById<TextView>(R.id.detailSheetTitle)
        val contentRecycler = content.findViewById<RecyclerView>(R.id.detailSheetContentRecycler)
        val openComments = content.findViewById<Button>(R.id.detailSheetCommentsButton)
        val contentAdapter = PostContentAdapter { clickedUrl ->
            val allImageUrls = contentAdapterCurrentImageUrls
            val index = allImageUrls.indexOf(clickedUrl).takeIf { it >= 0 } ?: 0
            openImageViewer(allImageUrls, index)
        }
        contentRecycler.layoutManager = LinearLayoutManager(this)
        contentRecycler.adapter = contentAdapter

        var contentAdapterCurrentImageUrls: List<String> = emptyList()

        titleView.text = post.title
        contentAdapter.submitList(emptyList())

        openComments.setOnClickListener {
            val intent = Intent(this, CommentsActivity::class.java).apply {
                putExtra(CommentsActivity.EXTRA_POST_URL, post.url)
                putExtra(CommentsActivity.EXTRA_POST_TITLE, post.title)
            }
            startActivity(intent)
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = false
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            }
        }
        dialog.show()

        lifecycleScope.launch {
            loadingView.visibility = View.VISIBLE
            val detail = withContext(Dispatchers.IO) {
                runCatching { repository.fetchPostDetail(post.url) }.getOrNull()
            }
            loadingView.visibility = View.GONE
            if (detail == null) {
                contentAdapter.submitList(emptyList())
                Toast.makeText(this@MainActivity, getString(R.string.post_load_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (detail.title.isNotBlank()) {
                titleView.text = detail.title
            }

            val blocks = if (detail.contentBlocks.isNotEmpty()) {
                detail.contentBlocks
            } else {
                listOf(com.project.dc_reels.model.PostContentBlock(type = com.project.dc_reels.model.PostContentBlock.Type.TEXT, text = detail.bodyText.ifBlank { post.preview }))
            }
            contentAdapterCurrentImageUrls = blocks.mapNotNull { it.imageUrl }.distinct()
            contentAdapter.submitList(blocks)
        }
    }

    private fun openImageViewerFromPost(post: DcPost) {
        val cached = post.imageUrls
        if (cached.isNotEmpty()) {
            openImageViewer(cached, 0)
            return
        }

        lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            val detail = withContext(Dispatchers.IO) {
                runCatching { repository.fetchPostDetail(post.url) }.getOrNull()
            }
            loading.visibility = View.GONE

            val images = detail?.imageUrls.orEmpty()
            if (images.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.no_image), Toast.LENGTH_SHORT).show()
                return@launch
            }
            openImageViewer(images, 0)
        }
    }

    private fun openImageViewer(imageUrls: List<String>, startIndex: Int) {
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGE_URLS, ArrayList(imageUrls))
            putExtra(ImageViewerActivity.EXTRA_START_INDEX, startIndex)
        }
        startActivity(intent)
    }
}