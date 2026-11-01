package com.rishi.vigil

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

object Tools {

    // Tool list sent to the LLM (OpenAI function-calling format)
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
        "required":["on"]}}}
    ]
    """)

    // Runs the tool the LLM picked and returns a result string for the LLM
    fun run(ctx: Context, name: String, args: JSONObject): String = runCatching {
        when (name) {
            "set_alarm" -> {
                val h = args.getInt("hour"); val m = args.getInt("minute")
                ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Vigil"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Alarm set for %02d:%02d".format(h, m)
            }

            "set_timer" -> {
                val s = args.getInt("seconds")
                ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Vigil"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
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
                ctx.startActivity(launch)
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

            else -> "Unknown tool: $name"
        }
    }.getOrElse { "Failed: ${it.message}" }
}
