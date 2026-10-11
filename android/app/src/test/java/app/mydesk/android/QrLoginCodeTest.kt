package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class QrLoginCodeTest {
    private val server="https://mydesk.example"
    private val valid="mydesk://login?server=${URLEncoder.encode(server,"UTF-8")}&id=${"a".repeat(32)}&token=${"b".repeat(43)}"
    @Test fun parsesOnlyLoginCodesForTheConnectedServer() {
        val code=QrLoginCode.parse(valid,server+"/")
        assertEquals(server,code.server);assertEquals("a".repeat(32),code.id)
        assertTrue(runCatching {QrLoginCode.parse(valid,"https://other.example")}.isFailure)
    }
    @Test fun rejectsForeignUrlsMalformedSecretsAndDuplicateParameters() {
        for(raw in listOf("https://mydesk.example",valid.replace("mydesk://login","mydesk://other"),
            valid+"&server=https%3A%2F%2Fevil.example",valid+"#fragment",valid.replace("id="+"a".repeat(32),"id=bad"),
            valid.replace("token="+"b".repeat(43),"token=short"),valid.replace("https%3A%2F%2Fmydesk.example","http%3A%2F%2Fmydesk.example"))) {
            assertTrue(raw,runCatching {QrLoginCode.parse(raw,server)}.isFailure)
        }
    }
}
