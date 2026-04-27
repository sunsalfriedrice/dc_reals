package com.project.dc_reels.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.project.dc_reels.R

class SimpleImagePagerAdapter(
    private val imageUrls: List<String>,
    private val onTap: (Int) -> Unit,
    private val useCenterCrop: Boolean = false
) : RecyclerView.Adapter<SimpleImagePagerAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_viewer_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(imageUrls[position], position)
    }

    override fun getItemCount(): Int = imageUrls.size

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<ImageView>(R.id.viewerImageView)

        fun bind(url: String, position: Int) {
            imageView.scaleType = if (useCenterCrop) {
                ImageView.ScaleType.CENTER_CROP
            } else {
                ImageView.ScaleType.FIT_CENTER
            }

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
            imageView.setOnClickListener { onTap(position) }
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

