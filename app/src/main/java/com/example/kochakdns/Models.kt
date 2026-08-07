package com.example.kochakdns

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class DnsServer(
    val role: String,
    val priority: Int,
    val family: String,
    val address: String
)

data class DnsProfile(
    val name: String,
    val enabled: Boolean,
    val ipv4Primary: String?,
    val ipv6Primary: String?,
    val ipv4Secondary: String?,
    val ipv6Secondary: String?,
    val servers: List<DnsServer>,
    val updatedAt: String?
) {
    fun toJson(): String {
        val serversArray = JSONArray()
        servers.forEach { s ->
            serversArray.put(JSONObject().apply {
                put("role", s.role)
                put("priority", s.priority)
                put("family", s.family)
                put("address", s.address)
            })
        }
        return JSONObject().apply {
            put("name", name)
            put("enabled", enabled)
            put("ipv4Primary", ipv4Primary ?: JSONObject.NULL)
            put("ipv6Primary", ipv6Primary ?: JSONObject.NULL)
            put("ipv4Secondary", ipv4Secondary ?: JSONObject.NULL)
            put("ipv6Secondary", ipv6Secondary ?: JSONObject.NULL)
            put("updatedAt", updatedAt ?: JSONObject.NULL)
            put("servers", serversArray)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): DnsProfile {
            val obj = JSONObject(json)
            val serversArray = obj.optJSONArray("servers")
            val servers = mutableListOf<DnsServer>()
            if (serversArray != null) {
                for (i in 0 until serversArray.length()) {
                    val s = serversArray.getJSONObject(i)
                    servers.add(
                        DnsServer(
                            role = s.optString("role"),
                            priority = s.optInt("priority"),
                            family = s.optString("family"),
                            address = s.optString("address")
                        )
                    )
                }
            }
            return DnsProfile(
                name = obj.optString("name"),
                enabled = obj.optBoolean("enabled"),
                ipv4Primary = obj.optString("ipv4Primary").takeIf { it.isNotEmpty() && it != "null" },
                ipv6Primary = obj.optString("ipv6Primary").takeIf { it.isNotEmpty() && it != "null" },
                ipv4Secondary = obj.optString("ipv4Secondary").takeIf { it.isNotEmpty() && it != "null" },
                ipv6Secondary = obj.optString("ipv6Secondary").takeIf { it.isNotEmpty() && it != "null" },
                servers = servers,
                updatedAt = obj.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" }
            )
        }
    }
}

data class DnsItem(
    val name: String,
    val servers: List<DnsServer>,
    val ping: Long = -1,
    val previousPing: Long = -1
) {
    val jitter: Long
        get() = if (ping > 0 && previousPing > 0) abs(ping - previousPing) else 0
}

object VpnStats {
    val totalBytesSent = AtomicLong(0)
    val totalBytesReceived = AtomicLong(0)
    val totalPacketsSent = AtomicLong(0)
    val totalPacketsLost = AtomicLong(0)
    
    @Volatile
    var isVpnActive = false
    
    @Volatile
    var activeDnsName: String? = null
}
