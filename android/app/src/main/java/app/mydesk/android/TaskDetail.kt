package app.mydesk.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject

@Composable internal fun TaskDetail(task: JsonObject,zone: String,onClose: ()->Unit,history: ()->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text(task.text("task_name"))},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Text("状态：${status(task.text("status"))}")}
        item {Text(task.text("message"))}
        item {Text("最近执行：${date(task.text("timestamp",""),zone)}")}
        if(task.text("source","").isNotBlank()) item {Text("来源：${task.text("source")}")}
    }},confirmButton={TextButton(history) {Text("查看此任务历史")}},dismissButton={TextButton(onClose) {Text("关闭")}})
}
