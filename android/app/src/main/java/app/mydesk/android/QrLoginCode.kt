package app.mydesk.android

import java.net.URI
import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrl

data class QrLoginCode(val server: String,val id: String,val token: String) {
    companion object {
        fun parse(raw: String,connectedServer: String): QrLoginCode {
            require(raw.length<=2048) {"请扫描 MyDesk 电脑登录二维码"}
            val uri=runCatching {URI(raw)}.getOrElse {throw IllegalArgumentException("请扫描 MyDesk 电脑登录二维码")}
            require(uri.scheme=="mydesk" && uri.rawAuthority=="login" && uri.path.isNullOrEmpty() && uri.fragment==null) {"请扫描 MyDesk 电脑登录二维码"}
            val values=mutableMapOf<String,String>()
            for(part in (uri.rawQuery ?: "").split('&')) {
                val pair=part.split('=',limit=2)
                require(pair.size==2 && pair[0] in setOf("server","id","token") && !values.containsKey(pair[0])) {"登录二维码格式无效"}
                values[pair[0]]=URLDecoder.decode(pair[1],"UTF-8")
            }
            val server=InputRules.server(values["server"] ?: "")
            require(server.toHttpUrl()==InputRules.server(connectedServer).toHttpUrl()) {"二维码属于其他服务器，请打开当前服务器的电脑登录页"}
            val id=values["id"] ?: "";val token=values["token"] ?: ""
            require(id.matches(Regex("[a-f0-9]{32}")) && token.matches(Regex("[A-Za-z0-9_-]{43}"))) {"登录二维码格式无效"}
            return QrLoginCode(server,id,token)
        }
    }
}

data class QrLoginPreview(val code: QrLoginCode,val browser: String,val expiresIn: Int)
