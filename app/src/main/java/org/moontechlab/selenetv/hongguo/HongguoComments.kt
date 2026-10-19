// GPL-3.0-only. Hongguo comment protocol adapted from djseven/223344 HongguoStreamResolver.
package org.moontechlab.selenetv.hongguo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Android-only HTTP bridge for Hongguo episode IDs and time-aligned comments.
 * Empty results or failed signatures are surfaced as errors to the caller; never fake comments.
 */
internal object HongguoComments {
    private val signer = Signer()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build()
    private val idPattern = Regex("[0-9]{1,32}")

    fun episodeVids(seriesId: String): List<String> {
        require(idPattern.matches(seriesId))
        val body = JSONObject().put("series_id", seriesId).toString().toByteArray()
        val signed = signer.signStandard(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_detail/v1/",
            VendorConstants.device, body
        )
        val builder = Request.Builder().url(signed.url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        signed.headers.forEach { (k,v) -> builder.header(k,v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Hongguo episode HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            if (root.optInt("code", 0) != 0) error("Hongguo episode API rejected")
            val out = ArrayList<String>()
            fun collect(value: Any?, depth: Int) {
                if (depth > 8 || out.size > 500) return
                when(value) {
                    is JSONObject -> {
                        val direct = value.optString("vid").ifBlank { value.optString("video_id") }
                        if (idPattern.matches(direct)) out.add(direct)
                        val keys = value.keys()
                        while (keys.hasNext()) { val key=keys.next(); if (key != "vid" && key != "video_id") collect(value.opt(key),depth+1) }
                    }
                    is JSONArray -> for(i in 0 until value.length()) collect(value.opt(i),depth+1)
                }
            }
            collect(root.opt("data"),0)
            return out.distinct()
        }
    }

    fun comments(seriesId: String, vid: String, startSec: Int = 0, endSec: Int = 0): List<Pair<Long,String>> {
        require(idPattern.matches(seriesId) && idPattern.matches(vid))
        val startMs = startSec.coerceAtLeast(0) * 1000L
        val duration = if (endSec > startSec) endSec.toLong() * 1000L + 120000 else startMs + 180000L
        val body = JSONObject().apply {
            put("comment_source",601); put("server_channel",1000); put("group_id",vid)
            put("group_type",30); put("comment_type",20); put("sort",1)
            put("count",90); put("cursor",""); put("aid",8662); put("compliance_status",0)
            put("business_param", JSONObject().apply {
                put("book_id",seriesId); put("start_offset_time",startMs)
                put("playlet_item_duration",duration)
                put("need_danmaku_guide_type",JSONArray(listOf(1,3,4,2)))
            })
        }.toString().toByteArray()
        val signed = signer.sign(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/$vid/v1/",
            VendorConstants.device,body)
        val builder = Request.Builder().url(signed.url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        signed.headers.forEach { (k,v) -> builder.header(k,v) }
        builder.header("Comment-Source","601").header("Server-Channel","1000")
        client.newCall(builder.build()).execute().use { response ->
            if(!response.isSuccessful) error("Hongguo comment HTTP ${response.code}")
            val root=JSONObject(response.body?.string().orEmpty())
            if(root.optInt("code",0)!=0) error("Hongguo comment API rejected")
            val entries=root.optJSONObject("data")?.optJSONArray("data_list") ?: return emptyList()
            val output=ArrayList<Pair<Long,String>>()
            for (i in 0 until entries.length()) {
                val comment=entries.optJSONObject(i)?.optJSONObject("comment") ?: continue
                val common=comment.optJSONObject("common") ?: continue
                if(common.optString("group_id") != vid || common.optInt("status",1)!=1) continue
                val ms=comment.optJSONObject("expand")?.optLong("offset_time",-1L) ?: -1L
                val text=common.optJSONObject("content")?.optString("text")?.trim().orEmpty()
                if(ms>=0 && text.isNotEmpty()) output.add(ms to text.take(200))
            }
            return output.distinct().sortedBy { it.first }
        }
    }
}
