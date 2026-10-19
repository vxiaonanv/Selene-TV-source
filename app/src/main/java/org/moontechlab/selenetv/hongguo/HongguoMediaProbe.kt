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
    private const val MAX_SCAN = 128 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun probe(url: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-131071")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.byteStream()?.use { it.readNBytesCompat(MAX_SCAN) } ?: byteArrayOf()
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
                if (signature == "iso-bmff" && response.code == 206) inspectMp4(bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "media probe unavailable: ${e.javaClass.simpleName}")
        }
    }


    // Only parse complete boxes within a bounded byte-range. No key data or URL is logged.
    private fun inspectMp4(data: ByteArray) {
        val found = linkedSetOf<String>()
        val container = setOf("moov", "trak", "mdia", "minf", "stbl", "sinf", "schi", "moof", "traf", "edts", "dinf", "mvex")
        val interesting = setOf("moov", "trak", "sinf", "schm", "tenc", "pssh", "encv", "enca")
        var completeMoov = false
        var truncated = false

        fun u32(i: Int): Long =
            ((data[i].toLong() and 255) shl 24) or
            ((data[i + 1].toLong() and 255) shl 16) or
            ((data[i + 2].toLong() and 255) shl 8) or
            (data[i + 3].toLong() and 255)

        fun scan(from: Int, to: Int, depth: Int) {
            if (depth > 8) return
            var pos = from
            while (pos + 8 <= to) {
                val size32 = u32(pos)
                val type = String(data, pos + 4, 4, Charsets.US_ASCII)
                val header = if (size32 == 1L) 16 else 8
                if (pos + header > to) { truncated = true; break }
                val size = when {
                    size32 == 0L -> (to - pos).toLong()
                    size32 == 1L -> (u32(pos + 8) shl 32) or u32(pos + 12)
                    else -> size32
                }
                if (size < header || size > to - pos) { truncated = true; break }
                val end = pos + size.toInt()
                if (type in interesting) found.add(type)
                if (type == "moov") completeMoov = true
                if (type in container) scan(pos + header, end, depth + 1)
                pos = end
            }
        }
        scan(0, data.size, 0)
        val status = if (completeMoov && !truncated) "moov-sampled" else "inconclusive"
        val marks = if (found.isEmpty()) "none" else found.joinToString(",")
        val protectedHint = found.any { it in setOf("sinf", "schm", "tenc", "pssh", "encv", "enca") }
        Log.i(TAG, "mp4 boxes: status=$status, markers=$marks, protectedBoxHint=$protectedHint")
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
