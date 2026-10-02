package com.technewz.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) sudo-app/1.0 (personal news reader)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val aiClient: OkHttpClient = client.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = 4_000_000): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("User-Agent", UA).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for $url")
                val body = resp.body ?: throw IOException("Empty body")
                val source = body.source()
                source.request(maxBytes)
                val buffer = source.buffer
                val bytes = if (buffer.size > maxBytes) buffer.readByteArray(maxBytes) else buffer.readByteArray()
                String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
            }
        }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            aiClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw HttpException(resp.code, text.take(400))
                text
            }
        }
}

/** Streams a response body into [block] without buffering it as a String (for very large JSON). */
suspend fun <T> Http.stream(url: String, block: (java.io.InputStream) -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) sudo-app/1.0 (personal news reader)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for $url")
            block(resp.body!!.byteStream())
        }
    }
