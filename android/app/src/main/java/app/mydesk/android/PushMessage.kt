package app.mydesk.android

import java.time.Instant

data class PushBinding(val serverId: String,val deviceId: String,val registrationId: String="")
data class PushMessage(val eventId: String,val serverId: String,val deviceId: String,val kind: String,val channel: String,
    val title: String,val body: String,val reference: String,val revision: String,val createdAt: Instant,val expiresAt: Instant,val registrationId: String="") {
    companion object {
        fun parse(data: Map<String,String>,binding: PushBinding?,now: Instant): PushMessage?=runCatching {
            require(binding != null && binding.serverId.isNotBlank() && binding.deviceId.isNotBlank() && binding.registrationId.isNotBlank())
            fun value(name: String,max: Int): String=(data[name] ?: error("缺少字段")).also { require(it.length <= max && it.none { ch -> ch == '\u0000' }) }
            val event=value("event_id",32).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
            val server=value("server_id",32).also { require(it == binding.serverId) }
            val device=value("device_id",36).also { require(it == binding.deviceId) }
            val registration=value("registration_id",32).also {require(it == binding.registrationId)}
            val kind=value("kind",20).also { require(it in setOf("alert","task","test","sync","reminder","inbox")) }
            val channel=value("channel",20).also { require(it in setOf("reminders","alerts","tasks","inbox")) }
            require((kind=="inbox")== (channel=="inbox"))
            val created=Instant.parse(value("created_at",40))
            val expires=Instant.parse(value("expires_at",40))
            require(expires > now && expires > created && created <= now.plusSeconds(300) && expires <= created.plusSeconds(3600))
            PushMessage(event,server,device,kind,channel,value("title",120).also {require(it.isNotBlank())},value("body",500),value("reference",200),value("revision",200),created,expires,registration)
        }.getOrNull()
    }
}
