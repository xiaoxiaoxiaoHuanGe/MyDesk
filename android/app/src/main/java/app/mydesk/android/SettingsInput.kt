package app.mydesk.android

import kotlinx.serialization.json.*
import java.time.ZoneId

object SettingsInput {
    const val DEFAULT_TASK_TIMEOUT_HOURS=36
    fun mailProxy(enabled: Boolean,host: String,port: String): JsonElement {
        if(!enabled) return JsonNull
        val address=host.trim().removeSurrounding("[","]")
        val ipv6=address.contains(":") && runCatching {java.net.URI("http://[$address]").host!=null}.getOrDefault(false)
        require(address.length in 1..253 && (address.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*")) || ipv6)) {"代理地址填写 IP 或域名，不包含协议和路径"}
        val number=port.trim().toIntOrNull()
        require(number!=null && number in 1..65535) {"代理端口需要在 1–65535 之间"}
        return buildJsonObject {put("host",address);put("port",number)}
    }
    fun basic(zone: String,days: String): JsonObject {
        val timezone=zone.trim()
        runCatching {ZoneId.of(timezone)}.getOrElse {throw IllegalArgumentException("请选择有效的工作台时区")}
        val retention=days.trim().toIntOrNull()
        require(retention != null && retention in 1..365) {"历史保留天数需要在 1–365 之间"}
        return buildJsonObject {put("timezone",timezone);put("history_days",retention)}
    }
    private fun lines(text: String)=text.lineSequence().map {it.trim()}.filter {it.isNotEmpty()}.toList()
    fun network(enabled: Boolean,text: String): JsonObject {
        val nodes=lines(text).mapIndexed {index,line ->
            val parts=line.split('=').map {it.trim()}
            require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].matches(Regex("[a-zA-Z0-9:.\\-]+")) && !parts[1].startsWith('-')) {"节点第 ${index+1} 行需要「名称=域名或 IP」"}
            parts[0] to parts[1]
        }
        return network(enabled,nodes)
    }
    fun network(enabled: Boolean,values: List<Pair<String,String>>): JsonObject {
        val names=mutableSetOf<String>()
        val nodes=values.mapIndexed {index,(rawName,rawHost)->
            val name=rawName.trim();val host=rawHost.trim()
            require(name.isNotEmpty() && host.matches(Regex("[a-zA-Z0-9:.\\-]+")) && !host.startsWith('-')) {"请填写节点 ${index+1} 的名称和有效地址"}
            require(names.add(name)) {"节点名称不能重复"}
            buildJsonObject {put("name",name);put("host",host)}
        }
        require(nodes.size <= 20) {"最多配置 20 个网络节点"}
        return buildJsonObject {put("network",buildJsonObject {put("enabled",enabled);put("nodes",JsonArray(nodes))})}
    }
    fun taskTimeout(text: String): Double {
        val value=text.trim()
        if(value.isEmpty()) return DEFAULT_TASK_TIMEOUT_HOURS.toDouble()
        val hours=value.toDoubleOrNull()
        require(hours != null && hours.isFinite() && hours in 1.0..8760.0) {"超期时间需要为 1–8760 小时"}
        return hours
    }
}
