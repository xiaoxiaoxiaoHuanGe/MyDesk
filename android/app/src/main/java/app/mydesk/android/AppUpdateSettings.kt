package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable internal fun AppUpdateSettings(graph: AppGraph) {
    val state by graph.updates.state.collectAsState()
    DeskCard("应用更新") {
        Text("当前版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text(state.message.ifEmpty {"可以手动检查更新"})
        state.info?.let {info->
            Text("最新版本 ${info.name} (${info.code}) · ${info.size/1024/1024} MiB")
            Text(info.notes)
            if(state.downloading) {LinearProgressIndicator(progress={state.bytes.toFloat()/info.size},modifier=Modifier.fillMaxWidth());Text("${state.bytes/1024} / ${info.size/1024} KiB")}
            if(info.minSdk<=android.os.Build.VERSION.SDK_INT) {
                if(state.ready) TextButton({graph.updates.install()}) {Text("安装更新")}
                else TextButton({graph.updates.download()},enabled=!state.downloading&&!state.checking) {Text(if(state.downloading) "下载中…" else "下载 / 重试")}
            }
        }
        TextButton({graph.updates.check(true)},enabled=!state.checking&&!state.downloading) {Text("检查更新")}
    }
}
@Composable internal fun AppUpdatePrompt(graph: AppGraph) {
    val state by graph.updates.state.collectAsState()
    if(state.prompt) AlertDialog(onDismissRequest=graph.updates::dismissPrompt,title={Text("发现新版本 ${state.info?.name.orEmpty()}")},text={Text(state.info?.notes.orEmpty())},
        confirmButton={TextButton({graph.updates.dismissPrompt();graph.updates.download()}) {Text("下载")}},dismissButton={TextButton(graph.updates::dismissPrompt) {Text("稍后")}})
}
