package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter

/** DatePicker encodes calendar days in UTC; the result is wall time in the workbench's timezone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DeskDateTimePicker(value: String,timezone: String,onDismiss: ()->Unit,onConfirm: (String)->Unit) {
    val initial=remember(value,timezone) {
        runCatching {LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}
            .getOrElse {ZonedDateTime.now(ZoneId.of(timezone)).plusHours(1).toLocalDateTime()}
    }
    var step by rememberSaveable {mutableIntStateOf(0)}
    val day=rememberDatePickerState(initialSelectedDateMillis=initial.toLocalDate().toEpochDay()*86_400_000L)
    val clock=rememberTimePickerState(initialHour=initial.hour,initialMinute=initial.minute,is24Hour=true)
    if(step == 0) DatePickerDialog(onDismissRequest=onDismiss,
        confirmButton={TextButton({step=1},enabled=day.selectedDateMillis != null) {Text("下一步")}},
        dismissButton={TextButton(onDismiss) {Text("取消")}}) {
        DatePicker(state=day,title={Text("选择提醒日期",Modifier.padding(start=24.dp,top=20.dp))},
            headline={Text(day.selectedDateMillis?.let {LocalDate.ofEpochDay(it/86_400_000L).toString()} ?: "请选择日期",Modifier.padding(horizontal=24.dp,vertical=12.dp),style=MaterialTheme.typography.headlineSmall)},
            showModeToggle=false,modifier=Modifier.verticalScroll(rememberScrollState()))
    } else AlertDialog(onDismissRequest=onDismiss,title={Text("选择提醒时间")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("${LocalDate.ofEpochDay(day.selectedDateMillis!!/86_400_000L)} · $timezone",color=MaterialTheme.colorScheme.onSurfaceVariant)
            TimeInput(state=clock,modifier=Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton({
        val date=LocalDate.ofEpochDay(day.selectedDateMillis!!/86_400_000L)
        onConfirm(date.atTime(clock.hour,clock.minute).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
    }) {Text("确定")}},dismissButton={TextButton({step=0}) {Text("返回日期")}})
}
