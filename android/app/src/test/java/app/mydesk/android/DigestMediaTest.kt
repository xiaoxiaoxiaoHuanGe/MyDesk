package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test

class DigestMediaTest {
    @Test fun serverCopyUsesOnlyPrivateRelativePathsOnTheConfiguredOrigin() {
        val path="/api/x-digest/media/"+"a".repeat(64)
        val request=digestMediaRequest("https://desk.example", "mydesk_session=private",path)
        assertEquals("https://desk.example"+path,request?.url.toString())
        assertEquals("mydesk_session=private",request?.header("Cookie"))
        assertNull(digestMediaRequest("https://desk.example","mydesk_session=private","https://other.example"+path))
        assertNull(digestMediaRequest("https://desk.example","mydesk_session=private",path+"?to=elsewhere"))
        assertNull(digestMediaRequest("https://desk.example","",path))
    }
}
