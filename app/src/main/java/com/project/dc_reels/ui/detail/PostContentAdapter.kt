package com.project.dc_reels.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.project.dc_reels.R
import com.project.dc_reels.model.PostContentBlock

class PostContentAdapter(
    private val onImageClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<PostContentBlock>()

    fun submitList(blocks: List<PostContentBlock>) {
        items.clear()
        items.addAll(blocks)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].type == PostContentBlock.Type.IMAGE) TYPE_IMAGE else TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_IMAGE) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_post_content_image, parent, false)
            ImageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_post_content_text, parent, false)
            TextViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is TextViewHolder) {
            holder.bind(item)
        } else if (holder is ImageViewHolder) {
            holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private inner class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView = itemView.findViewById<TextView>(R.id.contentText)

        fun bind(item: PostContentBlock) {
            textView.text = item.text
        }
    }

    private inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<ImageView>(R.id.contentImage)

        fun bind(item: PostContentBlock) {
            val url = item.imageUrl.orEmpty()
            val glideUrl = GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader(HEADER_USER_AGENT, USER_AGENT)
                    .addHeader(HEADER_REFERER, REFERER)
                    .build()
            )
            Glide.with(imageView)
                .load(glideUrl)
                .placeholder(R.drawable.ic_launcher_foreground)
                .error(R.drawable.ic_launcher_foreground)
                .into(imageView)

            imageView.setOnClickListener {
                if (url.isNotBlank()) onImageClick(url)
            }
        }
    }

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_IMAGE = 1
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        private const val REFERER = "https://m.dcinside.com/"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_REFERER = "Referer"
    }
}

