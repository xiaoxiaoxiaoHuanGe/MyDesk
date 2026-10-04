package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MultiServiceSettings(mail: Boolean,settings: JsonObject,busy: Boolean,save: (JsonObject,()->Unit)->Unit,onClose: ()->Unit,
    feedback: String="",check: (String,String)->Unit={_,_->}) {
    val collection=if(mail) "gmail_accounts" else "server_sources"
    val entries=settings.obj(collection)
    var editing by remember {mutableStateOf<String?>(null)}
    var draft by remember {mutableStateOf(buildJsonObject {})}
    var removing by remember {mutableStateOf(false)}
    var query by remember {mutableStateOf("")}
    fun back() {editing=null;draft=buildJsonObject {}}
    fun write(value: JsonObject) {
        val key=editing ?: return
        save(buildJsonObject {put(collection,buildJsonObject {entries.forEach {(id,row)->put(id,row)};put(key,value)})}) {back()}
    }
    DeskModalSheet(onDismissRequest={if(!busy) onClose()},dragDismissEnabled=!busy,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(editing!=null) IconButton({back()},enabled=!busy) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回接入列表")}
                Text(if(mail) "Gmail 邮件" else "服务器监控",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onClose,enabled=!busy) {Icon(Icons.Default.Close,"关闭接入管理")}
            }
            if(feedback.isNotBlank()) Text(feedback,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(editing==null) {
                DeskTextField(query,{query=it},label={Text(if(mail) "搜索邮箱名称或地址" else "搜索服务器名称或地址")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("${entries.size} 项接入 · 分别管理凭据",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val visible=entries.entries.filter {(_,item)->val row=item.jsonObject;listOf(row.text("name"),row.text(if(mail) "username" else "url")).any {it.contains(query.trim(),ignoreCase=true)}}
                if(visible.isEmpty()) Text(if(entries.isEmpty()) "还没有接入，添加后即可同步。" else "未找到匹配项，试试名称或地址。",style=MaterialTheme.typography.bodyMedium)
                visible.forEach {(id,item)->
                    val row=item.jsonObject
                    Surface(onClick={editing=id;draft=row},enabled=!busy,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(row.text("name"),style=MaterialTheme.typography.titleMedium)
                            Text(row.text(if(mail) "username" else "url"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when {row.text("enabled","true")=="false"->"已暂停";row.text("credential_set")!="true"->"待填写凭据";else->"已配置"},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                DeskButton({editing="source_"+UUID.randomUUID().toString().replace("-","").take(12);draft=buildJsonObject {put("provider","1panel");put("enabled",true);put("limit",5)}},enabled=!busy && entries.size<20,modifier=Modifier.fillMaxWidth()) {Text(if(mail) "添加邮箱" else "添加服务器")}
            } else {
                ServiceEditor(mail,draft,busy,::write)
                if(entries.containsKey(editing)) {
                    DeskOutlinedButton({check(if(mail) "mail" else "servers",editing!!)},enabled=!busy && draft.text("credential_set")=="true",modifier=Modifier.fillMaxWidth()) {Text("检查已保存的连接")}
                    TextButton({removing=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("移除此接入")}
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if(removing) AlertDialog(onDismissRequest={if(!busy) removing=false},title={Text("移除此接入？")},text={Text("只移除当前项，其他邮箱和服务器保留。")},confirmButton={TextButton({
        save(buildJsonObject {put(collection,buildJsonObject {entries.filterKeys {it!=editing}.forEach {(id,row)->put(id,row)}})}) {removing=false;back()}
    },enabled=!busy) {Text("移除")}},dismissButton={TextButton({removing=false},enabled=!busy) {Text("取消")}})
}

@Composable private fun ServiceEditor(mail: Boolean,current: JsonObject,busy: Boolean,save: (JsonObject)->Unit) {
    val values=remember(current) {mutableStateMapOf("name" to current.text("name",""),"username" to current.text("username",""),"url" to current.text("url",""),
        "email" to current.text("email",""),"public_url" to current.text("public_url",""),"secret" to "",
        "proxy_host" to current.obj("proxy").text("host","127.0.0.1"),"proxy_port" to current.obj("proxy").text("port",""))}
    var provider by remember(current) {mutableStateOf(current.text("provider","1panel"))}
    var signature by remember(current) {mutableStateOf(current.text("signature","md5"))}
    var enabled by remember(current) {mutableStateOf(current.text("enabled","true")!="false")}
    var proxyEnabled by remember(current) {mutableStateOf(current["proxy"] is JsonObject)}
    var limit by remember(current) {mutableIntStateOf(current.text("limit","5").toIntOrNull() ?: 5)}
    var error by remember(current) {mutableStateOf("")}
    DeskTextField(values["name"].orEmpty(),{values["name"]=it;error=""},label={Text(if(mail) "邮箱名称" else "服务器名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
    if(mail) {
        DeskTextField(values["username"].orEmpty(),{values["username"]=it;error=""},label={Text("Gmail 地址")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
    } else {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for((key,label) in listOf("1panel" to "1Panel v2","beszel" to "Beszel")) FilterChip(provider==key,onClick={provider=key;values["secret"]="";error=""},label={Text(label)},enabled=!busy)
        }
        DeskTextField(values["url"].orEmpty(),{values["url"]=it;error=""},label={Text(if(provider=="1panel") "面板地址" else "Beszel 地址")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
        if(provider=="beszel") DeskTextField(values["email"].orEmpty(),{values["email"]=it;error=""},label={Text("登录邮箱")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
    }
    DeskTextField(values["secret"].orEmpty(),{values["secret"]=it;error=""},label={Text(if(mail) "应用专用密码" else if(provider=="1panel") "API Key" else "密码")},singleLine=true,enabled=!busy,
        visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),
        supportingText={Text(if(current.text("credential_set")=="true") "已有凭据留空保留；更换地址需重新填写。" else "此项凭据单独保存到 MyDesk 后端。")})
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("启用同步",Modifier.weight(1f));Switch(enabled,{enabled=it},enabled=!busy)}
    if(mail) {
        Text("每个邮箱展示的邮件数量",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {for(n in 3..5) FilterChip(limit==n,onClick={limit=n},label={Text("$n 封")},enabled=!busy)}
        Text("仅读取标题与发件人，不读取正文、不标记已读。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("使用 HTTP 代理",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                    Switch(proxyEnabled,{proxyEnabled=it;error=""},enabled=!busy,modifier=Modifier.semantics {contentDescription="使用 HTTP 代理"})
                }
                if(proxyEnabled) {
                    DeskTextField(values["proxy_host"].orEmpty(),{values["proxy_host"]=it;error=""},label={Text("代理地址")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
                    DeskTextField(values["proxy_port"].orEmpty(),{values["proxy_port"]=it;error=""},label={Text("代理端口")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                    Text("由 MyDesk 后端连接代理。同机代理地址可填 127.0.0.1。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else if(provider=="1panel") {
        Text("API 签名",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for((key,label) in listOf("md5" to "兼容模式","hmac-sha256" to "HMAC-SHA256")) FilterChip(signature==key,onClick={signature=key},label={Text(label)},enabled=!busy)
        }
        Text("填写面板根地址；在 1Panel 开启 API 并允许 MyDesk 电脑的出口 IP。只读取监控数据。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    DeskButton({runCatching {buildJsonObject {
        val name=values["name"].orEmpty().trim();require(name.isNotBlank()) {"请填写接入名称"};put("name",name);put("enabled",enabled)
        if(mail) {val address=values["username"].orEmpty().trim();require(address.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {"请填写有效的 Gmail 地址"};put("username",address);put("limit",limit)
            for(key in listOf("host","mailbox")) current[key]?.let {put(key,it)}
            put("proxy",SettingsInput.mailProxy(proxyEnabled,values["proxy_host"].orEmpty(),values["proxy_port"].orEmpty()))
        } else {val url=values["url"].orEmpty().trim();val parsed=java.net.URI(url);require(parsed.scheme in listOf("http","https") && parsed.host!=null && parsed.userInfo==null && parsed.query==null && parsed.fragment==null) {"请填写有效的 HTTP 或 HTTPS 面板地址"};put("url",url.trimEnd('/'));put("provider",provider)
            if(provider=="1panel") put("signature",signature) else {require(values["email"].orEmpty().isNotBlank()) {"请填写登录邮箱"};put("email",values["email"].orEmpty().trim());if(values["public_url"].orEmpty().isNotBlank()) put("public_url",values["public_url"]!!)}
        }
        val secret=values["secret"].orEmpty().trim();require(secret.isNotEmpty() || current.text("credential_set")=="true" || !enabled) {"请填写此项的独立凭据"}
        if(secret.isNotEmpty()) put(if(mail || provider=="beszel") "password" else "api_key",if(mail) secret.replace(" ","") else secret)
    }}.onSuccess(save).onFailure {error=it.message ?: "请检查输入"}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(if(busy) "保存中…" else if(mail) "保存邮箱" else "保存服务器")}
}
