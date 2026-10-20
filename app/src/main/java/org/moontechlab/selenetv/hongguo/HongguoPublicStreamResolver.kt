// SPDX-License-Identifier: GPL-3.0-only
package org.moontechlab.selenetv.hongguo

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves only public MP4 URLs exposed by the official Hongguo web player.
 * It honors the page's accessible_episode_cnt gate and never treats preview-only
 * episodes as full public episodes.
 */
object HongguoPublicStreamResolver {
    private const val TAG = "HongguoPlayback"
    private const val SITE = "https://hongguoduanju.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    data class PublicStream(val url: String, val quality: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    fun resolve(seriesId: String, vid: String, episodeIndex: Int): PublicStream? {
        if (!seriesId.matches(Regex("[0-9]{6,32}")) || !vid.matches(Regex("[0-9]{6,32}"))) return null
        return try {
            val page = Request.Builder()
                .url("$SITE/player/$seriesId/$vid")
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "$SITE/")
                .build()

            val html = client.newCall(page).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string().orEmpty()
            }
            if (html.isBlank()) return null

            val accessible = Regex("""["']accessible_episode_cnt["']\s*:\s*(\d+)""")
                .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (accessible != null && accessible > 0 && episodeIndex + 1 > accessible) {
                Log.i(TAG, "public web stream skipped: episode=${episodeIndex + 1}, accessible=$accessible")
                return null
            }

            val scripts = Regex(
                """<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
                RegexOption.IGNORE_CASE
            ).findAll(html)

            val candidates = linkedMapOf<String, Int>()
            val contentPattern = Regex("""["']contentUrl["']\s*:\s*["']([^"']+)["']""")
            for (script in scripts) {
                for (match in contentPattern.findAll(script.groupValues[1])) {
                    val normalized = normalize(match.groupValues[1])
                    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) continue
                    if (!normalized.contains("/video/public/$vid/")) continue
                    val q = Regex("""/(\d+)[pP]\.mp4(?:[?#]|$)""")
                        .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    if (q > 0) candidates[normalized] = q
                }
            }

            candidates.entries
                .sortedByDescending { it.value }
                .firstNotNullOfOrNull { (url, q) ->
                    if (probe(url)) PublicStream(url, q) else null
                }
                ?.also { Log.i(TAG, "public web stream selected: quality=${it.quality}P") }
        } catch (e: Exception) {
            Log.w(TAG, "public web stream unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun probe(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "$SITE/")
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { it.code == 206 || it.code == 200 }
        } catch (_: Exception) {
            false
        }
    }

    private fun normalize(raw: String): String = raw.trim()
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace(Regex("""([?&])Range=[^&]*"""), "$1")
        .trimEnd('?', '&')
}
