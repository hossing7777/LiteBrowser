package com.litebrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(c: Context) {
    private val p = c.getSharedPreferences("lite", Context.MODE_PRIVATE)

    fun get(k: String): List<Pair<String, String>> = try {
        val a = JSONArray(p.getString(k, "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("t") to o.getString("u") }
    } catch (e: Exception) { emptyList() }

    private fun put(k: String, l: List<Pair<String, String>>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.first).put("u", it.second)) }
        p.edit().putString(k, a.toString()).apply()
    }

    fun add(k: String, t: String, u: String, max: Int) {
        val l = get(k).filter { it.second != u }.toMutableList()
        l.add(0, t to u); put(k, l.take(max))
    }
    fun remove(k: String, u: String) = put(k, get(k).filter { it.second != u })
    fun has(k: String, u: String) = get(k).any { it.second == u }
    fun clear(k: String) = p.edit().remove(k).apply()
    fun flag(k: String) = p.getBoolean(k, false)
    fun setFlag(k: String) = p.edit().putBoolean(k, true).apply()
    var hormouzApiKey: String
        get() = p.getString("hormouz_api_key", "") ?: ""
        set(v) { p.edit().putString("hormouz_api_key", v).apply() }
        
    var hormouzModel: String
        get() = p.getString("hormouz_model", "nvidia/nemotron-3.5-lightning:free") ?: "nvidia/nemotron-3.5-lightning:free"
        set(v) { p.edit().putString("hormouz_model", v).apply() }
        
    var hormouzMode: String
        get() = p.getString("hormouz_mode", "auto") ?: "auto"
        set(v) { p.edit().putString("hormouz_mode", v).apply() }

    var session: String
        get() = p.getString("session", "") ?: ""
        set(v) { p.edit().putString("session", v).apply() }
}
