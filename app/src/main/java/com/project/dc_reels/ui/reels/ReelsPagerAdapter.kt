package com.project.dc_reels.ui.reels

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.project.dc_reels.R
import com.project.dc_reels.model.DcPost

class ReelsPagerAdapter(
    private val onOpenComments: (DcPost) -> Unit,
    private val onOpenDetail: (DcPost) -> Unit,
    private val onOpenImages: (DcPost) -> Unit,
    private val onOpenOriginal: (DcPost) -> Unit
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
        private val openOriginalButton = itemView.findViewById<ImageButton>(R.id.openOriginalButton)
        private val openCommentsButton = itemView.findViewById<ImageButton>(R.id.openCommentsButton)

        fun bind(post: DcPost) {
            titleText.text = post.title
            previewText.text = post.preview

            if (post.imageUrl.isNullOrBlank()) {
                imageView.setImageDrawable(null)
                noImageIcon.visibility = View.VISIBLE
            } else {
                noImageIcon.visibility = View.INVISIBLE
                val glideUrl = GlideUrl(
                    post.imageUrl,
                    LazyHeaders.Builder()
                        .addHeader(HEADER_USER_AGENT, USER_AGENT)
                        .addHeader(HEADER_REFERER, REFERER)
                        .build()
                )

                Glide.with(imageView)
                    .load(glideUrl)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            noImageIcon.visibility = View.VISIBLE
                            return false
                        }

                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: Target<android.graphics.drawable.Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            noImageIcon.visibility = View.GONE
                            return false
                        }
                    })
                    .into(imageView)
            }

            previewText.setOnClickListener { onOpenDetail(post) }
            openOriginalButton.setOnClickListener { onOpenOriginal(post) }
            imageView.setOnClickListener {
                if (!post.imageUrl.isNullOrBlank()) {
                    onOpenImages(post)
                }
            }
            openCommentsButton.setOnClickListener { onOpenComments(post) }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        private const val REFERER = "https://m.dcinside.com/"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_REFERER = "Referer"
    }
}

