package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MailDetailSheet(
    graph: AppGraph, mail: JsonObject, zone: String, open: (String)->Unit, close: ()->Unit
) {
    val id = mail.text("id")
    val account = mail.text("account_id")
    var detail by remember(id) {mutableStateOf<JsonObject?>(null)}
    var failure by remember(id) {mutableStateOf("")}
    var attempt by remember(id) {mutableIntStateOf(0)}
    LaunchedEffect(id,account,attempt) {
        failure=""
        try {detail=graph.mailDetail(account,id.removePrefix("$account:"))}
        catch(error: CancellationException) {throw error}
        catch(error: Exception) {failure=error.message ?: "无法读取邮件正文"}
    }
    val content = detail
    DeskModalSheet(close) {
        LazyColumn(Modifier.fillMaxWidth(),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=32.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text(mail.text("subject"),style=MaterialTheme.typography.titleLarge)
                Text(mail.text("sender")+" · "+date(mail.text("received_at"),zone),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                failure.isNotBlank()->item {
                    Text(failure,color=MaterialTheme.colorScheme.error)
                    TextButton({attempt++}) {Text("重试")}
                }
                content==null->item {LinearProgressIndicator(Modifier.fillMaxWidth())}
                else->{
                    val body=content.text("body").ifBlank {"没有可显示的正文。"}
                    items(body.chunked(1500)) {part->Text(part,style=MaterialTheme.typography.bodyMedium)}
                    if(content.text("truncated")=="true") item {
                        Text("正文较长，请到 Gmail 查看完整内容。",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val gmail=content?.text("gmail_url").orEmpty()
            if(gmail.startsWith("https://mail.google.com/")) item {
                OutlinedButton({open(gmail)}) {Text("打开 Gmail")}
            }
        }
    }
}
