package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReminderRepeatPicker(days: List<Int>,onDismiss: ()->Unit,onSelect: (List<Int>)->Unit,allowOnce: Boolean=true) {
    var chosen by remember {mutableStateOf(days)}
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    DeskModalSheet(onDismissRequest=onDismiss,sheetState=sheet,modifier=Modifier.semantics {paneTitle="重复频率"}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("重复频率",style=MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val options=listOf("每天" to (1..7).toList(),"周一至周五" to (1..5).toList(),"周末" to listOf(6,7))
                (if(allowOnce) listOf("仅一次" to emptyList<Int>())+options else options).forEach {(label,values)->
                    FilterChip(selected=chosen.sorted()==values,onClick={chosen=values},label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
                }
            }
            Text("自定义星期",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("周一","周二","周三","周四","周五","周六","周日").forEachIndexed {index,label->
                    val day=index+1
                    FilterChip(selected=day in chosen,onClick={chosen=if(day in chosen) chosen-day else (chosen+day).sorted()},label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
                }
            }
            Text("周一至周五不包含节假日及调休判断",style=MaterialTheme.typography.bodySmall)
            DeskButton({onSelect(chosen);onDismiss()},enabled=allowOnce || chosen.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {Text("确定")}
        }
    }
}
