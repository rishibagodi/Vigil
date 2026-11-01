package com.rishi.vigil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Long-term facts, custom words and command history, stored privately on the phone
object Memory {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vigil_memory", Context.MODE_PRIVATE)

    // ── Facts ──
    fun all(ctx: Context): List<String> {
        val arr = JSONArray(prefs(ctx).getString("facts", "[]"))
        return List(arr.length()) { arr.getString(it) }
    }

    fun save(ctx: Context, facts: List<String>) =
        prefs(ctx).edit().putString("facts", JSONArray(facts).toString()).apply()

    // ── Custom words for Whisper ──
    fun words(ctx: Context): String = prefs(ctx).getString("words", "") ?: ""

    fun saveWords(ctx: Context, words: String) =
        prefs(ctx).edit().putString("words", words.trim()).apply()

    // ── Command history ──
    class Entry(val time: Long, val said: String, val tools: String, val reply: String)

    fun log(ctx: Context, said: String, tools: List<String>, reply: String) {
        val arr = JSONArray(prefs(ctx).getString("history", "[]"))
        arr.put(JSONObject()
            .put("time", System.currentTimeMillis())
            .put("said", said)
            .put("tools", tools.joinToString(", "))
            .put("reply", reply))
        val latest = (maxOf(0, arr.length() - 50) until arr.length()).map { arr.get(it) }   // keep last 50
        prefs(ctx).edit().putString("history", JSONArray(latest).toString()).apply()
    }

    fun history(ctx: Context): List<Entry> {
        val arr = JSONArray(prefs(ctx).getString("history", "[]"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Entry(o.getLong("time"), o.getString("said"), o.optString("tools"), o.optString("reply"))
        }.reversed()                                                                        // newest first
    }

    fun clearHistory(ctx: Context) = prefs(ctx).edit().remove("history").apply()
}