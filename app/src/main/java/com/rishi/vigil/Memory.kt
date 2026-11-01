package com.rishi.vigil

import android.content.Context
import org.json.JSONArray

// Long-term facts, stored privately on the phone
object Memory {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vigil_memory", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<String> {
        val arr = JSONArray(prefs(ctx).getString("facts", "[]"))
        return List(arr.length()) { arr.getString(it) }
    }

    fun save(ctx: Context, facts: List<String>) =
        prefs(ctx).edit().putString("facts", JSONArray(facts).toString()).apply()
}