package com.project.dc_reels.ui.comments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.project.dc_reels.R
import com.project.dc_reels.data.CommentPagingSource
import com.project.dc_reels.data.DcRepository
import com.project.dc_reels.model.CommentSortOrder
import com.project.dc_reels.model.DcComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommentsActivity : AppCompatActivity() {
    private val repository = DcRepository()

    private lateinit var titleText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loadMoreButton: Button
    private lateinit var toggleGroup: MaterialButtonToggleGroup

    private val allComments = mutableListOf<DcComment>()
    private val shownComments = mutableListOf<DcComment>()
    private var sortOrder = CommentSortOrder.NEWEST
    private var nextPage = 1
    private var hasMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comments)

        titleText = findViewById(R.id.commentsTitle)
        spinner = findViewById(R.id.commentsLoading)
        recyclerView = findViewById(R.id.commentsRecyclerView)
        emptyText = findViewById(R.id.commentsEmptyText)
        loadMoreButton = findViewById(R.id.loadMoreCommentsButton)
        toggleGroup = findViewById(R.id.sortToggleGroup)

        recyclerView.layoutManager = LinearLayoutManager(this)
        val adapter = CommentsAdapter(shownComments)
        recyclerView.adapter = adapter

        val postTitle = intent.getStringExtra(EXTRA_POST_TITLE).orEmpty()
        val postUrl = intent.getStringExtra(EXTRA_POST_URL).orEmpty()
        titleText.text = postTitle

        if (postUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.comment_load_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadMoreButton.setOnClickListener {
            appendPage(adapter)
        }

        toggleGroup.check(R.id.sortNewestButton)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sortOrder = if (checkedId == R.id.sortNewestButton) {
                CommentSortOrder.NEWEST
            } else {
                CommentSortOrder.OLDEST
            }
            rebuildFromFirstPage(adapter)
        }

        lifecycleScope.launch {
            showLoading(true)
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.fetchAllComments(postUrl) }
            }
            val loaded = result.getOrDefault(emptyList())
            allComments.clear()
            allComments.addAll(loaded)
            rebuildFromFirstPage(adapter)
            showLoading(false)
            if (result.isFailure) {
                Toast.makeText(this@CommentsActivity, getString(R.string.comment_load_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rebuildFromFirstPage(adapter: CommentsAdapter) {
        showLoading(true)
        shownComments.clear()
        nextPage = 1
        val page = CommentPagingSource.paginate(allComments, nextPage, PAGE_SIZE, sortOrder)
        shownComments.addAll(page.items)
        nextPage = page.nextPage
        hasMore = page.hasMore
        adapter.notifyDataSetChanged()
        updateEmptyState()
        showLoading(false)
    }

    private fun appendPage(adapter: CommentsAdapter) {
        if (!hasMore) return
        val page = CommentPagingSource.paginate(allComments, nextPage, PAGE_SIZE, sortOrder)
        val start = shownComments.size
        shownComments.addAll(page.items)
        nextPage = page.nextPage
        hasMore = page.hasMore
        adapter.notifyItemRangeInserted(start, page.items.size)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        emptyText.visibility = if (shownComments.isEmpty()) View.VISIBLE else View.GONE
        loadMoreButton.visibility = if (hasMore) View.VISIBLE else View.GONE
    }

    private fun showLoading(visible: Boolean) {
        spinner.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_POST_URL = "extra_post_url"
        const val EXTRA_POST_TITLE = "extra_post_title"
        private const val PAGE_SIZE = 15
    }
}

private class CommentsAdapter(
    private val items: List<DcComment>
) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return CommentViewHolder(view)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val writer = itemView.findViewById<TextView>(R.id.commentWriterText)
        private val date = itemView.findViewById<TextView>(R.id.commentDateText)
        private val body = itemView.findViewById<TextView>(R.id.commentBodyText)

        fun bind(item: DcComment) {
            writer.text = item.writer
            date.text = item.dateText
            body.text = item.text
        }
    }
}

