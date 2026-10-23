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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private lateinit var contacts: ContactResolver
    private lateinit var device: DeviceController
    private lateinit var voice: VoiceController

    private var speaking = false

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
        binding.web.loadUrl("file:///android_asset/web/index.html")
    }

    private fun setupVoice() {
        voice.onPartial = { text -> js("Neo.onPartial(${JSONObject.quote(text)})") }
        voice.onFinal = { text ->
            js("Neo.onPartial('')")
            runTurn(text.trim(), fromVoice = true)
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
        voice.onSpeechEnd = {
            speaking = false
            js("Neo.onState('idle')")
            js("Neo.onSpeakEnd()")
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
                    handleDeviceIntent(intent, args, reply)
                }
            } catch (e: Exception) {
                js("Neo.onState('idle')")
                val offline = "Backend se connect nahi ho paya. Settings me URL aur token check karein — local Termux ya apna HTTPS cloud host."
                js("Neo.onAssistant(${JSONObject.quote(offline)})")
                speak("Backend offline hai. Settings me connection check karein.")
            }
        }
    }

    private fun handleDeviceIntent(intent: String, args: JSONObject, aiReply: String) {
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

    private fun speak(text: String) {
        if (!prefs.speakReplies || text.isBlank()) {
            js("Neo.onState('idle')")
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

    override fun onResume() {
        super.onResume()
        NeoAccessibilityService.userEnabledAutomation = prefs.automation
        pushAppState()
        checkBackend()
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
                js("Neo.onState('listening')")
                voice.startListening(prefs.language)
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
            runOnUiThread {
                js("Neo.onState('idle')")
                speaking = false
            }
        }

        @JavascriptInterface
        fun executeIntent(intent: String, argsJson: String) {
            runOnUiThread {
                val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
                handleDeviceIntent(intent, args, "")
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
