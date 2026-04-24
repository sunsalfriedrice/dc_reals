package com.project.dc_reels.ui.reels

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.project.dc_reels.R
import com.project.dc_reels.model.DcPost

class ReelsPagerAdapter(
    private val onOpenComments: (DcPost) -> Unit,
    private val onOpenDetail: (DcPost) -> Unit
) : RecyclerView.Adapter<ReelsPagerAdapter.ReelViewHolder>() {

    private val items = mutableListOf<DcPost>()

    fun submitList(posts: List<DcPost>) {
        items.clear()
        items.addAll(posts)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reel_post, parent, false)
        return ReelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReelViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ReelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText = itemView.findViewById<TextView>(R.id.postTitleText)
        private val imageView = itemView.findViewById<ImageView>(R.id.postImageView)
        private val noImageIcon = itemView.findViewById<ImageView>(R.id.noImageIcon)
        private val previewText = itemView.findViewById<TextView>(R.id.postPreviewText)
        private val metaText = itemView.findViewById<TextView>(R.id.postMetaText)
        private val openCommentsButton = itemView.findViewById<Button>(R.id.openCommentsButton)

        fun bind(post: DcPost) {
            titleText.text = post.title
            previewText.text = post.preview
            metaText.text = itemView.context.getString(
                R.string.post_meta_format,
                post.writer,
                post.dateText,
                post.recommend,
                post.viewCount
            )

            if (post.imageUrl.isNullOrBlank()) {
                imageView.setImageDrawable(null)
                noImageIcon.visibility = View.VISIBLE
            } else {
                noImageIcon.visibility = View.GONE
                imageView.load(post.imageUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                    error(R.drawable.ic_launcher_foreground)
                }
            }

            previewText.setOnClickListener { onOpenDetail(post) }
            imageView.setOnClickListener { onOpenDetail(post) }
            openCommentsButton.setOnClickListener { onOpenComments(post) }
        }
    }
}

