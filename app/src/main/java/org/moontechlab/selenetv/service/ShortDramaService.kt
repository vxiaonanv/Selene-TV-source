package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DramaCategory(val id: Int, val name: String)
data class DramaItem(val id: String, val name: String, val cover: String, val episodeCount: Int = 0)
data class DramaDetail(val id: String, val name: String, val cover: String, val description: String, val episodeCount: Int)

/** Reads the short-drama API from the configured LunaTV server, not from a hardcoded provider. */
object ShortDramaService {
    private fun request(path: String): String {
        val base = AppDataService.getServerUrlSync()?.trimEnd('/')
            ?: error("请先登录并配置 LunaTV 服务端")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Accept", "application/json")
        AppDataService.getCookiesSync()?.let { conn.setRequestProperty("Cookie", it) }
        return try {
            if (conn.responseCode !in 200..299) error("短剧接口 HTTP ${conn.responseCode}：$path；实际地址：${conn.url}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    suspend fun categories(): List<DramaCategory> = withContext(Dispatchers.IO) {
        val arr = JSONArray(request("/api/shortdrama/categories"))
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optInt("type_id", -1)
            if (id < 0) null else DramaCategory(id, obj.optString("type_name"))
        }
    }

    suspend fun list(categoryId: Int, page: Int): Pair<List<DramaItem>, Boolean> = withContext(Dispatchers.IO) {
        val obj = JSONObject(request("/api/shortdrama/list?categoryId=$categoryId&page=$page&size=24"))
        val arr = obj.optJSONArray("list") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            DramaItem(d.optString("id"), d.optString("name"), d.optString("cover"), d.optInt("episode_count"))
        }.filter { it.id.isNotBlank() }
        items to obj.optBoolean("hasMore", false)
    }

    suspend fun detail(id: String): DramaDetail = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(id, "UTF-8")
        val d = JSONObject(request("/api/shortdrama/detail?id=$encoded"))
        DramaDetail(
            id = d.optString("id", id),
            name = d.optString("title"),
            cover = d.optString("poster"),
            description = d.optString("desc"),
            episodeCount = d.optJSONArray("episodes")?.length() ?: 0
        )
    }

    suspend fun playUrl(id: String, episodeIndex: Int, name: String): String = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val result = JSONObject(request("/api/shortdrama/parse?id=$encodedId&episode=${episodeIndex + 1}&name=$encodedName"))
        val url = result.optString("url")
        if (url.isBlank()) error("该集暂时没有播放地址")
        val base = AppDataService.getServerUrlSync()?.trimEnd('/') ?: ""
        if (url.startsWith("/")) base + url else url
    }
}
