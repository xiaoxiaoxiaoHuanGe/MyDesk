package app.mydesk.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Calendar date plus local clock time, interpreted in the workbench timezone. */
@Composable internal fun DeskReminderTimePicker(today: java.time.LocalDate,dayIndex: Int,hours: Int,minutes: Int,enabled: Boolean,
    onDay: (Int)->Unit,onHours: (Int)->Unit,onMinutes: (Int)->Unit,showDate: Boolean=true) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(showDate) key(today) {
                TimeWheel("日期",dayIndex,36500,enabled,onDay,Modifier.weight(1.65f),18.sp,
                    display={index -> when(index) {0->"今天";1->"明天";2->"后天";else->today.plusDays(index.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))}},
                    description={index -> today.plusDays(index.toLong()).toString()},cyclic=false)
            }
            TimeWheel("小时",hours,23,enabled,onHours,Modifier.weight(1f))
            TimeWheel("分钟",minutes,59,enabled,onMinutes,Modifier.weight(1f))
        }
    }
}

/** Duration wheels share the date picker's selection, scrolling and accessibility behavior. */
@Composable internal fun DeskCountdownPicker(hours: Int,minutes: Int,seconds: Int,enabled: Boolean,
    onHours: (Int)->Unit,onMinutes: (Int)->Unit,onSeconds: (Int)->Unit) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TimeWheel("小时",hours,23,enabled,onHours,Modifier.weight(1f))
            Text(":",Modifier.align(Alignment.CenterVertically).padding(top=16.dp),style=MaterialTheme.typography.titleLarge)
            TimeWheel("分钟",minutes,59,enabled,onMinutes,Modifier.weight(1f))
            Text(":",Modifier.align(Alignment.CenterVertically).padding(top=16.dp),style=MaterialTheme.typography.titleLarge)
            TimeWheel("秒钟",seconds,59,enabled,onSeconds,Modifier.weight(1f))
        }
    }
}

@Composable private fun TimeWheel(label: String,value: Int,maximum: Int,enabled: Boolean,onChange: (Int)->Unit,modifier: Modifier,
    textSize: androidx.compose.ui.unit.TextUnit=24.sp,display: (Int)->String={it.toString().padStart(2,'0')},description: (Int)->String={"$it $label"},cyclic: Boolean=true) {
    val count=maximum+1
    val middle=if(cyclic) 500_000/count*count else 0
    val list=rememberLazyListState(initialFirstVisibleItemIndex=middle+value)
    val scope=rememberCoroutineScope()
    val currentChange by rememberUpdatedState(onChange)
    val rowHeight=with(LocalDensity.current) {24.sp.toDp()+24.dp}.coerceAtLeast(56.dp)
    val selectedIndex by remember {
        derivedStateOf {
            val info=list.layoutInfo
            val center=(info.viewportStartOffset+info.viewportEndOffset)/2
            info.visibleItemsInfo.minByOrNull {abs(it.offset+it.size/2-center)}?.index ?: middle+value
        }
    }
    val selected by remember {derivedStateOf {if(cyclic) Math.floorMod(selectedIndex,count) else selectedIndex}}
    LaunchedEffect(value) {
        if(!list.isScrollInProgress && selected!=value) list.scrollToItem(if(cyclic) selectedIndex-selected+value else value)
    }
    LaunchedEffect(list) {
        snapshotFlow {selected}.distinctUntilChanged().collect {currentChange(it)}
    }
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(rowHeight*3).clipToBounds().clearAndSetSemantics {
            contentDescription="${label}选择"
            stateDescription=description(selected)
            progressBarRangeInfo=ProgressBarRangeInfo(selected.toFloat(),0f..maximum.toFloat(),maximum-1)
            if(!enabled) disabled()
            setProgress {requested ->
                if(!enabled || !requested.isFinite()) false
                else {scope.launch {val target=requested.roundToInt().coerceIn(0,maximum);list.scrollToItem(if(cyclic) selectedIndex-selected+target else target)};true}
            }
        }) {
            HorizontalDivider(Modifier.align(Alignment.TopCenter).padding(top=rowHeight),color=MaterialTheme.colorScheme.outlineVariant)
            HorizontalDivider(Modifier.align(Alignment.TopCenter).padding(top=rowHeight*2),color=MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(vertical=rowHeight),
                flingBehavior=rememberSnapFlingBehavior(list),userScrollEnabled=enabled) {
                items(if(cyclic) 1_000_000 else count,key={it}) {index ->
                    val number=if(cyclic) index%count else index
                    Box(Modifier.fillMaxWidth().height(rowHeight).clickable(enabled=enabled) {scope.launch {list.animateScrollToItem(index)}},contentAlignment=Alignment.Center) {
                        Text(display(number),fontSize=textSize,maxLines=1,
                            fontWeight=if(number == selected) FontWeight.SemiBold else FontWeight.Normal,
                            color=if(number == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.45f))
                    }
                }
            }
        }
    }
}
