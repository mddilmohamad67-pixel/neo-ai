package com.neoai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.neoai.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private lateinit var contacts: ContactResolver
    private lateinit var device: DeviceController
    private lateinit var voice: VoiceController

    private var speaking = false
    private var isResumed = false
    private var awaitingConfirm: PendingAction? = null
    private var confirmTries = 0

    private data class PendingAction(val intent: String, val args: JSONObject)

    private val trackedPermissions = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        js("Neo.onPermissions(${permissionStatusJson()})")
        refreshAccessibilityState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        api = ApiClient(prefs)
        contacts = ContactResolver(this)
        device = DeviceController(this, prefs, contacts)
        voice = VoiceController(this)

        setupWebView()
        setupVoice()
        loadUi()
        syncWebUi()

        if (!prefs.handsFree) {
            NeoForegroundService.stop(this)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.web.setBackgroundColor(0xFF07070D.toInt())
        with(binding.web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString NeoAI/1.0"
        }
        binding.web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.web.isVerticalScrollBarEnabled = false
        binding.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pushAppState()
                js("Neo.onReady()")
                checkBackend()
            }
        }
        binding.web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("NEO-WEB", "${msg.message()} @${msg.lineNumber()}")
                return true
            }
        }
        binding.web.addJavascriptInterface(Bridge(), "NeoNative")
    }

    private fun setupVoice() {
        voice.onPartial = { text -> js("Neo.onPartial(${JSONObject.quote(text)})") }
        voice.onFinal = { text ->
            js("Neo.onPartial('')")
            if (awaitingConfirm != null) handleConfirmAnswer(text.trim())
            else runTurn(text.trim(), fromVoice = true)
        }
        voice.onError = { message ->
            js("Neo.onPartial('')")
            js("Neo.onError(${JSONObject.quote(message)})")
            js("Neo.onState('idle')")
        }
        voice.onSpeechStart = {
            speaking = true
            js("Neo.onState('speaking')")
        }
        voice.onSpeechEnd = { onReplyFinished() }
    }

    /** Called whenever a spoken reply finished (local TTS or ElevenLabs audio). */
    private fun onReplyFinished() {
        speaking = false
        js("Neo.onState('idle')")
        js("Neo.onSpeakEnd()")
        if (awaitingConfirm != null) {
            binding.web.postDelayed({ startConfirmListen() }, 300)
            return
        }
        if (prefs.handsFree && isResumed) {
            binding.web.postDelayed({
                if (isResumed && prefs.handsFree && awaitingConfirm == null &&
                    !speaking && !voice.isListening()
                ) {
                    startVoiceListen()
                }
            }, 450)
        }
    }

    private fun startVoiceListen() {
        if (!isResumed || voice.isListening()) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        js("Neo.onState('listening')")
        voice.startListening(prefs.language)
    }

    private fun startConfirmListen() {
        if (awaitingConfirm == null || !isResumed) return
        startVoiceListen()
    }

    private fun beginVoiceConfirm(intent: String, args: JSONObject) {
        awaitingConfirm = PendingAction(intent, args)
        confirmTries = 0
        js("Neo.onState('thinking')")
        speak(device.voicePrompt(intent, args) + " Haan ya nahi?", force = true)
    }

    private fun handleConfirmAnswer(raw: String) {
        val pending = awaitingConfirm ?: return
        val t = raw.lowercase()
        val yes = Regex("\\b(haan|han|ha|yes|yeah|yep|bilkul|kar do|kardo|bhej do|bhejdo|send|theek|thik|ok|okay|sahi)\\b")
            .containsMatchIn(t)
        val no = Regex("\\b(nahi|nahin|na|no|cancel|mat|ruko|band|stop)\\b").containsMatchIn(t)
        when {
            yes -> {
                awaitingConfirm = null
                confirmTries = 0
                perform(pending.intent, pending.args, "")
            }
            no -> {
                awaitingConfirm = null
                confirmTries = 0
                finishWithReply("Cancel kar diya.", "")
            }
            else -> {
                confirmTries++
                if (confirmTries >= 2) {
                    awaitingConfirm = null
                    confirmTries = 0
                    finishWithReply("Cancel kar diya.", "")
                } else {
                    speak("Samajh nahi aaya. Haan ya nahi?", force = true)
                }
            }
        }
    }

    // ------------------------------------------------------------ turn flow

    private fun runTurn(raw: String, fromVoice: Boolean) {
        val text = raw.replace(Regex("^(hey |ok |okay )?neo[, ]+", RegexOption.IGNORE_CASE), "").trim()
        if (text.isBlank()) {
            js("Neo.onState('idle')")
            return
        }

        js("Neo.onUser(${JSONObject.quote(text)}, ${fromVoice})")
        js("Neo.onState('thinking')")

        val local = device.tryLocal(text)
        if (local != null) {
            finishWithReply(local.message, "")
            return
        }

        val quick = device.localQuickIntent(text)
        if (quick != null) {
            handleDeviceIntent(quick.first, quick.second, "", fromVoice)
            return
        }

        lifecycleScope.launch {
            try {
                val ctx = api.deviceContext(
                    mapOf(
                        "accessibility" to if (NeoAccessibilityService.enabled) "on" else "off",
                        "battery" to "${device.batteryPercent()}%",
                        "user" to prefs.displayName
                    )
                )
                val result = api.chat(text, ctx)
                val reply = result.optString("reply").ifBlank { "Main sun rahi hoon." }
                val intent = result.optString("intent")
                val args = result.optJSONObject("args") ?: JSONObject()

                if (intent.isBlank() || intent == "chat" || intent == "none") {
                    finishWithReply(reply, intent)
                } else {
                    handleDeviceIntent(intent, args, reply, fromVoice)
                }
            } catch (e: Exception) {
                js("Neo.onState('idle')")
                val offline = "Backend se connect nahi ho paya. Settings me URL aur token check karein — local Termux ya apna HTTPS cloud host."
                js("Neo.onAssistant(${JSONObject.quote(offline)})")
                speak("Backend offline hai. Settings me connection check karein.")
            }
        }
    }

    private fun handleDeviceIntent(intent: String, args: JSONObject, aiReply: String, fromVoice: Boolean = false) {
        if (intent == "weather") {
            lifecycleScope.launch {
                val loc = lastLocation()
                val text = try {
                    api.weather(loc?.first, loc?.second)
                } catch (e: Exception) {
                    "Weather ke liye backend offline hai."
                }
                finishWithReply(text, "weather")
            }
            return
        }
        if (device.requiresConfirmation(intent) && fromVoice) {
            beginVoiceConfirm(intent, args)
            return
        }
        if (device.requiresConfirmation(intent)) {
            runOnUiThread {
                js("Neo.onState('idle')")
                AlertDialog.Builder(this)
                    .setTitle("Confirm karein")
                    .setMessage(device.describe(intent, args))
                    .setNegativeButton("Cancel") { _, _ ->
                        js("Neo.onAssistant(${JSONObject.quote("Cancel kar diya.")})")
                    }
                    .setPositiveButton("Kar do") { _, _ -> perform(intent, args, aiReply) }
                    .show()
            }
        } else {
            perform(intent, args, aiReply)
        }
    }

    private fun perform(intent: String, args: JSONObject, aiReply: String) {
        js("Neo.onState('thinking')")
        val result = device.execute(intent, args)
        lifecycleScope.launch { api.ack(intent, result.ok, result.message) }
        val spoken = if (result.ok) result.message else "Ye nahi ho paya: ${result.message}"
        finishWithReply(spoken, intent)
    }

    private fun finishWithReply(reply: String, intent: String) {
        js("Neo.onAssistant(${JSONObject.quote(reply)})")
        speak(reply)
    }

    // ---------------------------------------------------------------- speak

    private fun speak(text: String, force: Boolean = false) {
        if ((!prefs.speakReplies && !force) || text.isBlank()) {
            js("Neo.onState('idle')")
            binding.web.postDelayed({ if (isResumed) onReplyFinished() }, 180)
            return
        }
        lifecycleScope.launch {
            js("Neo.onState('thinking')")
            val audio = try {
                api.tts(text)
            } catch (e: Exception) {
                null
            }
            if (audio != null) {
                js("Neo.onState('speaking')")
                js("Neo.playAudio(${JSONObject.quote(audio.first)}, ${JSONObject.quote(audio.second)})")
            } else {
                voice.speakLocal(text, prefs.language)
            }
        }
    }

    private fun stopSpeaking() {
        voice.stopSpeaking()
        speaking = false
        js("Neo.stopAudio()")
        js("Neo.onState('idle')")
    }

    // ------------------------------------------------------------- web glue

    private fun js(script: String) {
        binding.web.post { runCatching { binding.web.evaluateJavascript(script, null) } }
    }

    private fun pushAppState() {
        val obj = JSONObject()
            .put("server", prefs.serverUrl)
            .put("language", prefs.language)
            .put("theme", prefs.theme)
            .put("speak", prefs.speakReplies)
            .put("handsFree", prefs.handsFree)
            .put("automation", prefs.automation && NeoAccessibilityService.enabled)
            .put("accessibility", NeoAccessibilityService.enabled)
            .put("name", prefs.displayName)
        js("Neo.onConfig($obj)")
    }

    private fun checkBackend() {
        lifecycleScope.launch {
            val ok = api.health()
            js("Neo.onBackend($ok)")
        }
    }

    // ------------------------------------------------------ WebView hot update

    private fun webDir() = File(filesDir, "web")

    private fun loadUi() {
        val local = File(webDir(), "index.html")
        val url = if (local.isFile) "file://${local.absolutePath}" else "file:///android_asset/web/index.html"
        binding.web.loadUrl(url)
    }

    /**
     * Downloads changed UI files from the backend, verifies every SHA-256, then
     * swaps the folder and reloads. Native Kotlin/Gradle changes still need an
     * APK install; HTML/CSS/JS do not.
     */
    private fun syncWebUi() {
        lifecycleScope.launch {
            runCatching {
                val manifest = api.uiManifest() ?: return@launch
                val version = manifest.optString("version")
                val entries = manifest.optJSONObject("files") ?: return@launch
                val current = webDir()
                if (version.isNotBlank() && version == prefs.uiVersion &&
                    File(current, "index.html").isFile
                ) return@launch

                val tmp = File(filesDir, "web.tmp")
                tmp.deleteRecursively()
                if (!tmp.mkdirs()) throw IOException("cannot create temp dir")

                var total = 0L
                val names = entries.keys()
                while (names.hasNext()) {
                    val rel = names.next()
                    if (rel.isBlank() || rel.contains("..") ||
                        !rel.matches(Regex("^[A-Za-z0-9._/-]+$"))
                    ) continue
                    val meta = entries.optJSONObject(rel) ?: continue
                    val expected = meta.optString("sha256")
                    if (expected.length != 64) continue
                    val bytes = api.uiFile(rel) ?: throw IOException("missing $rel")
                    total += bytes.size
                    if (bytes.size > 4 * 1024 * 1024 || total > 16 * 1024 * 1024) {
                        throw IOException("UI bundle too large")
                    }
                    if (sha256(bytes) != expected) throw IOException("hash mismatch: $rel")
                    val target = File(tmp, rel)
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                        throw IOException("unsafe path: $rel")
                    }
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
                if (!File(tmp, "index.html").isFile) throw IOException("index.html missing")

                current.deleteRecursively()
                if (!tmp.renameTo(current)) {
                    tmp.copyRecursively(current, overwrite = true)
                    tmp.deleteRecursively()
                }
                prefs.uiVersion = version
                runOnUiThread { if (!isFinishing) loadUi() }
            }.onFailure {
                // Keep the previous UI (or bundled assets) when anything fails.
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append("%02x".format(b.toInt() and 0xff))
        return out.toString()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        NeoAccessibilityService.userEnabledAutomation = prefs.automation
        pushAppState()
        checkBackend()
    }

    override fun onPause() {
        isResumed = false
        if (voice.isListening()) voice.stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    private fun refreshAccessibilityState() {
        NeoAccessibilityService.userEnabledAutomation =
            prefs.automation && NeoAccessibilityService.enabled
        pushAppState()
    }

    private fun permissionStatusJson(): JSONObject {
        val obj = JSONObject()
        trackedPermissions.forEach {
            obj.put(
                it,
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            )
        }
        return obj
    }

    // ----------------------------------------------------------------- bridge

    inner class Bridge {

        @JavascriptInterface
        fun state(): String = JSONObject().apply {
            put("server", prefs.serverUrl)
            put("language", prefs.language)
            put("theme", prefs.theme)
            put("speak", prefs.speakReplies)
            put("handsFree", prefs.handsFree)
            put("automation", prefs.automation && NeoAccessibilityService.enabled)
            put("accessibility", NeoAccessibilityService.enabled)
            put("name", prefs.displayName)
            put("token", prefs.token)
        }.toString()

        @JavascriptInterface
        fun saveConfig(json: String) {
            runCatching {
                val o = JSONObject(json)
                if (o.has("server")) prefs.serverUrl = o.optString("server", prefs.serverUrl)
                if (o.has("token")) prefs.token = o.optString("token", prefs.token)
                if (o.has("language")) prefs.language = o.optString("language", prefs.language)
                if (o.has("theme")) prefs.theme = o.optString("theme", prefs.theme)
                if (o.has("speak")) prefs.speakReplies = o.optBoolean("speak", true)
                if (o.has("name")) prefs.displayName = o.optString("name", prefs.displayName)
                runOnUiThread { pushAppState() }
                checkBackend()
            }
        }

        @JavascriptInterface
        fun startListening() {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    askPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return@runOnUiThread
                }
                this@MainActivity.stopSpeaking()
                startVoiceListen()
            }
        }

        @JavascriptInterface
        fun stopListening() {
            voice.stopListening()
        }

        @JavascriptInterface
        fun sendText(text: String) {
            runOnUiThread { runTurn(text, fromVoice = false) }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            runOnUiThread { this@MainActivity.stopSpeaking() }
        }

        @JavascriptInterface
        fun speakDone() {
            runOnUiThread { onReplyFinished() }
        }

        @JavascriptInterface
        fun executeIntent(intent: String, argsJson: String) {
            runOnUiThread {
                val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
                handleDeviceIntent(intent, args, "", fromVoice = false)
            }
        }

        @JavascriptInterface
        fun requestPermissions(csv: String) {
            val list = csv.split(",").map { it.trim() }.filter { it.isNotBlank() }.toTypedArray()
            if (list.isNotEmpty()) {
                runOnUiThread { askPermissions(list) }
            }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Accessibility kyun chahiye?")
                    .setMessage(
                        "NEO accessibility sirf tab use karti hai jab aap automation ON karte hain — " +
                            "aapke apne phone par button tap karne, text type karne aur scroll karne ke liye. " +
                            "Ye permission aap khud Android Settings me dete hain, aur kabhi bhi band kar sakte hain. " +
                            "NEO koi permission khud grant nahi karti."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Settings kholo") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .show()
            }
        }

        @JavascriptInterface
        fun setAutomation(on: Boolean): Boolean {
            prefs.automation = on
            NeoAccessibilityService.userEnabledAutomation = on && NeoAccessibilityService.enabled
            runOnUiThread { pushAppState() }
            if (on && !NeoAccessibilityService.enabled) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Pehle Accessibility Settings me NEO enable karein.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            return prefs.automation
        }

        @JavascriptInterface
        fun setHandsFree(on: Boolean) {
            prefs.handsFree = on
            runOnUiThread {
                if (on) NeoForegroundService.start(this@MainActivity)
                else NeoForegroundService.stop(this@MainActivity)
                pushAppState()
            }
        }

        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                }.onFailure {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            runOnUiThread {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun checkServer() {
            checkBackend()
        }

        @JavascriptInterface
        fun weather() {
            lifecycleScope.launch {
                try {
                    val loc = lastLocation()
                    val text = api.weather(loc?.first, loc?.second)
                    js("Neo.onWeather(${JSONObject.quote(text)})")
                    js("Neo.onAssistant(${JSONObject.quote(text)})")
                    speak(text)
                } catch (e: Exception) {
                    js("Neo.onState('idle')")
                    js("Neo.onAssistant(${JSONObject.quote("Weather ke liye backend offline hai.")})")
                }
            }
        }

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun permissionsStatus(): String = permissionStatusJson().toString()

        @JavascriptInterface
        fun copy(text: String): Boolean {
            val ok = this@MainActivity.copyToClipboard(text)
            if (ok) toast("Copied")
            return ok
        }

        @JavascriptInterface
        fun speakLocal(text: String) {
            runOnUiThread { voice.speakLocal(text, prefs.language) }
        }
    }

    private fun askPermissions(permissions: Array<String>) {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
            if (needed.isEmpty()) {
                js("Neo.onPermissions(${permissionStatusJson()})")
                refreshAccessibilityState()
                return
            }
            permissionLauncher.launch(needed.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (loc != null) return loc.latitude to loc.longitude
        }
        return null
    }
}
