package com.rishi.vigil

import android.Manifest
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Tools {

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
        "required":["title","start"]}}}
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
}