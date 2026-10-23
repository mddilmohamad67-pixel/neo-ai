package com.neoai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ActionResult(val ok: Boolean, val message: String, val intent: String = "")

/**
 * Executes only user-visible, officially supported Android actions.
 * Nothing here bypasses a platform restriction: if an app does not expose an
 * API or deep link, we say so instead of pretending the action worked.
 */
class DeviceController(
    private val context: Context,
    private val prefs: Prefs,
    private val contacts: ContactResolver
) {

    private val audio: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun requiresConfirmation(intent: String): Boolean = when (intent) {
        "call", "whatsapp", "sms", "reminder", "accessibility", "uninstall", "delete" -> true
        else -> false
    }

    fun describe(intent: String, args: JSONObject): String = when (intent) {
        "call" -> "Call ${args.optString("name").ifBlank { args.optString("number") }}?"
        "whatsapp" -> "WhatsApp ${args.optString("name")}:\n\"${args.optString("message")}\"\n\nMessage app me khulega, send aap dabaiye."
        "sms" -> "SMS ${args.optString("name")}:\n\"${args.optString("message")}\""
        "reminder" -> "Reminder: ${args.optString("title")}"
        "accessibility" -> "Phone par automation: ${args.optString("action")} ${args.optString("text")}".trim()
        else -> "Ye action chalayein?"
    }

    fun execute(intent: String, args: JSONObject): ActionResult = try {
        when (intent) {
            "open_app" -> openApp(args.optString("app"))
            "open_url" -> openUrl(args.optString("url"))
            "search" -> search(args.optString("app"), args.optString("query"))
            "call" -> callContact(args.optString("name"), args.optString("number"))
            "whatsapp" -> whatsapp(args.optString("name"), args.optString("number"), args.optString("message"))
            "sms" -> sms(args.optString("name"), args.optString("number"), args.optString("message"))
            "volume" -> volume(args.optString("direction"), args.optInt("percent", 50))
            "torch" -> torch(args.optBoolean("on", true))
            "settings" -> openSettings(args.optString("screen"))
            "reminder" -> reminder(args.optString("title"), args.optString("when"))
            "alarm" -> alarm(args.optInt("hour", 7), args.optInt("minute", 0))
            "accessibility" -> accessibilityAction(args.optString("action"), args.optString("text"))
            "time" -> ActionResult(true, timeText())
            "battery" -> ActionResult(true, batteryText())
            "stop" -> ActionResult(true, "Theek hai, ruk gaya.")
            else -> ActionResult(false, "Ye command abhi support nahi hai: $intent")
        }
    } catch (e: SecurityException) {
        ActionResult(false, "Permission nahi hai. Settings > Permissions se allow karein.")
    } catch (e: Exception) {
        ActionResult(false, "Action fail hua: ${e.message ?: e.javaClass.simpleName}")
    }

    // ---------------------------------------------------------------- apps

    private fun openApp(label: String): ActionResult {
        val wanted = label.ifBlank { "settings" }.lowercase()
        val alias = mapOf(
            "yt" to "youtube", "insta" to "instagram", "wa" to "whatsapp",
            "setting" to "settings", "config" to "settings", "cam" to "camera",
            "photos" to "gallery", "gallery" to "photos", "browser" to "chrome",
            "music" to "spotify", "map" to "maps"
        ).entries.firstOrNull { wanted.contains(it.key) }?.value ?: wanted

        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val best = apps
            .map { it to pm.getApplicationLabel(it).toString() }
            .filter { (_, name) ->
                val n = name.lowercase()
                n.contains(alias) || alias.contains(n) || levenshtein(n, alias) <= 2
            }
            .sortedBy { (_, name) -> if (name.equals(alias, true)) 0 else name.length }
            .firstOrNull()
            ?: return ActionResult(false, "\"$label\" naam ka app install nahi mila.")

        val launch = pm.getLaunchIntentForPackage(best.first.packageName)
            ?: return ActionResult(false, "${best.second} koi launcher activity expose nahi karta.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return ActionResult(true, "${best.second} khol diya.")
    }

    private fun openUrl(url: String): ActionResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ActionResult(false, "Sirf http/https links allowed hain.")
        }
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Link khol diya.")
    }

    private fun search(app: String, query: String): ActionResult {
        if (query.isBlank()) return ActionResult(false, "Search query khali hai.")
        val q = Uri.encode(query)
        return when (app.lowercase()) {
            "youtube" -> start(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))
                    .setPackage("com.google.android.youtube"),
                "YouTube par \"$query\" search khol diya."
            ).let { if (it.ok) it else start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")), "YouTube par search khol diya.") }

            "instagram" -> {
                val pkg = "com.instagram.android"
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return ActionResult(false, "Instagram install nahi hai.")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                ActionResult(
                    true,
                    "Instagram khol diya. Instagram search ke liye koi official deep link nahi deta — search bar khud dabana padega."
                )
            }

            "whatsapp" -> start(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$q")),
                "WhatsApp share screen khol di."
            )

            else -> start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")), "Google par search khol diya.")
        }
    }

    // ------------------------------------------------------------- calling

    private fun resolve(name: String, numberHint: String): Triple<ContactMatch?, String, Boolean> {
        if (numberHint.isNotBlank()) {
            val clean = numberHint.filter { it.isDigit() || it == '+' }
            return Triple(ContactMatch(name.ifBlank { clean }, clean, 100), clean, false)
        }
        if (!contacts.hasPermission()) return Triple(null, "", true)
        val matches = contacts.find(name)
        if (matches.isEmpty()) return Triple(null, "", false)
        val best = contacts.best(name)
        return if (best != null) Triple(best, best.number, false) else Triple(matches.first(), "", true)
    }

    private fun callContact(name: String, number: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, "Call permission chahiye.")
        }
        val (match, resolved, ambiguous) = resolve(name, number)
        if (ambiguous) return ActionResult(false, "Ek se zyada contacts mile. Poora naam bataiye.")
        if (match == null || resolved.isBlank()) return ActionResult(false, "\"$name\" contacts me nahi mila.")

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(resolved)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, "${match.name} ko call lag raha hai.")
        } catch (e: SecurityException) {
            ActionResult(false, "Call permission block hai.")
        }
    }

    private fun whatsapp(name: String, number: String, message: String): ActionResult {
        val (match, resolved, ambiguous) = resolve(name, number)
        if (ambiguous) return ActionResult(false, "Ek se zyada contacts mile. Poora naam bataiye.")
        if (match == null || resolved.isBlank()) return ActionResult(false, "\"$name\" contacts me nahi mila.")
        val digits = resolved.filter { it.isDigit() }
        val text = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=$text"))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, "${match.name} ke liye WhatsApp draft khul gaya. Send aapko dabana hai.")
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "WhatsApp install nahi hai. SMS ya share use karein.")
        }
    }

    private fun sms(name: String, number: String, message: String): ActionResult {
        val (match, resolved, ambiguous) = resolve(name, number)
        if (ambiguous) return ActionResult(false, "Ek se zyada contacts mile. Poora naam bataiye.")
        if (match == null || resolved.isBlank()) return ActionResult(false, "\"$name\" contacts me nahi mila.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(resolved)}"))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, "SMS draft khul gaya. Send aapko dabana hai.")
    }

    // ------------------------------------------------------------- system

    private fun volume(direction: String, percent: Int): ActionResult {
        val stream = AudioManager.STREAM_MUSIC
        return when (direction.lowercase()) {
            "up", "badhao", "increase" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Volume badha diya (${currentVolume()}%).")
            }
            "down", "kam", "decrease" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Volume kam kar diya (${currentVolume()}%).")
            }
            "mute" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Mute kar diya.")
            }
            "set" -> {
                val max = audio.getStreamMaxVolume(stream)
                val target = (max * percent.coerceIn(0, 100) / 100f).toInt()
                audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Volume $percent% par set hai.")
            }
            else -> ActionResult(false, "Volume direction samajh nahi aayi.")
        }
    }

    private fun currentVolume(): Int {
        val stream = AudioManager.STREAM_MUSIC
        return audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    }

    private fun torch(on: Boolean): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, "Torch ke liye Camera permission chahiye.")
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cam ->
            cm.getCameraCharacteristics(cam).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult(false, "Is phone me flash torch nahi hai.")
        cm.setTorchMode(id, on)
        return ActionResult(true, if (on) "Torch on." else "Torch off.")
    }

    private fun openSettings(screen: String): ActionResult {
        val s = screen.lowercase()
        val intent = when {
            s.contains("wifi") -> Intent(Settings.ACTION_WIFI_SETTINGS)
            s.contains("bluetooth") -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            s.contains("battery") -> Intent(Intent.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            s.contains("accessibility") -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            s.contains("app") -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            s.contains("display") -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            s.contains("sound") -> Intent(Settings.ACTION_SOUND_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Settings khol diye.")
    }

    private fun reminder(title: String, whenText: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, "Reminder ke liye Calendar permission chahiye.")
        }
        val begin = System.currentTimeMillis() + 15 * 60 * 1000
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title.ifBlank { "NEO reminder" })
            .putExtra(CalendarContract.Events.DESCRIPTION, whenText.ifBlank { "NEO AI se banaya gaya" })
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 30 * 60 * 1000)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, "Calendar 15 min aage ka reminder le kar khula. Save aapko dabana hai.")
    }

    private fun alarm(hour: Int, minute: Int): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, "Alarm ${"%02d:%02d".format(hour, minute)} par set karne ki screen khol di.")
    }

    private fun accessibilityAction(action: String, text: String): ActionResult {
        val service = NeoAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service ON nahi hai. Settings > Accessibility me NEO ko enable karein.")
        if (!service.userEnabledAutomation) {
            return ActionResult(false, "Automation user ne OFF kiya hua hai. Main screen se ON karein.")
        }
        return when (action.lowercase()) {
            "back" -> ActionResult(service.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "Back.")
            "home" -> ActionResult(service.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), "Home.")
            "recents" -> ActionResult(service.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS), "Recents.")
            "notifications" -> ActionResult(
                service.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
                "Notifications."
            )
            "click" -> {
                val done = service.clickText(text)
                ActionResult(done, if (done) "Tap kiya: $text" else "\"$text\" screen par nahi mila.")
            }
            "type" -> ActionResult(service.typeText(text), "Text type kar diya (agar field focused thi).")
            "scroll" -> ActionResult(service.scroll(true), "Scroll kiya.")
            else -> ActionResult(false, "Accessibility action samajh nahi aayi.")
        }
    }

    // ------------------------------------------------------------ local NLU

    /** Handles safe, deterministic commands without the backend. */
    fun tryLocal(text: String): ActionResult? {
        val t = text.lowercase().trim()
        val wordOn = Regex("\\b(torch|flashlight|flash)\\b").containsMatchIn(t)
        return when {
            wordOn && Regex("\\b(off|band|bujha|बंद)").containsMatchIn(t) -> torch(false)
            wordOn -> torch(true)

            Regex("\\b(volume|awaaz|aawaz|sound)\\b").containsMatchIn(t) &&
                Regex("\\b(badha|badhao|up|increase|tez)").containsMatchIn(t) -> volume("up", 0)

            Regex("\\b(volume|awaaz|aawaz|sound)\\b").containsMatchIn(t) &&
                Regex("\\b(kam|down|decrease|dheere)").containsMatchIn(t) -> volume("down", 0)

            Regex("\\b(volume|awaaz|aawaz)\\b").containsMatchIn(t) &&
                Regex("\\bmute\\b").containsMatchIn(t) -> volume("mute", 0)

            Regex("\\b(samay|time|kya baja)").containsMatchIn(t) -> ActionResult(true, timeText())
            Regex("\\b(battery|charge)\\b").containsMatchIn(t) -> ActionResult(true, batteryText())

            Regex("\\b(settings|setting)\\b").containsMatchIn(t) &&
                Regex("\\b(kholo|khol|open)").containsMatchIn(t) -> openSettings(t)

            Regex("\\b(youtube|instagram|whatsapp|telegram|spotify|chrome|gmail|maps|netflix|snapchat|facebook|camera|gallery|clock|calendar)\\b")
                .containsMatchIn(t) &&
                Regex("\\b(kholo|khol|open|launch)").containsMatchIn(t) &&
                !Regex("\\b(search|dhoondo|dhoondh|profile|par|pe)\\b").containsMatchIn(t) -> {
                val app = Regex("\\b(youtube|instagram|whatsapp|telegram|spotify|chrome|gmail|maps|netflix|snapchat|facebook|camera|gallery|clock|calendar)\\b")
                    .find(t)?.value.orEmpty()
                openApp(app)
            }
            else -> null
        }
    }

    private fun timeText(): String {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "Abhi ${fmt.format(Date())} baje hain."
    }

    private fun batteryText(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (Build.VERSION.SDK_INT >= 23) bm.isCharging else false
        return "Battery $level%${if (charging) " (charging)" else ""}."
    }

    fun batteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun start(intent: Intent, okMessage: String): ActionResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ActionResult(true, okMessage)
    } catch (e: ActivityNotFoundException) {
        ActionResult(false, "Koi app is action ko handle nahi karta.")
    } catch (e: SecurityException) {
        ActionResult(false, "Permission ki wajah se action block hua.")
    }

    @Suppress("unused")
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
