package app.mydesk.android

import javax.crypto.SecretKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

class SessionCipher(val key: SecretKey) {
    fun encrypt(text: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key)
        return Base64.getEncoder().encodeToString(cipher.iv+cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(text: String): String {
        val bytes=Base64.getDecoder().decode(text)
        require(bytes.size >= 28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
    }
}
