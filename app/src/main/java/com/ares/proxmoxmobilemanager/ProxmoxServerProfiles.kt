package com.ares.proxmoxmobilemanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ProxmoxServerProfile(
    val name: String,
    val connection: ProxmoxConnection
)

object ProxmoxServerProfiles {
    private const val PREFS = "proxmox_server_profiles"
    private const val KEY = "profiles"

    fun load(context: Context): List<ProxmoxServerProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(ProxmoxServerProfile(o.optString("name"), ProxmoxConnection(
                    o.optString("localUrl"), o.optString("remoteUrl"), o.optString("username"),
                    o.optString("password"), o.optString("tokenId"), o.optString("tokenSecret")
                )))
            }
        }
    }

    fun save(context: Context, profile: ProxmoxServerProfile) {
        val profiles = load(context).filterNot { it.name == profile.name }.toMutableList()
        profiles.add(profile)
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("localUrl", p.connection.localUrl)
                put("remoteUrl", p.connection.remoteUrl)
                put("username", p.connection.username)
                put("password", p.connection.password)
                put("tokenId", p.connection.tokenId)
                put("tokenSecret", p.connection.tokenSecret)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun delete(context: Context, name: String) {
        val arr = JSONArray()
        load(context).filterNot { it.name == name }.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("localUrl", p.connection.localUrl)
                put("remoteUrl", p.connection.remoteUrl)
                put("username", p.connection.username)
                put("password", p.connection.password)
                put("tokenId", p.connection.tokenId)
                put("tokenSecret", p.connection.tokenSecret)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
