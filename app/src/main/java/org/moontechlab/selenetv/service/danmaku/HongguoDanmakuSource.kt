package org.moontechlab.selenetv.service.danmaku

import org.json.JSONObject
import org.moontechlab.selenetv.hongguo.HongguoComments
import org.moontechlab.selenetv.model.DanmakuComment
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** Hongguo real episode comments. Search and comments are best-effort; never synthesize messages. */
class HongguoDanmakuSource : DanmakuSource {
    override val providerName = "hongguo"
    private val client = OkHttpClient()
    override fun search(keyword: String): List<DanmakuMedia> = runCatching {
        if(keyword.isBlank()) return emptyList()
        val url="https://hongguoduanju.com/search/" + URLEncoder.encode(keyword.trim(), "UTF-8")
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0").build()
        client.newCall(req).execute().use { response ->
            if(!response.isSuccessful) return emptyList()
            val html=response.body?.string().orEmpty()
            Regex("""/detail\\?series_id=([0-9]{6,32})""").findAll(html)
                .map { it.groupValues[1] }.distinct().take(20)
                .map { id -> DanmakuMedia(providerName,id,keyword.trim(),episodeCount=null) }.toList()
        }
    }.getOrDefault(emptyList())

    override fun getEpisodes(mediaId: String): List<DanmakuEpisode> = runCatching {
        HongguoComments.episodeVids(mediaId).mapIndexed { index, vid ->
            DanmakuEpisode(providerName, "$mediaId:$vid", "第${index+1}集", index+1)
        }
    }.getOrDefault(emptyList())

    override fun getComments(episodeId: String): List<DanmakuComment> = getComments(episodeId,0,0)

    override fun getComments(episodeId: String,startSec:Int,endSec:Int): List<DanmakuComment> = runCatching {
        val ids=episodeId.split(':')
        if(ids.size!=2) return emptyList()
        HongguoComments.comments(ids[0],ids[1],startSec,endSec).map { (time,text) ->
            DanmakuComment(time, DanmakuComment.MODE_SCROLL, 0xFFFFFFL, text)
        }
    }.getOrDefault(emptyList())
}
