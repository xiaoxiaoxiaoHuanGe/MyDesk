package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PushMessageTest {
    private val now=Instant.parse("2026-10-02T08:00:00Z")
    private fun data()=mapOf("event_id" to "a".repeat(32),"server_id" to "b".repeat(32),"device_id" to "12345678-1234-1234-1234-123456789012",
        "registration_id" to "d".repeat(32),"kind" to "alert","channel" to "alerts","title" to "服务器","body" to "服务器离线","reference" to "server1","revision" to "down",
        "created_at" to now.minusSeconds(1).toString(),"expires_at" to now.plusSeconds(3599).toString())
    @Test fun requiresBoundSourceAndRejectsExpiredOrMalformedMessages() {
        val binding=PushBinding("b".repeat(32),"12345678-1234-1234-1234-123456789012","d".repeat(32))
        assertNotNull(PushMessage.parse(data(),binding,now))
        assertNull(PushMessage.parse(data(),binding.copy(serverId="c".repeat(32)),now))
        assertNull(PushMessage.parse(data(),binding.copy(deviceId="other"),now))
        assertNull(PushMessage.parse(data(),binding.copy(registrationId="e".repeat(32)),now))
        assertNull(PushMessage.parse(data()+mapOf("expires_at" to now.toString()),binding,now))
        assertNull(PushMessage.parse(data()+mapOf("created_at" to now.plusSeconds(600).toString()),binding,now))
        assertNull(PushMessage.parse(data()+mapOf("channel" to "unknown"),binding,now))
        assertNull(PushMessage.parse(data()+mapOf("event_id" to "../../bad"),binding,now))
        assertNull(PushMessage.parse(data()+mapOf("title" to "x".repeat(121)),binding,now))
        assertNull(PushMessage.parse(data()+mapOf("kind" to "unknown"),binding,now))
    }
}
