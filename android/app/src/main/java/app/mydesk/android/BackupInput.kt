package app.mydesk.android

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.InputStream
import java.io.ByteArrayOutputStream

internal object BackupInput {
    const val MAX_BYTES=128*1024
    fun read(stream: InputStream): JsonObject {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val count=stream.read(buffer,0,minOf(buffer.size,MAX_BYTES+1-output.size()))
            if(count<0) break
            output.write(buffer,0,count)
            require(output.size()<=MAX_BYTES) {"备份文件超过 128 KB"}
        }
        return file(output.toByteArray())
    }
    fun password(value: String,confirmation: String=value) {
        require(value.length in 12..256) {"备份密码需要 12–256 个字符"}
        require(value==confirmation) {"两次备份密码不一致"}
    }
    fun file(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..MAX_BYTES) {"备份文件为空或超过 128 KB"}
        return try {
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val result=Json.parseToJsonElement(decoded).jsonObject
            require(result.text("format")=="mydesk-config-backup" && result["version"]?.jsonPrimitive?.intOrNull==1 && result.text("ciphertext").isNotBlank())
            result
        } catch(error: Exception) {throw IllegalArgumentException("请选择有效的 MyDesk 加密备份文件")}
    }
    fun filename(value: String)=value.takeIf {it.matches(Regex("MyDesk-[A-Za-z0-9-]+\\.mydesk"))} ?: "MyDesk-backup.mydesk"
}
