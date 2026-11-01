package com.rishi.vigil

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object Tools {

    // Tools that use the internet → must run off the main thread
    val network = setOf("weather", "web_search")

    val schema = JSONArray("""
    [
      {"type":"function","function":{"name":"set_alarm",
        "description":"Set an alarm at a specific time. Use the 24-hour clock.",
        "parameters":{"type":"object","properties":{
          "hour":{"type":"integer","description":"0-23"},
          "minute":{"type":"integer","description":"0-59"},
          "label":{"type":"string"}},
        "required":["hour","minute"]}}},

      {"type":"function","function":{"name":"set_timer",
        "description":"Start a countdown timer.",
        "parameters":{"type":"object","properties":{
          "seconds":{"type":"integer","description":"Total duration in seconds"},
          "label":{"type":"string"}},
        "required":["seconds"]}}},

      {"type":"function","function":{"name":"open_app",
        "description":"Open an installed app by its name.",
        "parameters":{"type":"object","properties":{
          "name":{"type":"string","description":"App name, e.g. YouTube"}},
        "required":["name"]}}},

      {"type":"function","function":{"name":"torch",
        "description":"Turn the flashlight on or off.",
        "parameters":{"type":"object","properties":{
          "on":{"type":"boolean"}},
        "required":["on"]}}},

      {"type":"function","function":{"name":"call_contact",
        "description":"Phone call a contact by name, or a phone number.",
        "parameters":{"type":"object","properties":{
          "name":{"type":"string","description":"Contact name as saved, or a phone number"}},
        "required":["name"]}}},

      {"type":"function","function":{"name":"send_sms",
        "description":"Send an SMS to a contact by name, or a phone number.",
        "parameters":{"type":"object","properties":{
          "name":{"type":"string","description":"Contact name as saved, or a phone number"},
          "message":{"type":"string","description":"The text to send"}},
        "required":["name","message"]}}},

      {"type":"function","function":{"name":"set_volume",
        "description":"Set a volume level. Use percent 0 to mute.",
        "parameters":{"type":"object","properties":{
          "stream":{"type":"string","enum":["media","ring","alarm","notification"]},
          "percent":{"type":"integer","description":"0-100"}},
        "required":["stream","percent"]}}},

      {"type":"function","function":{"name":"ringer_mode",
        "description":"Set the phone ringer to normal, vibrate, or silent.",
        "parameters":{"type":"object","properties":{
          "mode":{"type":"string","enum":["normal","vibrate","silent"]}},
        "required":["mode"]}}},

      {"type":"function","function":{"name":"do_not_disturb",
        "description":"Turn Do Not Disturb on or off.",
        "parameters":{"type":"object","properties":{
          "on":{"type":"boolean"}},
        "required":["on"]}}},

      {"type":"function","function":{"name":"brightness",
        "description":"Set screen brightness.",
        "parameters":{"type":"object","properties":{
          "percent":{"type":"integer","description":"0-100"}},
        "required":["percent"]}}},

      {"type":"function","function":{"name":"get_events",
        "description":"List calendar events starting on a date.",
        "parameters":{"type":"object","properties":{
          "date":{"type":"string","description":"Start date, format YYYY-MM-DD"},
          "days":{"type":"integer","description":"Number of days to include, default 1"}},
        "required":["date"]}}},

      {"type":"function","function":{"name":"add_event",
        "description":"Add an event to the calendar.",
        "parameters":{"type":"object","properties":{
          "title":{"type":"string"},
          "start":{"type":"string","description":"Local start time, format YYYY-MM-DDTHH:MM"},
          "duration_minutes":{"type":"integer","description":"Default 60"}},
        "required":["title","start"]}}},

      {"type":"function","function":{"name":"weather",
        "description":"Get current weather and daily forecast. Omit city to use the user's current location.",
        "parameters":{"type":"object","properties":{
          "city":{"type":"string","description":"City name, e.g. Bengaluru. Omit for current location"},
          "days":{"type":"integer","description":"Forecast days 1-7, default 2"}},
        "required":[]}}},

      {"type":"function","function":{"name":"web_search",
        "description":"Search the web for current information: news, sports scores, prices, recent events, or facts you are unsure about.",
        "parameters":{"type":"object","properties":{
          "query":{"type":"string"}},
        "required":["query"]}}},

      {"type":"function","function":{"name":"remember",
        "description":"Save a fact the user asks you to remember long-term. Write it as one short self-contained sentence with exact dates, e.g. 'Bike service is on 5 Nov 2026'.",
        "parameters":{"type":"object","properties":{
          "fact":{"type":"string"}},
        "required":["fact"]}}},

      {"type":"function","function":{"name":"forget",
        "description":"Delete saved facts that mention a keyword. Use 'everything' to delete all saved facts.",
        "parameters":{"type":"object","properties":{
          "about":{"type":"string","description":"Keyword, e.g. 'bike service', or 'everything'"}},
        "required":["about"]}}}
    ]
    """)

    private val zone = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

    fun run(ctx: Context, name: String, args: JSONObject): String = runCatching {
        when (name) {
            "set_alarm" -> {
                val h = args.getInt("hour"); val m = args.getInt("minute")
                ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Vigil"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Alarm set for %02d:%02d".format(h, m)
            }

            "set_timer" -> {
                val s = args.getInt("seconds")
                ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Vigil"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Timer started for $s seconds"
            }

            "open_app" -> {
                val query = args.getString("name").lowercase().trim()
                val pm = ctx.packageManager
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == query }
                    ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(query) }
                    ?: return@runCatching "No installed app named '$query'"
                val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                    ?: return@runCatching "Can't open ${match.loadLabel(pm)}"
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened ${match.loadLabel(pm)}"
            }

            "torch" -> {
                val on = args.getBoolean("on")
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.first {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                cm.setTorchMode(id, on)
                "Flashlight ${if (on) "on" else "off"}"
            }

            "call_contact" -> {
                check(ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    "Phone permission not granted. Open the Vigil app, step 1."
                }
                val (contact, number) = findContact(ctx, args.getString("name"))
                ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Calling $contact"
            }

            "send_sms" -> {
                check(ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    "SMS permission not granted. Open the Vigil app, step 1."
                }
                val (contact, number) = findContact(ctx, args.getString("name"))
                val text = args.getString("message")
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
                "Message sent to $contact: $text"
            }

            "set_volume" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val streamName = args.getString("stream")
                val stream = when (streamName) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> AudioManager.STREAM_MUSIC
                }
                val pct = args.getInt("percent").coerceIn(0, 100)
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream) * pct / 100, AudioManager.FLAG_SHOW_UI)
                "$streamName volume set to $pct percent"
            }

            "ringer_mode" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val mode = args.getString("mode")
                if (mode == "silent") check(ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                    "Silent mode needs Do Not Disturb access. Open the Vigil app, step 3."
                }
                am.ringerMode = when (mode) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                "Phone set to $mode"
            }

            "do_not_disturb" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                check(nm.isNotificationPolicyAccessGranted) {
                    "Do Not Disturb access not granted. Open the Vigil app, step 3."
                }
                val on = args.getBoolean("on")
                nm.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL)
                "Do Not Disturb ${if (on) "on" else "off"}"
            }

            "brightness" -> {
                check(Settings.System.canWrite(ctx)) { "Brightness access not granted. Open the Vigil app, step 4." }
                val pct = args.getInt("percent").coerceIn(0, 100)
                val cr = ctx.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceAtLeast(1))
                "Brightness set to $pct percent"
            }

            "get_events" -> {
                check(ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                    "Calendar permission not granted. Open the Vigil app, step 1."
                }
                val day = LocalDate.parse(args.getString("date"))
                val days = args.optInt("days", 1).coerceIn(1, 14)
                val begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
                val uri = Instances.CONTENT_URI.buildUpon()
                    .also { ContentUris.appendId(it, begin); ContentUris.appendId(it, end) }.build()

                val events = mutableListOf<String>()
                ctx.contentResolver.query(uri, arrayOf(Instances.TITLE, Instances.BEGIN, Instances.ALL_DAY),
                    null, null, "${Instances.BEGIN} ASC")
                    ?.use { c ->
                        while (c.moveToNext()) {
                            val start = Instant.ofEpochMilli(c.getLong(1)).atZone(zone)
                            events += if (c.getInt(2) == 1) "${c.getString(0)} (all day)"
                            else "${c.getString(0)} at ${start.format(timeFmt)}"
                        }
                    }
                if (events.isEmpty()) "No events" else events.joinToString("; ")
            }

            "add_event" -> {
                check(ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                    "Calendar permission not granted. Open the Vigil app, step 1."
                }
                val title = args.getString("title")
                val start = LocalDateTime.parse(args.getString("start")).atZone(zone)
                val end = start.plusMinutes(args.optLong("duration_minutes", 60))
                val calId = primaryCalendar(ctx) ?: error("No writable calendar found")
                ctx.contentResolver.insert(Events.CONTENT_URI, ContentValues().apply {
                    put(Events.CALENDAR_ID, calId)
                    put(Events.TITLE, title)
                    put(Events.DTSTART, start.toInstant().toEpochMilli())
                    put(Events.DTEND, end.toInstant().toEpochMilli())
                    put(Events.EVENT_TIMEZONE, zone.id)
                })
                "Added '$title' on ${start.format(timeFmt)}"
            }

            "weather" -> {
                val city = args.optString("city").trim()
                val days = args.optInt("days", 2).coerceIn(1, 7)

                // 1. Where? City name → coordinates, or the phone's location
                val (lat, lon, place) = if (city.isNotEmpty()) {
                    val r = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
                            URLEncoder.encode(city, "UTF-8")))
                        .optJSONArray("results")?.optJSONObject(0) ?: error("Couldn't find a place called $city")
                    Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.getString("name"))
                } else {
                    val loc = lastLocation(ctx)
                    Triple(loc.latitude, loc.longitude, "your location")
                }

                // 2. Current conditions + daily forecast
                val w = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                        "&timezone=auto&forecast_days=$days"))
                val cur = w.getJSONObject("current")
                val d = w.getJSONObject("daily")
                val forecast = (0 until days).joinToString("; ") { i ->
                    "${d.getJSONArray("time").getString(i)}: ${sky(d.getJSONArray("weather_code").getInt(i))}, " +
                            "${d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()} to " +
                            "${d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()} degrees, " +
                            "${d.getJSONArray("precipitation_probability_max").optInt(i)} percent chance of rain"
                }
                "Weather at $place now: ${cur.getDouble("temperature_2m").roundToInt()} degrees, " +
                        "feels like ${cur.getDouble("apparent_temperature").roundToInt()}, ${sky(cur.getInt("weather_code"))}, " +
                        "humidity ${cur.getInt("relative_humidity_2m")} percent, wind ${cur.getDouble("wind_speed_10m").roundToInt()} km/h. " +
                        "Forecast: $forecast"
            }

            "web_search" -> {
                val body = JSONObject()
                    .put("query", args.getString("query"))
                    .put("search_depth", "basic")          // 1 credit per search
                    .put("max_results", 3)
                    .put("include_answer", true)
                val res = JSONObject(httpPost("https://api.tavily.com/search", body.toString(),
                    "Bearer ${BuildConfig.TAVILY_API_KEY}"))
                val results = res.optJSONArray("results") ?: JSONArray()
                val snippets = (0 until results.length()).joinToString("\n") { i ->
                    results.getJSONObject(i).let { "- ${it.optString("title")}: ${it.optString("content").take(300)}" }
                }
                "Answer: ${res.optString("answer")}\nSources:\n$snippets"
            }

            "remember" -> {
                val fact = args.getString("fact").trim()
                val facts = Memory.all(ctx).toMutableList()
                if (facts.none { it.equals(fact, ignoreCase = true) }) {
                    facts += fact
                    Memory.save(ctx, facts.takeLast(100))              // keep the latest 100
                }
                "Saved: $fact"
            }

            "forget" -> {
                val about = args.getString("about").trim().lowercase()
                val facts = Memory.all(ctx)
                if (about == "everything" || about == "all") {
                    Memory.save(ctx, emptyList())
                    return@runCatching "Forgot everything (${facts.size} facts)"
                }
                val keep = facts.filterNot { it.lowercase().contains(about) }
                Memory.save(ctx, keep)
                if (keep.size == facts.size) "Nothing saved about $about"
                else "Forgot ${facts.size - keep.size} fact(s) about $about"
            }

            else -> "Unknown tool: $name"
        }
    }.getOrElse { "Failed: ${it.message}" }

    // Name → (saved name, number). A spoken number is used directly.
    private fun findContact(ctx: Context, query: String): Pair<String, String> {
        val q = query.trim()
        if (q.none { it.isLetter() } && q.count { it.isDigit() } >= 6) return q to q

        check(ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            "Contacts permission not granted. Open the Vigil app, step 1."
        }
        val found = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { c -> while (c.moveToNext()) found += c.getString(0) to c.getString(1) }

        return found.firstOrNull { it.first.equals(q, ignoreCase = true) }
            ?: found.firstOrNull()
            ?: error("No contact named $q")
    }

    // The user's main writable calendar (primary Google calendar if present)
    private fun primaryCalendar(ctx: Context): Long? {
        ctx.contentResolver.query(
            Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.IS_PRIMARY),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE} = 1",
            null, null
        )?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) return c.getLong(0)
                if (first == null) first = c.getLong(0)
            }
            return first
        }
        return null
    }

    // Most recent known location from any provider (permission checked first)
    @SuppressLint("MissingPermission")
    private fun lastLocation(ctx: Context): Location {
        check(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "Location permission not granted. Open the Vigil app, step 1."
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        return listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: error("Couldn't get your location. Turn on location, or say a city name.")
    }

    // WMO weather code → words
    private fun sky(code: Int) = when (code) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "rain showers"
        85, 86 -> "snow showers"
        in 95..99 -> "thunderstorm"
        else -> "unknown conditions"
    }

    private fun httpGet(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000; readTimeout = 10_000
            check(responseCode in 200..299) { "HTTP $responseCode" }
            inputStream.bufferedReader().use { it.readText() }
        }

    private fun httpPost(url: String, json: String, auth: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", auth)
            outputStream.use { it.write(json.toByteArray()) }
            check(responseCode in 200..299) { "HTTP $responseCode" }
            inputStream.bufferedReader().use { it.readText() }
        }
}