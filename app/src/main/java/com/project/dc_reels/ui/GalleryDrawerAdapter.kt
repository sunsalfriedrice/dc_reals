package com.project.dc_reels.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.project.dc_reels.R
import com.project.dc_reels.model.GalleryConfig

class GalleryDrawerAdapter(
    private val onSelect: (GalleryConfig) -> Unit,
    private val onRename: (GalleryConfig) -> Unit,
    private val onDelete: (GalleryConfig) -> Unit
) : RecyclerView.Adapter<GalleryDrawerAdapter.GalleryViewHolder>() {

    private val items = mutableListOf<GalleryConfig>()

    fun submitList(galleries: List<GalleryConfig>) {
        items.clear()
        items.addAll(galleries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery, parent, false)
        return GalleryViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class GalleryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.galleryNameText)
        private val subtitle = itemView.findViewById<TextView>(R.id.galleryIdText)
        private val editButton = itemView.findViewById<ImageButton>(R.id.editGalleryButton)
        private val deleteButton = itemView.findViewById<ImageButton>(R.id.deleteGalleryButton)

        fun bind(item: GalleryConfig) {
            title.text = item.displayName
            subtitle.text = item.id
            itemView.setOnClickListener { onSelect(item) }
            editButton.setOnClickListener { onRename(item) }
            deleteButton.setOnClickListener { onDelete(item) }
        }
    }
}

