package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import java.util.Locale
import java.time.Instant
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@Composable internal fun ServerCard(snapshot: JsonObject,zone: String) {
    val feed=snapshot.obj("feeds").obj("servers")
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    DeskCard("服务器") {
        when {
            snapshot.obj("configured").text("servers") != "true" -> Text("服务器监控尚未接入，在设置中添加 1Panel 服务器。")
            feed.obj("data")["error"] != null -> Text(feed.obj("data").text("error"))
            feed.obj("data").rows("items").isEmpty() -> Text("等待服务器数据")
            else -> feed.obj("data").rows("items").forEach { server ->
                Surface(onClick={selected=server},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text(server.text("name"),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);DeskStateBadge(if(server["error"]!=null) "连接异常" else status(server.text("status")),server["error"]!=null)}
                        if(server["error"]!=null) Text(server.text("error"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        else FlowRow(horizontalArrangement=Arrangement.spacedBy(14.dp)) {for((key,label) in listOf("cpu" to "CPU","ram" to "内存","disk" to "磁盘")) Text("$label ${serverMetric(server[key],"%")}",style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
        }
        if(feed["updated_at"] != null) Text("${if(feed.text("stale") == "true") "数据已过期 · " else "更新于 "}${date(feed.text("updated_at"),zone)}",style=MaterialTheme.typography.bodySmall)
    }
    selected?.let {server -> ServerDetail(server,zone) {selected=null}}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServerDetail(server: JsonObject,zone: String="Asia/Shanghai",onClose: ()->Unit) {
    DeskModalSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("服务器详情",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                IconButton(onClose,modifier=Modifier.size(48.dp)) {Icon(Icons.Default.Close,"关闭服务器详情")}
            }
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(server.text("name"),style=MaterialTheme.typography.headlineSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        DeskStateBadge(if(server["error"]!=null) "连接异常" else status(server.text("status")),server["error"]!=null || server.text("status") in setOf("down","unknown"))
                        val provider=when(server.text("provider","")) {"1panel"->"1Panel v2";"beszel"->"Beszel";else->""}
                        if(provider.isNotEmpty()) Text(provider,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(server["error"]!=null) Text(server.text("error"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            }
            Text("资源占用",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    for((key,label) in listOf("cpu" to "CPU","ram" to "内存","disk" to "磁盘")) {
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            ServerDetailValue(label,serverMetric(server[key],"%"))
                            val value=(server[key] as? JsonPrimitive)?.doubleOrNull?.takeIf {it.isFinite()}
                            if(value!=null) LinearProgressIndicator(progress={value.coerceIn(0.0,100.0).toFloat()/100f},modifier=Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),trackColor=MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            Text("其他指标",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("负载 · 1 / 5 / 15 分钟",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(serverMetric(server["load"]),style=MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    val network=server["network"]
                    if(network is JsonObject) {
                        ServerDetailValue("累计上传",serverByteCount(network["sent_bytes"]))
                        ServerDetailValue("累计下载",serverByteCount(network["received_bytes"]))
                    } else ServerDetailValue("网络指标",serverMetric(network))
                    val temperature=if(server.text("provider","")=="1panel" && server["temperature"] in listOf(null,JsonNull)) "接口未提供" else serverMetric(server["temperature"]," °C")
                    ServerDetailValue("温度",temperature)
                }
            }
            Text("更新于 ${date(server.text("updated_at",""),zone)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun ServerDetailValue(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,textAlign=TextAlign.End)
    }
}

private fun serverByteCount(value: JsonElement?): String {
    val primitive=value as? JsonPrimitive ?: return "未采集"
    if(primitive.isString) return "未采集"
    var count=primitive.doubleOrNull?.takeIf {it.isFinite() && it>=0} ?: return "未采集"
    val units=listOf("B","KiB","MiB","GiB","TiB","PiB","EiB")
    var unit=0
    while(count>=1024 && unit<units.lastIndex) {count/=1024;unit++}
    return String.format(Locale.ROOT,"%.2f %s",count,units[unit])
}

private fun serverMetric(value: JsonElement?,suffix: String=""): String=when(value) {
    is JsonPrimitive -> value.doubleOrNull?.takeIf {it.isFinite()}?.let {String.format(Locale.ROOT,"%.2f",it)+suffix} ?: "未知"
    is JsonArray -> if(value.isNotEmpty() && value.all {(it as? JsonPrimitive)?.doubleOrNull?.isFinite() == true}) value.joinToString(" / ") {serverMetric(it)} else "未知"
    else -> "未知"
}

@Composable internal fun NetworkCard(snapshot: JsonObject,zone: String) {
    val feed=snapshot.obj("feeds").obj("network")
    val data=feed.obj("data")
    DeskCard("网络") {
        when {
            snapshot.obj("configured").text("network") != "true" -> Text("网络检测尚未启用")
            data["error"] != null -> Text(data.text("error"))
            data.isEmpty() -> Text("等待网络检测")
            else -> {
                Text("Internet · ${when(data.text("online")) { "true"->"在线"; "false"->"异常"; else->"未知" }}")
                Text("Ping ${data.text("ping")} ms · 公网 IP ${data.text("public_ip")}")
                data.rows("nodes").forEach { node -> Text("${node.text("name")} · ${when(node.text("online")) {"true"->"可达"; "false"->"不可达"; else->"未知"}} · ${node.text("ping")} ms") }
            }
        }
        if(feed["updated_at"] != null) Text("${if(feed.text("stale") == "true") "数据已过期 · " else "更新于 "}${date(feed.text("updated_at"),zone)}",style=MaterialTheme.typography.bodySmall)
        Text("检测位置：运行 MyDesk 的电脑或服务器",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun StepsCard(snapshot: JsonObject,busy: Boolean,command: (String,JsonObject)->Unit,openPlan: (Boolean)->Unit={}) {
    var steps by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var release by remember { mutableStateOf(false) }
    val job=snapshot.obj("wxstep")
    val active=job.text("status") in setOf("dispatching","queued","running","tracking_error")
    DeskCard("手动提交") {
        if(snapshot.obj("configured").text("github") != "true") Text("步数尚未配置，在设置中连接步数工作流。")
        if(job.isNotEmpty()) StepsResult(job)
        if(job.text("status") == "tracking_error") TextButton({release=true}) {Text("检查后结束本地跟踪")}
        val submit: ()->Unit={runCatching {InputRules.steps(steps)}.onSuccess {command("wxstep/submit",buildJsonObject {put("steps",it)})}.onFailure {error=it.message ?: "步数无效"};Unit}
        DeskTextField(steps,{steps=it; error=""},label={Text("目标步数")},
            supportingText={Text(if(error.isNotEmpty()) error else "30000 步以内，且不能低于当前微信运动步数")},isError=error.isNotEmpty(),singleLine=true,
            enabled=!busy && !active,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),
            trailingIcon={DeskButton(submit,modifier=Modifier.padding(end=8.dp).semantics {contentDescription="提交步数"},
                enabled=steps.isNotBlank() && !busy && !active && snapshot.obj("configured").text("github") == "true") {Text("提交")}})


    }
    if(release) AlertDialog(onDismissRequest={release=false},title={Text("结束本地跟踪？")},text={Text("请先在 GitHub Actions 确认本次任务。此操作不会取消 GitHub 上的任务。")},confirmButton={TextButton({release=false; command("wxstep/release",buildJsonObject {put("confirmed",true)})}) {Text("已检查，结束跟踪")}},dismissButton={TextButton({release=false}) {Text("取消")}})
}

@Composable internal fun MailCard(snapshot: JsonObject,zone: String) {
    val feed=snapshot.obj("feeds").obj("mail")
    val data=feed.obj("data")
    var selected by rememberSaveable {mutableStateOf("")}
    var choosing by remember {mutableStateOf(false)}
    val accounts=data.rows("accounts")
    val account=accounts.firstOrNull {it.text("id")==selected}
    val sortedMails=(if(account!=null) account.rows("items") else data.rows("items")).sortedByDescending {
        runCatching {Instant.parse(it.text("received_at",""))}.getOrDefault(Instant.MIN)
    }
    val mails=if(account==null) sortedMails.take(3) else sortedMails
    DeskCard("最近邮件",headerAction={
        if(accounts.isNotEmpty()) Box {
            TextButton({choosing=true},modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp)) {
                Text("选择邮箱",style=MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ArrowDropDown,null,modifier=Modifier.padding(start=4.dp).size(18.dp))
            }
            DropdownMenu(choosing,{choosing=false},modifier=Modifier.heightIn(max=320.dp)) {
                DropdownMenuItem(text={Text("全部邮箱")},onClick={selected="";choosing=false})
                accounts.forEach {row->DropdownMenuItem(text={Text(row.text("name"))},onClick={selected=row.text("id");choosing=false})}
            }
        }
    }) {
        when {
            snapshot.obj("configured").text("mail") != "true" -> Text("Gmail 尚未接入，配置 IMAP 后查看最近邮件。")
            data["error"] != null -> Text(data.text("error"))
            data.isEmpty() -> Text("等待邮件同步")
            else -> {
                val failures=if(account!=null) listOf(account).filter {it["error"]!=null} else accounts.filter {it["error"]!=null}
                failures.forEach {row->Text("${row.text("name")} · ${row.text("error")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text(if(account==null) "全部邮箱 · 最近 3 封" else "${account.text("name")} · 收件箱",modifier=Modifier.weight(1f).padding(end=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primaryContainer) {
                        val unread=(account ?: data).text("unread","未知")
                        Text(if(unread=="null" || account?.get("error")!=null) "未读未知" else "$unread 未读${if(account==null && data.text("unread_complete","true")=="false") " · 部分邮箱" else ""}",modifier=Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                mails.forEach {mail ->
                    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(mail.text("subject"),fontWeight=if(mail.text("unread") == "true") FontWeight.SemiBold else FontWeight.Normal,
                                style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(mail.text("sender"),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(date(mail.text("received_at"),zone),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                            }
                            if(account==null) {
                                val owner=accounts.firstOrNull {it.text("id")==mail.text("account_id","")}
                                val source=listOf(mail.text("account_name",owner?.text("name","") ?: ""),
                                    mail.text("account_email",owner?.text("username","") ?: "")).filter {it.isNotBlank()}.distinct().joinToString(" · ")
                                if(source.isNotBlank()) Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(source,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if(mails.isEmpty() && failures.isEmpty()) Text("收件箱暂无邮件")
            }
        }
        if(feed["updated_at"] != null) Text("更新于 ${date(feed.text("updated_at"),zone)}",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun IntegrationSettings(settings: JsonObject?,busy: Boolean,save: (JsonObject,()->Unit)->Unit,serverError: String="",checkTask: (String)->Unit={},feedback: String=serverError,checkService: (String,String)->Unit={_,_->}) {
    var section by rememberSaveable {mutableStateOf("")}
    DeskCard("外部服务接入") {
        val githubCount=(if(settings?.obj("github")?.isNotEmpty()==true) 1 else 0)+(settings?.obj("github_tasks")?.size ?: 0)
        DeskSettingRow("GitHub 任务",if(githubCount>0) "已添加 $githubCount 项 · 单独管理" else "添加微信步数与自动任务",{section="github_all"},enabled=settings!=null && !busy,icon=SettingsGlyphs.GitHub)
        for((key,name) in listOf("gmail_accounts" to "Gmail 邮件","server_sources" to "服务器监控")) {
            val count=settings?.obj(key)?.size ?: 0
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            DeskSettingRow(name,if(count>0) "已添加 $count 项 · 单独管理" else "添加多个接入",{section=key},enabled=settings!=null && !busy,
                icon=if(key=="gmail_accounts") SettingsGlyphs.Mail else SettingsGlyphs.Server)
        }
    }
    if(section.isNotEmpty() && settings!=null) {
        if(section=="github_all") GitHubTaskSettings(settings,busy,save,onClose={section=""},checkTask=checkTask,feedback=feedback)
        else MultiServiceSettings(section=="gmail_accounts",settings,busy,save,onClose={section=""},feedback=feedback,check=checkService)
    }
}
