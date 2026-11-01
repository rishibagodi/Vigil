package com.rishi.vigil

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

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
        "required":["name","message"]}}}
    ]
    """)

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
                    "Phone permission not granted. Open the Vigil app to allow it."
                }
                val (contact, number) = findContact(ctx, args.getString("name"))
                ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Calling $contact"
            }

            "send_sms" -> {
                check(ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    "SMS permission not granted. Open the Vigil app to allow it."
                }
                val (contact, number) = findContact(ctx, args.getString("name"))
                val text = args.getString("message")
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
                "Message sent to $contact: $text"
            }

            else -> "Unknown tool: $name"
        }
    }.getOrElse { "Failed: ${it.message}" }

    // Name → (saved name, number). A spoken number is used directly.
    private fun findContact(ctx: Context, query: String): Pair<String, String> {
        val q = query.trim()
        if (q.none { it.isLetter() } && q.count { it.isDigit() } >= 6) return q to q

        check(ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            "Contacts permission not granted. Open the Vigil app to allow it."
        }
        val found = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { c -> while (c.moveToNext()) found += c.getString(0) to c.getString(1) }

        return found.firstOrNull { it.first.equals(q, ignoreCase = true) }   // exact name first
            ?: found.firstOrNull()                                            // then partial match
            ?: error("No contact named $q")
    }
}