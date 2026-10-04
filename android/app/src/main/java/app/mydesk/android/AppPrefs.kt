package app.mydesk.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.UUID

private val Context.deskPrefs by preferencesDataStore("mydesk")
data class PhonePrefs(val server: String="",val device: String="",val theme: String="system",val deviceName: String="")
class AppPrefs(val context: Context,private val store: DataStore<Preferences> = context.deskPrefs) {
    private val serverKey=stringPreferencesKey("server")
    private val deviceKey=stringPreferencesKey("device")
    private val themeKey=stringPreferencesKey("theme")
    private val nameKey=stringPreferencesKey("device_name")
    private val defaultName=Build.MODEL.take(80).ifBlank {"Android 手机"}
    val flow=store.data.map { PhonePrefs(it[serverKey] ?: "",it[deviceKey] ?: "",it[themeKey] ?: "system",it[nameKey] ?: defaultName) }
    suspend fun initialize() { store.edit { if (it[deviceKey].isNullOrEmpty()) it[deviceKey]=UUID.randomUUID().toString();if(it[nameKey].isNullOrEmpty()) it[nameKey]=defaultName } }
    suspend fun server(value: String) { store.edit { it[serverKey]=InputRules.server(value) } }
    suspend fun theme(value: String) { require(value in setOf("system","light","dark")); store.edit { it[themeKey]=value } }
    suspend fun deviceName(value: String) {val name=InputRules.deviceName(value);store.edit {it[nameKey]=name}}
}

/** Every server has a separate encrypted session. Editing the address cannot send an old cookie to a different host. */
class SecureVault(context: Context,base: String): SessionVault {
    private val prefs=context.getSharedPreferences("secure-session",Context.MODE_PRIVATE)
    private val namespace=MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).joinToString("") { "%02x".format(it) }
    private val cipher: SessionCipher
    init {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key=(store.getKey("mydesk-session",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mydesk-session",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        cipher=SessionCipher(key)
    }
    @Synchronized private fun read(name: String): String {
        val value=prefs.getString("$namespace:$name",null) ?: return ""
        return runCatching { cipher.decrypt(value) }.getOrElse { prefs.edit().remove("$namespace:$name").commit(); "" }
    }
    @Synchronized private fun write(name: String,value: String) {
        val editor=prefs.edit()
        if (value.isEmpty()) editor.remove("$namespace:$name") else editor.putString("$namespace:$name",cipher.encrypt(value))
        check(editor.commit()) { "无法安全保存登录会话" }
    }
    override var cookie: String get()=read("cookie"); set(value)=write("cookie",value)
    override var csrf: String get()=read("csrf"); set(value)=write("csrf",value)
}
