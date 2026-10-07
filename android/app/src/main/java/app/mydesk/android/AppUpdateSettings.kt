package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun AppUpdateSettings(graph: AppGraph) {
    val state by graph.updates.state.collectAsState()
    var details by remember {mutableStateOf(false)}
    val info=state.info
    val compatible=info!=null && info.minSdk<=android.os.Build.VERSION.SDK_INT
    DeskCard("") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("应用更新",style=MaterialTheme.typography.titleSmall)
                Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick={
                when {
                    state.ready && compatible->graph.updates.install()
                    compatible->details=true
                    else->graph.updates.check(true)
                }
            },enabled=!state.checking&&!state.downloading) {
                Text(when {
                    state.checking->"检查中…"
                    state.downloading->"下载中…"
                    state.ready && compatible->"安装"
                    compatible->"更新至 ${info?.name}"
                    else->"检查更新"
                })
            }
        }
        if(state.downloading && info!=null) {
            val progress=(state.bytes.toFloat()/info.size).coerceIn(0f,1f)
            LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
            Text("${(progress*100).toInt()}%",style=MaterialTheme.typography.bodySmall)
        } else if(state.message.isNotBlank() && !state.checking && !state.message.startsWith("发现新版本")) {
            Text(state.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(details && info!=null) AlertDialog(onDismissRequest={details=false},title={Text("更新至 ${info.name}")},
        text={Text(info.notes)},confirmButton={TextButton({details=false;graph.updates.download()}) {Text("下载 / 重试")}},
        dismissButton={TextButton({details=false}) {Text("稍后")}})
}
@Composable internal fun AppUpdatePrompt(graph: AppGraph) {
    val state by graph.updates.state.collectAsState()
    if(state.prompt) AlertDialog(onDismissRequest=graph.updates::dismissPrompt,title={Text("发现新版本 ${state.info?.name.orEmpty()}")},text={Text(state.info?.notes.orEmpty())},
        confirmButton={TextButton({graph.updates.dismissPrompt();graph.updates.download()}) {Text("下载")}},dismissButton={TextButton(graph.updates::dismissPrompt) {Text("稍后")}})
}
