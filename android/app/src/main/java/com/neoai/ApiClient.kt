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

    companion object {
        private val PRIVATE_HOST = Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)")
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        /**
         * Cloud backends must be HTTPS: the access token and chat text travel over the wire.
         * Cleartext is only tolerated for loopback/LAN development URLs.
         */
        fun safeBase(raw: String): String {
            val base = raw.trim().trimEnd('/')
            if (base.isEmpty()) throw BackendException("Server URL khali hai")
            val uri = android.net.Uri.parse(base)
            val scheme = uri.scheme?.lowercase()
                ?: throw BackendException("URL me http:// ya https:// likhein")
            val host = uri.host?.lowercase()
                ?: throw BackendException("Server URL galat hai")
            val local = host in LOCAL_HOSTS || host.endsWith(".local") ||
                PRIVATE_HOST.containsMatchIn(host)
            if (scheme != "https" && !local) {
                throw BackendException(
                    "Cloud backend ke liye https:// zaroori hai — warna token intercept ho sakta hai."
                )
            }
            return base
        }
    }

    private fun request(path: String): Request.Builder {
        val base = safeBase(prefs.serverUrl)
        val builder = Request.Builder()
            .url(base + path)
            .header("Accept", "application/json")
        if (prefs.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${prefs.token}")
                .header("X-Neo-Token", prefs.token)
        }
        return builder
    }

    /** Authenticated capability probe; false also means "token rejected". */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.token.isBlank()) return@runCatching false
            http.newCall(request("/health/full").get().build()).execute().use { it.isSuccessful }
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
