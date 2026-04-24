package com.project.dc_reels.data

import android.content.Context
import com.project.dc_reels.model.GalleryConfig
import org.json.JSONArray
import org.json.JSONObject

class GalleryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadAll(): List<GalleryConfig> {
        val raw = prefs.getString(KEY_GALLERIES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isNotBlank()) {
                    add(GalleryConfig(id = id, displayName = if (name.isBlank()) id else name))
                }
            }
        }
    }

    fun saveAll(galleries: List<GalleryConfig>) {
        val arr = JSONArray()
        galleries.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.displayName)
            })
        }
        prefs.edit().putString(KEY_GALLERIES, arr.toString()).apply()
    }

    companion object {
        private const val PREF_NAME = "dc_reels_gallery_store"
        private const val KEY_GALLERIES = "galleries"
    }
}

