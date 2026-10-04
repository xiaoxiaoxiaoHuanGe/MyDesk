package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReminderDetailSheet(reminder: Reminder,zone: String,busy: Boolean,onDismiss: ()->Unit,onAction: (Reminder,String,Int)->Unit) {
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val scope=rememberCoroutineScope()
    DeskModalSheet(onDismissRequest=onDismiss,sheetState=sheet,modifier=Modifier.semantics {paneTitle="提醒详情"}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("提醒详情",modifier=Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleMedium)
                IconButton({scope.launch {sheet.hide();onDismiss()}},modifier=Modifier.size(48.dp)) {Icon(Icons.Default.Close,contentDescription="关闭详情")}
            }
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(reminder.title,modifier=Modifier.semantics {heading()},style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                        Text("提醒时间",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        DeskStateBadge(reminderStatusLabel(reminder))
                    }
                    Text(reminderTime(reminder.remindAt,zone,true),style=MaterialTheme.typography.bodyLarge)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("稍后提醒",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(10 to "10 分钟",30 to "30 分钟",60 to "1 小时").forEach {(minutes,label) ->
                        DeskOutlinedButton({onAction(reminder,"snooze",minutes)},enabled=!busy,
                            modifier=Modifier.semantics {contentDescription="延后 $minutes 分钟"}) {Text(label)}
                    }
                }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                DeskButton({onAction(reminder,"complete",10)},enabled=!busy,modifier=Modifier.weight(1f)) {Text("完成提醒")}
                DeskOutlinedButton({onAction(reminder,"cancel",10)},enabled=!busy,modifier=Modifier.weight(1f)) {Text("取消提醒")}
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
