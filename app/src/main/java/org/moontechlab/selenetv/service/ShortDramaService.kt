package org.moontechlab.selenetv.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.moontechlab.selenetv.hongguo.HongguoComments
import org.moontechlab.selenetv.hongguo.HongguoStreamResolver
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DramaCategory(val id: Int, val name: String)
data class DramaItem(val id: String, val name: String, val cover: String, val episodeCount: Int = 0)
data class DramaDetail(val id: String, val name: String, val cover: String, val description: String, val episodeCount: Int, val episodes: List<DramaEpisode> = emptyList())
data class DramaEpisode(val title: String, val url: String)

/** Client-side Hongguo source based on djseven/223344. Does not depend on LunaTV CMS. */
object ShortDramaService {
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private val names=mapOf(1 to "真人剧",2 to "漫剧",3 to "AI剧",101 to "总热播榜",102 to "真人剧榜",103 to "漫剧榜",104 to "AI剧榜")
    private val genres=mapOf(1 to "short_play",2 to "comic_series",3 to "ai_series")
    private val rankPaths=mapOf(101 to "hot-drama",102 to "hot-real-drama",103 to "hot-comic-drama",104 to "hot-ai-drama")
    private val catalog=java.util.concurrent.ConcurrentHashMap<String,DramaItem>()
    private val idPattern=Regex("[0-9]{6,32}")
    private const val api="https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/?aid=8662&app_name=novelread&version_code=73532&device_platform=android&os=android"
    suspend fun categories():List<DramaCategory> = withContext(Dispatchers.IO) {
        names.map { DramaCategory(it.key,it.value) }
    }
    private fun get(url:String):String {
        val request=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36").build()
        client.newCall(request).execute().use { response ->
            if(!response.isSuccessful) error("红果 HTTP ${response.code}")
            return response.body?.string() ?: error("红果返回空数据")
        }
    }
    private fun listGenre(categoryId:Int,page:Int):Pair<List<DramaItem>,Boolean> {
        val genre=genres[categoryId] ?: error("未知短剧分类")
        val size=24
        val offset=(page.coerceAtLeast(1)-1)*size
        val body=JSONObject().apply {
            put("req_scene",if(genre=="short_play") "default" else genre)
            put("offset",offset);put("limit",size);put("req_type","only_content")
            put("need_selector_panel",false);put("client_req_type",if(offset>0) 2 else 3)
            put("session_id","");put("filter_ids","")
            put("select_items",JSONObject().apply {
                put("genre",JSONArray().put(genre));put("sort",JSONArray().put("online_time"))
                for(key in listOf("gender","category_dim_theme","category_dim_role","category_dim_epoch","online_time","creation_status"))put(key,JSONArray())
            })
        }.toString()
        val request=Request.Builder().url(api).post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent","com.dragon.read/7.3.5.32 (Linux; Android 14)")
            .header("X-XS-From-Web","0").header("Sdk-Version","2").build()
        client.newCall(request).execute().use { response ->
            if(!response.isSuccessful) error("红果分类接口 HTTP ${response.code}")
            val root=JSONObject(response.body?.string() ?: error("分类接口返回空数据"))
            if(root.optInt("code",0)!=0) error("红果分类接口错误：${root.optInt("code")}")
            val list=root.optJSONObject("data")?.optJSONArray("video_data") ?: error("红果分类接口缺少 video_data")
            val result=(0 until list.length()).mapNotNull { index ->
                val item=list.optJSONObject(index) ?: return@mapNotNull null
                val id=item.optString("series_id");val name=item.optString("title")
                if(!idPattern.matches(id)||name.isBlank())return@mapNotNull null
                DramaItem(id,name,item.optString("cover"),item.optInt("episode_cnt",0))
            }.distinctBy { it.id }
            result.forEach { catalog[it.id]=it }
            return result to (list.length()>=size)
        }
    }
    private fun listRank(categoryId:Int):Pair<List<DramaItem>,Boolean> {
        val rank=rankPaths[categoryId] ?: error("未知排行榜")
        val html=get("https://hongguoduanju.com/rank/$rank")
        val ids=Regex("""/detail\?series_id=([0-9]{6,32})""").findAll(html).map{it.groupValues[1]}.distinct().toList()
        val results=ids.mapNotNull { id ->
            val previous=catalog[id]
            if(previous!=null)return@mapNotNull previous
            val pos=html.indexOf("series_id=$id")
            val chunk=html.substring((pos-250).coerceAtLeast(0),(pos+1200).coerceAtMost(html.length))
            val title=Regex("""aria-label=["']查看([^"']+)["']""").find(chunk)?.groupValues?.get(1)
                ?:Regex("""alt=["']([^"']+?)封面["']""").find(chunk)?.groupValues?.get(1)
                ?:return@mapNotNull null
            val image=Regex("""<img[^>]+src=["'](https?://[^"']+)["']""").find(chunk)?.groupValues?.get(1).orEmpty()
            DramaItem(id,title,image).also { catalog[id]=it }
        }
        return results to false
    }
    suspend fun list(categoryId:Int,page:Int):Pair<List<DramaItem>,Boolean> = withContext(Dispatchers.IO) {
        if(categoryId in genres)listGenre(categoryId,page)
        else if(categoryId in rankPaths) {if(page>1)emptyList<DramaItem>() to false else listRank(categoryId)}
        else error("未知红果短剧分类：$categoryId")
    }
    suspend fun detail(id:String):DramaDetail = withContext(Dispatchers.IO) {
        require(idPattern.matches(id)) { "无效红果短剧 ID" }
        val cached=catalog[id]
        val vids=HongguoComments.episodeVids(id)
        if(vids.isEmpty())error("红果未返回真实分集，不能使用虚构分集")
        DramaDetail(id,cached?.name.orEmpty(),cached?.cover.orEmpty(),"",vids.size,
            vids.mapIndexed { index,vid -> DramaEpisode("第${index+1}集","hongguo:$vid") })
    }
    suspend fun playUrl(id:String,episodeIndex:Int,name:String,directUrl:String=""):String = withContext(Dispatchers.IO) {
        val vid=if(directUrl.startsWith("hongguo:")) directUrl.removePrefix("hongguo:") else {
            require(idPattern.matches(id)) { "无效红果短剧 ID" }
            HongguoStreamResolver.fetchEpisodeVids(id).getOrNull(episodeIndex) ?: error("未找到第${episodeIndex+1}集")
        }
        val info=HongguoStreamResolver.resolve(vid)
        val url=info["url"] as? String ?: error("红果没有返回播放地址")
        if(!url.startsWith("https://") && !url.startsWith("http://"))error("播放地址格式异常")
        // Diagnostic metadata only: never log signed URLs, token query strings or derived keys.
        // A spade_a-derived key is a protection *hint*, not proof of DRM or encrypted samples.
        val key = (info["decryptionKey"] as? String).orEmpty()
        val quality = (info["quality"] as? String).orEmpty()
        val variants = (info["qualities"] as? List<*>)?.size ?: 0
        val mediaTypeHint = try {
            val path = java.net.URI(url).path.orEmpty().lowercase()
            when {
                path.endsWith(".m3u8") -> "hls"
                path.endsWith(".mpd") -> "dash"
                path.endsWith(".mp4") -> "mp4"
                path.endsWith(".m4s") -> "fragment"
                else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
        android.util.Log.i(
            "HongguoPlayback",
            "stream diagnostic: quality=$quality, variants=$variants, urlPathType=$mediaTypeHint, keyHint=${key.isNotBlank()}"
        )
        if (key.isNotBlank()) {
            // Do not send potentially protected samples to a clear-only Media3 data source.
            // Actual encryption and supported license path require further verification.
            error("当前画质返回内容保护相关标记，尚未确认媒体格式及所需播放支持")
        }
        url
    }
}
