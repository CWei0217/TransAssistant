package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class OcrCacheEntry(
    val text: String,
    val pHash: String,
    val rect: String, // "left,top,width,height"
    val timestamp: Long,
    val isDynamic: Boolean
)

class OcrCacheManager(context: Context) {
    private val prefs = context.getSharedPreferences("ocr_cache_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getCachedResult(bitmap: Bitmap, rect: Rect, isDynamic: Boolean): String? {
        if (!prefs.getBoolean("enable_cache", true)) return null

        val currentPHash = PHashUtil.computePHash(bitmap)
        val rectKey = "${rect.left},${rect.top},${rect.width()},${rect.height()}"
        val cacheJson = prefs.getString("cache_data", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<OcrCacheEntry>>() {}.type
        val cacheList: MutableList<OcrCacheEntry> = gson.fromJson(cacheJson, type)

        val ttl = if (isDynamic) 30_000L else 300_000L // 30s or 5min
        val now = System.currentTimeMillis()

        // Remove expired entries
        val iterator = cacheList.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val entryTtl = if (entry.isDynamic) 30_000L else 300_000L
            if (now - entry.timestamp > entryTtl) {
                iterator.remove()
            }
        }

        val match = cacheList.find { entry ->
            entry.rect == rectKey && PHashUtil.calculateSimilarity(currentPHash, entry.pHash) >= 90.0
        }

        if (match != null) {
            saveCache(cacheList)
            return match.text
        }
        return null
    }

    fun putCache(text: String, bitmap: Bitmap, rect: Rect, isDynamic: Boolean) {
        val pHash = PHashUtil.computePHash(bitmap)
        val rectKey = "${rect.left},${rect.top},${rect.width()},${rect.height()}"
        val cacheJson = prefs.getString("cache_data", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<OcrCacheEntry>>() {}.type
        val cacheList: MutableList<OcrCacheEntry> = gson.fromJson(cacheJson, type)

        cacheList.add(OcrCacheEntry(text, pHash, rectKey, System.currentTimeMillis(), isDynamic))
        saveCache(cacheList)
    }

    private fun saveCache(list: List<OcrCacheEntry>) {
        prefs.edit().putString("cache_data", gson.toJson(list)).apply()
    }

    fun clearCache() {
        prefs.edit().remove("cache_data").apply()
    }
}
