// SPDX-License-Identifier: GPL-3.0-only
package org.moontechlab.selenetv.hongguo

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Bounded diagnostic request for the selected stream.
 * Never log URLs, query strings, cookies, media bytes or key material.
 * This does not decrypt protected media or claim a DRM scheme.
 */
object HongguoMediaProbe {
    private const val TAG = "HongguoPlayback"
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun probe(url: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-31")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.byteStream()?.use { it.readNBytesCompat(32) } ?: byteArrayOf()
                val signature = when {
                    bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" -> "iso-bmff"
                    bytes.size >= 7 && String(bytes, 0, 7, Charsets.US_ASCII) == "#EXTM3U" -> "hls"
                    bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "<MPD" -> "dash"
                    bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "G@M@" -> "unknown"
                    bytes.size >= 1 && bytes[0] == 0x47.toByte() -> "mpeg-ts-possible"
                    else -> "unknown"
                }
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase().take(72)
                val range = when (response.code) {
                    206 -> "partial"
                    200 -> "full-response"
                    else -> "other"
                }
                Log.i(TAG, "media probe: http=${response.code}, contentType=$type, range=$range, acceptRanges=${response.header("Accept-Ranges") == "bytes"}, signature=$signature, sampledBytes=${bytes.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "media probe unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArray(limit)
        var used = 0
        while (used < limit) {
            val n = read(out, used, limit - used)
            if (n <= 0) break
            used += n
        }
        return out.copyOf(used)
    }
}
