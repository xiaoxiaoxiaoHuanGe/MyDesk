package app.mydesk.android

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class SessionCipherTest {
    @Test fun ciphertextHidesSessionAndDifferentWritesUseDifferentNonces() {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher=SessionCipher(key)
        val value="mydesk_session=private-session"
        val first=cipher.encrypt(value)
        assertFalse(first.contains(value))
        assertEquals(value,cipher.decrypt(first))
        assertNotEquals(first,cipher.encrypt(value))
    }
    @Test fun alteredCiphertextCannotBecomeAnAuthenticatedSession() {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher=SessionCipher(key)
        val encrypted=cipher.encrypt("session")
        val raw=java.util.Base64.getDecoder().decode(encrypted)
        raw[raw.lastIndex]=(raw.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(java.util.Base64.getEncoder().encodeToString(raw)) }.isFailure)
    }
}
