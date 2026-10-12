package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal fun inboxReadMatches(row: JsonObject,payload: JsonObject): Boolean =
    payload["through_seq"]?.jsonPrimitive?.longOrNull?.let {row.text("seq").toLongOrNull()?.let {seq->seq<=it}==true} ?: payload["ids"]?.jsonArray?.any {it.jsonPrimitive.content==row.text("id")}==true

@Composable internal fun RecentInbox(snapshot: JsonObject,open: (String?)->Unit) {
    val inbox=snapshot.obj("inbox")
    if(inbox.rows("recent").isEmpty()&&inbox.rows("sources").isEmpty())return
    DeskCard("最近通知 · ${inbox.text("unread","0")} 未读") {
        inbox.rows("recent").forEach {row->TextButton({open(row.text("id"))}) {Text("${row.text("source_name")} · ${row.text("title")}${if(row.text("read_at","").isBlank()) " · 未读" else ""}")}}
        TextButton({open(null)}) {Text("查看全部")}
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun InboxSheet(graph: AppGraph,snapshot: JsonObject,close: ()->Unit,initial: String?=null) {
    val scope=rememberCoroutineScope();var rows by remember {mutableStateOf<List<JsonObject>>(emptyList())}
    var source by rememberSaveable {mutableStateOf("")};var detail by remember {mutableStateOf<JsonObject?>(null)}
    var cursor by remember {mutableStateOf<Long?>(null)};var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var cutoff by remember {mutableLongStateOf(0L)}
    val supported=snapshot.obj("capabilities").text("inbox")=="true"
    suspend fun load(more: Boolean=false) {
        if(busy||!supported)return;busy=true;message=""
        try {
            val result=graph.inboxRequest("/api/inbox?limit=50"+(if(more&&cursor!=null) "&before=$cursor" else "")+(if(source.isNotBlank()) "&source_id=$source" else ""))
            val page=result.rows("items");graph.repo.cacheInbox(page)
            val pending=graph.store.inboxPending().map {deskJson.parseToJsonElement(it.json).jsonObject}
            val visible=page.map {row->if(pending.any {inboxReadMatches(row,it)}) buildJsonObject {row.forEach {(k,v)->put(k,v)};put("read_at","pending")} else row}
            rows=if(more) (rows+visible).distinctBy {it.text("id")} else visible
            cursor=result["next_cursor"]?.jsonPrimitive?.longOrNull
            cutoff=maxOf(cutoff,rows.maxOfOrNull {it.text("seq").toLong()} ?: 0)
        } catch(error: Exception) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            val cached=graph.store.inboxCache().filter {source.isEmpty()||it.text("source_id")==source}
            if(!more) {
                val pending=graph.store.inboxPending().map {deskJson.parseToJsonElement(it.json).jsonObject}
                rows=cached.map {row->if(pending.any {inboxReadMatches(row,it)}) buildJsonObject {row.forEach {(k,v)->put(k,v)};put("read_at","pending")} else row}
                cutoff=maxOf(cutoff,rows.maxOfOrNull {it.text("seq").toLong()} ?: 0)
            }
            message=if(error is ApiError && error.status==404) "服务器暂未支持通知收件箱" else "离线缓存；${error.message ?: "读取失败，可重试"}"
        } finally {busy=false}
    }
    suspend fun open(id: String) {
        message=""
        try {detail=graph.inboxRequest("/api/inbox/$id");graph.repo.cacheInbox(listOf(detail!!))}
        catch(error: Exception) {if(error is kotlinx.coroutines.CancellationException)throw error;detail=if(error is ApiError && error.status==404) null else graph.store.inboxMessage(id);message=if(detail==null) "该通知已清理或尚未缓存" else "显示离线缓存"}
    }
    LaunchedEffect(source) {load();if(initial!=null)open(initial)}
    LaunchedEffect(snapshot.obj("inbox").text("latest_seq"),snapshot.obj("inbox").text("unread")) {if(!busy)load()}
    DeskModalSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            InboxSheetHeader(if(detail==null) "通知收件箱" else "通知详情",close,back=if(detail!=null) ({detail=null}) else null)
            if(!supported) InboxHint("此功能需要升级服务器")
            if(message.isNotEmpty())InboxHint(message)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            val selected=detail
            if(selected!=null) {
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(selected.text("title"),style=MaterialTheme.typography.titleMedium)
                        InboxHint("${selected.text("source_name")} · ${date(selected.text("received_at"),snapshot.text("timezone","Asia/Shanghai"))}")
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        SelectionContainer {Text(selected.text("body",selected.text("summary")),style=MaterialTheme.typography.bodyLarge)}
                    }
                }
                DeskButton({scope.launch {graph.readInbox(buildJsonObject {put("ids",JsonArray(listOf(JsonPrimitive(selected.text("id")))))});detail=null;load()}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("标为已读")}
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(source.isEmpty(),{source=""},{Text("全部来源")})
                    snapshot.obj("inbox").rows("sources").forEach {item->FilterChip(source==item.text("id"),{source=item.text("id")},{Text(item.text("name"))})}
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton({scope.launch {load()}},enabled=supported&&!busy) {Text("刷新")}
                    TextButton({scope.launch {graph.readInbox(buildJsonObject {put("through_seq",cutoff)});load()}},enabled=supported&&!busy&&rows.isNotEmpty()) {Text("当前列表已读")}
                }
                for(row in rows) {
                    InboxMessageCard(row,snapshot.text("timezone","Asia/Shanghai")) {scope.launch {open(row.text("id"))}}
                }
                if(rows.isEmpty()&&!busy)InboxHint("暂无通知")
                if(cursor!=null)TextButton({scope.launch {load(true)}},enabled=!busy) {Text("加载更早通知")}
                InboxHint("已读状态将在联网后同步")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NotificationSourcesSheet(graph: AppGraph,close: ()->Unit) {
    val scope=rememberCoroutineScope();val clipboard=LocalClipboardManager.current
    var sources by remember {mutableStateOf<List<JsonObject>>(emptyList())};var name by rememberSaveable {mutableStateOf("")}
    var cpe by rememberSaveable {mutableStateOf(true)};var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")};var path by remember {mutableStateOf("")};var removing by remember {mutableStateOf<JsonObject?>(null)}
    var renaming by remember {mutableStateOf<JsonObject?>(null)};var renamed by remember {mutableStateOf("")}
    var creating by rememberSaveable {mutableStateOf(false)}
    var selectedId by rememberSaveable {mutableStateOf("")}
    var pushReady by remember {mutableStateOf<Boolean?>(null)}
    var rotating by remember {mutableStateOf<JsonObject?>(null)}
    val selected=sources.firstOrNull {it.text("id")==selectedId}
    val zone=graph.state.value.snapshot?.text("timezone","Asia/Shanghai") ?: "Asia/Shanghai"
    fun back() {creating=false;selectedId="";message=""}
    suspend fun refresh() {val result=graph.inboxRequest("/api/notification-sources");sources=result.rows("sources");pushReady=result.text("push_available")=="true"}
    fun perform(operation: suspend ()->Unit) {if(busy)return;scope.launch {busy=true;message="";try {operation();refresh()}catch(error: Exception) {if(error is kotlinx.coroutines.CancellationException)throw error;message=if(error is ApiError && error.status==404) "服务器暂未支持通知接入" else error.message ?: "操作失败"}finally {busy=false}}}
    LaunchedEffect(Unit) {perform {}}
    DeskModalSheet(onDismissRequest={if(!busy)close()},dragDismissEnabled=!busy,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            InboxSheetHeader(if(creating) "添加通知来源" else if(selected!=null) "来源详情" else "通知来源",close,back=if(creating||selected!=null) ::back else null,busy=busy)
            if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(creating) {
                DeskTextField(name,{name=it},label={Text("来源名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                Text("消息类型",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(cpe,{cpe=true},label={Text("CPE 短信")},enabled=!busy)
                    FilterChip(!cpe,{cpe=false},label={Text("通用文本")},enabled=!busy)
                }
                if(cpe) InboxHint("CPE 短信需携带消息 ID")
                DeskButton({perform {path=graph.inboxRequest("/api/notification-sources","POST",buildJsonObject {put("name",name.trim());put("kind",if(cpe) "cpe" else "text")}).text("receive_path");name="";creating=false}},enabled=!busy&&name.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text("创建来源")}
            } else if(selected!=null) {
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(selected.text("name"),style=MaterialTheme.typography.titleMedium)
                        InboxHint(if(selected.text("kind")=="cpe") "CPE 短信" else "通用文本")
                        InboxHint(sourceReceivedLabel(selected,zone))
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("启用来源",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    Switch(selected.text("enabled")=="1",{value->perform {graph.inboxRequest("/api/notification-sources/${selected.text("id")}","PATCH",buildJsonObject {put("enabled",value)})}},enabled=!busy,modifier=Modifier.semantics {contentDescription="启用来源"})
                }
                DeskSettingRow("来源名称",selected.text("name"),{renaming=selected;renamed=selected.text("name")},enabled=!busy)
                DeskSettingRow("重置接收地址","旧地址将立即失效",{rotating=selected},enabled=!busy)
                TextButton({removing=selected},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("删除来源")}
            } else {
                if(sources.isEmpty()&&!busy) InboxHint("还没有来源，添加后即可接收消息")
                sources.forEach {row->NotificationSourceCard(row,zone,{selectedId=row.text("id");message=""},enabled=!busy)}
                DeskButton({creating=true;name="";cpe=true;message=""},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("添加来源")}
                pushReady?.let {InboxHint(if(it) "后台推送已配置" else "后台推送未配置，消息仍可同步")}
                if(message.isNotEmpty()) TextButton({perform {}},enabled=!busy) {Text("重试")}
            }
            if(path.isNotEmpty()) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("接收地址已生成",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("地址仅显示一次，请及时复制",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
                        DeskOutlinedButton({clipboard.setText(AnnotatedString(graph.state.value.server+path))},modifier=Modifier.fillMaxWidth()) {Text("复制接收地址")}
                        TextButton({path=""}) {Text("已保存，隐藏")}
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    removing?.let {row->AlertDialog(onDismissRequest={removing=null},title={Text("删除来源？")},text={Text("历史消息保留，将停止接收新消息。")},confirmButton={TextButton({removing=null;perform {graph.inboxRequest("/api/notification-sources/${row.text("id")}","DELETE");selectedId=""}}) {Text("删除")}},dismissButton={TextButton({removing=null}) {Text("取消")}})}
    rotating?.let {row->AlertDialog(onDismissRequest={rotating=null},title={Text("重置接收地址？")},text={Text("旧地址立即失效，请同步更新发送方配置。")},confirmButton={TextButton({rotating=null;perform {path=graph.inboxRequest("/api/notification-sources/${row.text("id")}/rotate-secret","POST").text("receive_path")}}) {Text("重置")}},dismissButton={TextButton({rotating=null}) {Text("取消")}})}
    renaming?.let {row->AlertDialog(onDismissRequest={renaming=null},title={Text("来源名称")},text={DeskTextField(renamed,{renamed=it},singleLine=true)},confirmButton={TextButton({renaming=null;perform {graph.inboxRequest("/api/notification-sources/${row.text("id")}","PATCH",buildJsonObject {put("name",renamed.trim())})}},enabled=renamed.isNotBlank()) {Text("保存")}},dismissButton={TextButton({renaming=null}) {Text("取消")}})}
}
