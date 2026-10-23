package com.neoai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BackendException(message: String) : Exception(message)

class ApiClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(prefs.serverUrl + path)
            .header("X-Neo-Token", prefs.token)
            .header("Accept", "application/json")

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request("/health").get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun chat(text: String, deviceContext: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("message", text)
            .put("language", prefs.language)
            .put("user_name", prefs.displayName)
            .put("device", deviceContext)
            .toString()
            .toRequestBody(jsonType)

        http.newCall(request("/chat").post(body).build()).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw BackendException("Server ${res.code}: ${friendlyError(raw)}")
            }
            runCatching { JSONObject(raw) }
                .getOrElse { throw BackendException("Invalid response from server") }
        }
    }

    suspend fun ack(intent: String, ok: Boolean, detail: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("intent", intent)
                .put("ok", ok)
                .put("detail", detail.take(300))
                .toString()
                .toRequestBody(jsonType)
            http.newCall(request("/chat/ack").post(body).build()).execute().close()
        }
    }

    /** Returns (base64 audio, mime) or null when ElevenLabs is not configured. */
    suspend fun tts(text: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("text", text)
            .put("language", prefs.language)
            .toString()
            .toRequestBody(jsonType)

        http.newCall(request("/tts").post(body).build()).execute().use { res ->
            if (res.code == 503) return@withContext null
            if (!res.isSuccessful) throw BackendException("TTS ${res.code}: ${friendlyError(res.body?.string().orEmpty())}")
            val mime = res.header("Content-Type") ?: "audio/mpeg"
            val bytes = res.body?.bytes() ?: return@withContext null
            if (bytes.isEmpty()) null else android.util.Base64.encodeToString(
                bytes, android.util.Base64.NO_WRAP
            ) to mime
        }
    }

    suspend fun weather(lat: Double?, lon: Double?): String = withContext(Dispatchers.IO) {
        val q = buildString {
            append("/weather?lang=").append(prefs.language)
            if (lat != null && lon != null) append("&lat=").append(lat).append("&lon=").append(lon)
        }
        http.newCall(request(q).get().build()).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw BackendException("Weather ${res.code}")
            JSONObject(raw).optString("text", "Weather data mila nahi.")
        }
    }

    private fun friendlyError(raw: String): String = runCatching {
        JSONObject(raw).optString("detail").ifBlank { raw.take(200) }
    }.getOrDefault(raw.take(200))

    fun deviceContext(extra: Map<String, String> = emptyMap()): JSONObject {
        val arr = JSONArray()
        extra.forEach { (k, v) -> arr.put(JSONObject().put("key", k).put("value", v)) }
        return JSONObject()
            .put("platform", "android")
            .put("accessibility", extra["accessibility"] ?: "off")
            .put("battery", extra["battery"] ?: "")
            .put("apps", JSONObject.NULL)
    }
}
