package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
        inbox.rows("recent").forEach {row->TextButton({open(row.text("id"))}) {Text("${row.text("source_name")} · ${row.text("title")}${if(row.text("read_at")=="null") " · 未读" else ""}")}}
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("通知收件箱",style=MaterialTheme.typography.titleLarge)
            if(!supported) Text("此功能需要升级服务器")
            if(message.isNotEmpty())Text(message)
            Text("离线已读操作会在联网后同步",style=MaterialTheme.typography.bodySmall)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            val selected=detail
            if(selected!=null) {
                Text(selected.text("source_name"));Text(date(selected.text("received_at"),snapshot.text("timezone","Asia/Shanghai")))
                SelectionContainer {Text(selected.text("body",selected.text("summary")))}
                TextButton({scope.launch {graph.readInbox(buildJsonObject {put("ids",JsonArray(listOf(JsonPrimitive(selected.text("id")))))});detail=null;load()}},enabled=!busy) {Text("标为已读")}
                TextButton({detail=null}) {Text("返回列表")}
            } else {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(source.isEmpty(),{source=""},{Text("全部来源")})
                    snapshot.obj("inbox").rows("sources").forEach {item->FilterChip(source==item.text("id"),{source=item.text("id")},{Text(item.text("name"))})}
                }
                TextButton({scope.launch {graph.readInbox(buildJsonObject {put("through_seq",cutoff)});load()}},enabled=supported&&!busy) {Text("全部已读（截至当前列表）")}
                for(row in rows) {
                    TextButton({scope.launch {open(row.text("id"))}}) {Column {
                        Text("${row.text("source_name")} · ${row.text("title")}${if(row.text("read_at")=="null") " · 未读" else ""}")
                        Text(row.text("summary"),maxLines=3);Text(date(row.text("received_at"),snapshot.text("timezone","Asia/Shanghai")))
                    }}
                }
                if(rows.isEmpty()&&!busy)Text("暂无通知")
                if(cursor!=null)TextButton({scope.launch {load(true)}},enabled=!busy) {Text("加载更早通知")}
                TextButton({scope.launch {load()}},enabled=supported&&!busy) {Text("刷新 / 重试")}
            }
            TextButton(close) {Text("关闭")}
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
    suspend fun refresh() {val result=graph.inboxRequest("/api/notification-sources");sources=result.rows("sources");message=if(result.text("push_available")!="true") "消息可同步，后台推送未配置" else "消息可同步，后台推送已配置；送达需真机验证"}
    fun perform(operation: suspend ()->Unit) {if(busy)return;scope.launch {busy=true;try {operation();refresh()}catch(error: Exception) {if(error is kotlinx.coroutines.CancellationException)throw error;message=if(error is ApiError && error.status==404) "服务器暂未支持通知接入" else error.message ?: "操作失败"}finally {busy=false}}}
    LaunchedEffect(Unit) {perform {refresh()}}
    DeskModalSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("通知接入",style=MaterialTheme.typography.titleLarge);Text(message)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            DeskTextField(name,{name=it},label={Text("来源名称")},singleLine=true)
            Row {Text("CPE 短信（必须带幂等 ID）");Switch(cpe,{cpe=it},enabled=!busy)}
            TextButton({perform {path=graph.inboxRequest("/api/notification-sources","POST",buildJsonObject {put("name",name);put("kind",if(cpe) "cpe" else "text")}).text("receive_path");name=""}},enabled=!busy&&name.isNotBlank()) {Text("创建来源")}
            if(path.isNotEmpty()) {
                Text("接收地址仅此一次展示，请立即复制。以后需要重置才能重新获取。")
                TextButton({clipboard.setText(AnnotatedString(graph.state.value.server+path))}) {Text("复制完整 HTTPS 接收地址")}
                TextButton({path=""}) {Text("已保存，隐藏地址")}
            }
            sources.forEach {row->
                HorizontalDivider();Text("${row.text("name")} · ${if(row.text("enabled")=="1") "启用" else "停用"}")
                Text("最近接收：${row.text("last_received_at","—")}")
                FlowRow {
                    TextButton({renaming=row;renamed=row.text("name")},enabled=!busy) {Text("重命名")}
                    TextButton({perform {graph.inboxRequest("/api/notification-sources/${row.text("id")}","PATCH",buildJsonObject {put("enabled",row.text("enabled")!="1")})}},enabled=!busy) {Text("启用 / 停用")}
                    TextButton({perform {path=graph.inboxRequest("/api/notification-sources/${row.text("id")}/rotate-secret","POST").text("receive_path")}},enabled=!busy) {Text("重置密钥")}
                    TextButton({removing=row},enabled=!busy) {Text("删除")}
                }
            }
            TextButton(close) {Text("关闭")}
        }
    }
    removing?.let {row->AlertDialog(onDismissRequest={removing=null},title={Text("删除来源？")},text={Text("历史消息保留；此来源将不再接收新消息。")},confirmButton={TextButton({removing=null;perform {graph.inboxRequest("/api/notification-sources/${row.text("id")}","DELETE")}}) {Text("删除")}},dismissButton={TextButton({removing=null}) {Text("取消")}})}
    renaming?.let {row->AlertDialog(onDismissRequest={renaming=null},title={Text("重命名来源")},text={DeskTextField(renamed,{renamed=it})},confirmButton={TextButton({renaming=null;perform {graph.inboxRequest("/api/notification-sources/${row.text("id")}","PATCH",buildJsonObject {put("name",renamed)})}}) {Text("保存")}},dismissButton={TextButton({renaming=null}) {Text("取消")}})}
}
