package com.technewz.app.net

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.technewz.app.data.AppJson

class AiUnavailableException(message: String) : Exception(message)

/**
 * Thin REST client for the Gemini API (generateContent). Responses are requested as JSON so
 * they can be validated before anything is shown to the user.
 */
class Gemini(private val apiKey: String, private var model: String) {

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    suspend fun generateJson(prompt: String, pdf: ByteArray? = null, temperature: Double = 0.2): JsonElement {
        val text = generate(prompt, pdf, temperature, json = true)
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return AppJson.parseToJsonElement(cleaned)
    }

    suspend fun generate(prompt: String, pdf: ByteArray? = null, temperature: Double = 0.4, json: Boolean = false): String {
        if (apiKey.isBlank()) throw AiUnavailableException("Add a Gemini API key in Settings to enable AI features.")
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (pdf != null) addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "application/pdf")
                                put("data", Base64.encodeToString(pdf, Base64.NO_WRAP))
                            }
                        }
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                if (json) put("responseMimeType", "application/json")
            }
        }.toString()

        val response = try {
            call(body)
        } catch (e: HttpException) {
            if (e.code == 404) {
                // Model retired or renamed: pick the newest available Flash model and retry once.
                model = discoverModel() ?: throw AiUnavailableException("Gemini model '$model' is not available.")
                call(body)
            } else throw translate(e)
        }
        val root = AppJson.parseToJsonElement(response).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiUnavailableException("Gemini returned no answer (it may have been blocked by safety filters).")
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: JsonArray(emptyList())
        return parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
    }

    private suspend fun call(body: String): String =
        Http.postJson("$base/models/$model:generateContent", body, mapOf("x-goog-api-key" to apiKey))

    private fun translate(e: HttpException): Exception = when (e.code) {
        400 -> if (e.message?.contains("API_KEY", true) == true || e.message?.contains("API key", true) == true)
            AiUnavailableException("Your Gemini API key was rejected. Check it in Settings.") else e
        401, 403 -> AiUnavailableException("Your Gemini API key was rejected. Check it in Settings.")
        429 -> AiUnavailableException("Gemini rate limit reached — will retry on the next refresh.")
        else -> e
    }

    suspend fun discoverModel(): String? = runCatching {
        val json = Http.get("$base/models?pageSize=200", mapOf("x-goog-api-key" to apiKey))
        val models = AppJson.parseToJsonElement(json).jsonObject["models"]?.jsonArray ?: return null
        models.map { it.jsonObject }
            .filter { m ->
                val methods = m["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                "generateContent" in methods
            }
            .map { it["name"]!!.jsonPrimitive.content.removePrefix("models/") }
            .filter { it.contains("flash") && !it.contains("image") && !it.contains("tts") && !it.contains("live") && !it.contains("exp") }
            .sortedByDescending { it }
            .let { list -> list.firstOrNull { it.contains("lite") && !it.contains("preview") } ?: list.firstOrNull() }
    }.getOrNull()

    /** Lightweight connectivity check used by Settings → "Test key". */
    suspend fun ping(): String {
        generate("Reply with the single word OK.", temperature = 0.0)
        return model
    }
}

fun JsonObject.str(key: String): String = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

fun JsonObject.strList(key: String): List<String> =
    this[key]?.let { el -> runCatching { el.jsonArray.map { it.jsonPrimitive.content.trim() } }.getOrNull() }
        ?.filter { it.isNotEmpty() } ?: emptyList()

