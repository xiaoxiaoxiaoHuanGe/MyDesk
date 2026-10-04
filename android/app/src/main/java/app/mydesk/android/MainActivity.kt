package app.mydesk.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity: ComponentActivity() {
    private var notificationLaunch by mutableStateOf<NotificationLaunch?>(null)
    private var launchSequence=0L
    private fun readNotification(intent: Intent) {
        if(intent.hasExtra("reminder_id") || intent.hasExtra("event_id") || intent.getBooleanExtra("open_workbench",false))
            notificationLaunch=NotificationLaunch(Intent(intent),++launchSequence)
    }
    private val notifyPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { graph.perform { graph.scheduler.apply(graph.store.visible()); if(graph.state.value.authenticated) graph.sync() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if(savedInstanceState == null || !savedInstanceState.getBoolean("notification_handled",true)) readNotification(intent)
        setContent {
            val state by graph.state.collectAsStateWithLifecycle()
            val dark=state.theme == "dark" || (state.theme == "system" && isSystemInDarkTheme())
            val colors=deskColors(dark)
            SideEffect {
                WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=!dark
                    isAppearanceLightNavigationBars=!dark
                }
            }
            MaterialTheme(colorScheme=colors,shapes=deskShapes,typography=deskTypography) {
                Surface(Modifier.fillMaxSize(),color=colors.background) {
                    if (!state.authenticated) LoginScreen(state) { server,user,password -> graph.perform { graph.login(server,user,password) } }
                    else Workbench(state,graph,notify={ if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotifications() },
                        exact={ if(Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) },
                        settings={openNotifications()},open={ url -> openUrl(url) },notificationLaunch=notificationLaunch,onNotificationHandled={notificationLaunch=null})
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readNotification(intent) }
    override fun onSaveInstanceState(outState: Bundle) {outState.putBoolean("notification_handled",notificationLaunch == null);super.onSaveInstanceState(outState)}
    override fun onStart() { super.onStart(); graph.startForeground(); graph.perform { graph.ready.await(); graph.scheduler.apply(graph.store.visible()) } }
    override fun onStop() { graph.stopForeground(); super.onStop() }
    private fun openNotifications() { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) }
    private fun openUrl(url: String) { val uri=Uri.parse(url); if (uri.scheme in setOf("https","http")) startActivity(Intent(Intent.ACTION_VIEW,uri)) }
}

@Composable private fun LoginScreen(state: HomeState,onLogin: (String,String,String)->Unit) {
    var server by rememberSaveable(state.server) { mutableStateOf(state.server) }
    var user by rememberSaveable { mutableStateOf("mydesk") }
    var password by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().imePadding().safeDrawingPadding(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Spacer(Modifier.height(24.dp)); DeskHeader("MyDesk","我的工作台 · 让今天的事情，清晰一点。") }
        item { DeskTextField(server,{server=it},label={Text("服务器地址")},placeholder={Text("https://电脑地址:8443")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri)) }
        item { DeskTextField(user,{user=it},label={Text("用户名")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item { DeskTextField(password,{password=it},label={Text("密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password)) }
        item { DeskButton(onClick={onLogin(server,user,password)},enabled=state.ready && !state.busy && server.isNotBlank() && password.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(state.busy) "正在登录…" else "登录 MyDesk") } }
        item { if (state.message.isNotEmpty()) Text(state.message,color=MaterialTheme.colorScheme.error); Text("连接你的 MyDesk 服务，账号与电脑工作台共用。",style=MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Workbench(state: HomeState,graph: AppGraph,notify: ()->Unit,exact: ()->Unit,settings: ()->Unit,open: (String)->Unit,notificationLaunch: NotificationLaunch?,onNotificationHandled: ()->Unit) {
    val pagerState=rememberPagerState(pageCount={3})
    val tab=pagerState.settledPage
    val pageScope=rememberCoroutineScope()
    val listStates=listOf(rememberLazyListState(),rememberLazyListState(),rememberLazyListState())
    val listState=listStates[tab]
    val settingsModel: SettingsViewModel=viewModel(key="settings:${state.server}",factory=remember(graph) {viewModelFactory {initializer {SettingsViewModel(graph)}}})
    val settingsState by settingsModel.state.collectAsStateWithLifecycle()
    val historyModel: TaskHistoryViewModel=viewModel(key="history:${state.server}",factory=remember(graph) {viewModelFactory {initializer {TaskHistoryViewModel(graph::loadHistoryPage)}}})
    val historyState by historyModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(tab,state.server) {if(tab == 2) settingsModel.reload()}
    var history by rememberSaveable { mutableStateOf(false) }
    var historyTask by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(history,historyTask) {if(history) historyModel.open(historyTask) else historyModel.clear()}
    DisposableEffect(historyModel) {onDispose {historyModel.clear()}}
    BackHandler(history) {history=false}
    var pendingScroll by remember {mutableStateOf<NotificationTarget?>(null)}
    var focusMessage by remember {mutableStateOf("")}
    var focusedReminder by rememberSaveable {mutableStateOf("")}
    var detailReminderId by rememberSaveable {mutableStateOf<String?>(null)}
    var businessDetail by remember {mutableStateOf<Pair<NotificationSection,JsonObject>?>(null)}
    var logout by remember { mutableStateOf(false) }
    val snapshot=state.snapshot ?: buildJsonObject { }
    val timezone=snapshot.text("timezone","Asia/Shanghai")
    var lastSettledPage by rememberSaveable {mutableIntStateOf(tab)}
    // A notification jump must keep its newly opened detail when the page settles.
    var notificationPage by remember {mutableStateOf<Int?>(null)}
    LaunchedEffect(tab) {
        if(lastSettledPage != tab) {
            lastSettledPage=tab
            if(notificationPage == tab) notificationPage=null
            else {
                history=false;pendingScroll=null;businessDetail=null;detailReminderId=null
                focusMessage="";focusedReminder=""
            }
        }
    }
    LaunchedEffect(notificationLaunch,state.snapshot) {
        val request=notificationLaunch ?: return@LaunchedEffect
        // Keep a notification pending across login/initial sync so references resolve against this account's cache.
        if(state.snapshot == null && (request.intent.getStringExtra("event_reference").orEmpty().isNotEmpty() || request.intent.getStringExtra("reminder_id").orEmpty().isNotEmpty())) return@LaunchedEffect
        val target=NotificationNavigation.target(request.intent,snapshot)
        val targetPage=if(target.section == NotificationSection.REMINDERS) 1 else 0
        if(pagerState.settledPage != targetPage) notificationPage=targetPage
        pagerState.scrollToPage(targetPage)
        history=false;focusMessage="";focusedReminder=if(targetPage == 1) target.reference else "";businessDetail=null
        detailReminderId=if(targetPage == 1) target.reference else null
        if(target.section == NotificationSection.SERVERS) snapshot.obj("feeds").obj("servers").obj("data").rows("items").firstOrNull {it.text("id","") == target.reference}?.let {businessDetail=target.section to it}
        if(target.section == NotificationSection.TASKS) snapshot.rows("tasks").firstOrNull {it.text("task_id","") == target.reference}?.let {businessDetail=target.section to it}
        pendingScroll=target
        onNotificationHandled()
    }
    LaunchedEffect(pendingScroll,tab,state.reminders) {
        val target=pendingScroll ?: return@LaunchedEffect
        if(tab != if(target.section == NotificationSection.REMINDERS) 1 else 0) return@LaunchedEffect
        val index=when(target.section) {
            NotificationSection.REMINDERS -> state.reminders.indexOfFirst {it.id == target.reference}.let {if(it < 0) {focusMessage="该提醒已被处理或尚未同步，当前显示最新提醒列表。";1} else it+2}
            NotificationSection.WORKBENCH -> 0
            NotificationSection.ATTENTION -> 1
            NotificationSection.TASKS -> 4
            NotificationSection.SERVERS -> 5
            NotificationSection.NETWORK -> 6
            NotificationSection.STEPS -> 2
            NotificationSection.MAIL -> 3
        }
        val actualIndex=index + if(index > 0 && (state.queued > 0 || state.message.isNotBlank() || focusMessage.isNotEmpty() || state.busy)) 1 else 0
        snapshotFlow {listState.layoutInfo.totalItemsCount}.first {it > actualIndex}
        listState.scrollToItem(actualIndex)
        pendingScroll=null
    }
    val detailReminder=state.reminders.firstOrNull {it.id == detailReminderId}
    val command: (String,JsonObject)->Unit={action,payload->graph.perform { graph.command(action,payload) }}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
        DeskMainTabs(pagerState.currentPage,onSelect={index->
            history=false;pendingScroll=null;businessDetail=null;detailReminderId=null;focusMessage="";focusedReminder=""
            pageScope.launch {pagerState.animateScrollToPage(index)}
        })
    }) { padding ->
        DeskMainPages(pagerState,Modifier.fillMaxSize().padding(padding).imePadding(),
            userScrollEnabled=businessDetail == null && detailReminder == null && !logout) {page->
            LazyColumn(Modifier.fillMaxSize(),state=listStates[page],contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {
                    val daily=snapshot.obj("daily_quote")
                    val showQuote=page==0 && !history
                    val quoteUrl=daily.text("url").takeIf {it.startsWith("https://hitokoto.cn/")}
                    DeskHeader(if(history && page == 0) "任务历史${historyTask?.let {id->" · "+(snapshot.rows("tasks").firstOrNull {it.text("task_id","") == id}?.text("task_name") ?: id)} ?: ""}" else listOf("MyDesk","即时提醒","MyDesk 设置")[page],
                        if(showQuote) daily.text("text").ifBlank {"把今天的事情，安静地安排好。"} else "",state.connected,
                        quoteCredit=if(showQuote && quoteUrl!=null) listOf(daily.text("author"),daily.text("source"),"一言").filter {it.isNotBlank()}.distinct().joinToString(" · ") else "",
                        onQuoteClick=if(showQuote && quoteUrl!=null) ({open(quoteUrl)}) else null)
                }
                if(state.queued > 0 || state.message.isNotBlank() || focusMessage.isNotEmpty() || state.busy) item {
                    if(state.queued > 0) Text("${state.queued} 项操作等待同步",color=MaterialTheme.colorScheme.primary)
                    if(state.message.isNotBlank()) Text(state.message,color=MaterialTheme.colorScheme.primary)
                    if(focusMessage.isNotEmpty()) Text(focusMessage,color=MaterialTheme.colorScheme.primary)
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                }
                when {
                    history && page == 0 -> {
                        item { TextButton({history=false}) { Text("返回工作台") } }
                        if(historyState.error.isNotEmpty()) item {Text(historyState.error,color=MaterialTheme.colorScheme.error);TextButton({if(historyState.rows.isEmpty()) historyModel.open(historyTask) else historyModel.loadMore()},enabled=!historyState.busy) {Text("重试")}}
                        if(historyState.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
                        if(!historyState.busy && historyState.error.isEmpty() && historyState.rows.isEmpty()) item {Text("该范围内暂无历史记录")}
                        items(historyState.rows,key={it.text("id")}) { row -> DeskCard(row.text("task_name")) { Text(status(row.text("status"))); Text(row.text("message")); Text(date(row.text("timestamp"),timezone),style=MaterialTheme.typography.bodySmall) } }
                        if(historyState.hasMore) item { TextButton(historyModel::loadMore,enabled=!historyState.busy) { Text("加载更早记录") } }
                    }
                    page == 2 -> {
                        item { AccountSettingsCard(state.server,state.deviceName,state.busy || settingsState.busy,state.syncing,
                            sync={graph.perform {graph.syncAll();settingsModel.reload().join()}},logout={logout=true},
                            rename=settingsModel::renameDevice,changePassword={current,password->graph.perform {graph.changePassword(current,password)}},
                            error=if(settingsState.failed) settingsState.message else "",passwordError=state.message) }
                        item { NotificationSettings(graph,state,notify,exact,settings) }
                        item { WorkbenchSettings(settingsState.config,state.busy || settingsState.busy,settingsModel::save,if(settingsState.failed) settingsState.message else "") }
                        item { IntegrationSettings(settingsState.config,state.busy || settingsState.busy,settingsModel::save,if(settingsState.failed) settingsState.message else "",checkTask=settingsModel::checkGitHubTask,feedback=settingsState.message,checkService=settingsModel::checkService) }
                        item { BackupSettings(graph,settingsModel::reload,enabled=!state.busy && !settingsState.busy) }
                    }
                    page == 1 -> {
                        item { ReminderForm(state.busy,timezone) {title,time->command("reminder/create",buildJsonObject { put("title",title); put("time",time) }) } }
                        items(state.reminders,key={it.id}) { reminder -> ReminderCard(reminder,timezone,state.busy,reminder.id == focusedReminder,detail={detailReminderId=reminder.id}) {action,minutes->graph.perform { graph.action(reminder,action,minutes) }} }
                        if(state.reminders.isEmpty()) item { DeskCard("还没有待办提醒") { Text("想到一件事，就给它设个时间。") } }
                    }
                    else -> {
                        item { WorkbenchAttentionCard(snapshot.rows("attention"),state.snapshot != null) }
                        item { StepsCard(snapshot,state.busy,command) }
                        item { MailCard(snapshot,timezone) }
                        item { WorkbenchTaskCard(snapshot.rows("tasks"),timezone,{businessDetail=NotificationSection.TASKS to it},{historyTask=null;history=true}) }
                        item { ServerCard(snapshot,timezone) }
                        item { NetworkCard(snapshot,timezone) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
    businessDetail?.let {(section,value) ->
        if(section == NotificationSection.SERVERS) ServerDetail(value,timezone) {businessDetail=null}
        else TaskDetail(value,timezone,onClose={businessDetail=null},history={businessDetail=null;historyTask=value.text("task_id","");history=true})
    }
    detailReminder?.let {reminder->
        ReminderDetailSheet(reminder,timezone,state.busy,onDismiss={detailReminderId=null}) {current,action,minutes->
            detailReminderId=null
            graph.perform {graph.action(current,action,minutes)}
        }
    }
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text("退出 MyDesk？")},text={Text((if(state.queued > 0) "有 ${state.queued} 项操作尚未同步。退出会丢弃这些操作，并清理本机提醒与缓存。" else "退出后将清理本机提醒和缓存。")+"\n\n服务器保存的任务、Token 和其他配置会保留，重新登录后恢复。")},confirmButton={TextButton({logout=false; graph.perform { graph.logout() }}) {Text("退出")}},dismissButton={TextButton({logout=false}) {Text("继续使用")}})
}

@Composable internal fun DeskCard(title: String,headerAction: (@Composable ()->Unit)?=null,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(title.isNotBlank()) Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(title,modifier=Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                headerAction?.invoke()
            }
            content()
        }
    }
}

@Composable internal fun ReminderForm(busy: Boolean,timezone: String,create: (String,String)->Unit) {
    val zone=remember(timezone) {ZoneId.of(timezone)}
    val initial=remember(timezone) {Instant.now().atZone(zone)}
    var clockMillis by remember(timezone) {mutableLongStateOf(System.currentTimeMillis())}
    var title by rememberSaveable {mutableStateOf("")}
    var dayEpoch by rememberSaveable(timezone) {mutableLongStateOf(initial.toLocalDate().toEpochDay())}
    val initialClock=if(initial.plusMinutes(1).toLocalDate() == initial.toLocalDate()) initial.plusMinutes(1).toLocalTime() else java.time.LocalTime.of(23,59)
    var hours by rememberSaveable(timezone) {mutableIntStateOf(initialClock.hour)}
    var minutes by rememberSaveable(timezone) {mutableIntStateOf(initialClock.minute)}
    var countdown by rememberSaveable {mutableStateOf(false)}
    var durationHours by rememberSaveable {mutableIntStateOf(0)}
    var durationMinutes by rememberSaveable {mutableIntStateOf(15)}
    var durationSeconds by rememberSaveable {mutableIntStateOf(0)}
    LaunchedEffect(timezone) {while(true) {kotlinx.coroutines.delay(30_000);clockMillis=System.currentTimeMillis()}}
    val today=Instant.ofEpochMilli(clockMillis).atZone(zone).toLocalDate()
    val selectedDate=java.time.LocalDate.ofEpochDay(dayEpoch.coerceAtLeast(today.toEpochDay()))
    val local=selectedDate.atTime(hours,minutes)
    val selected=local.atZone(zone)
    val duration=durationHours*3600L+durationMinutes*60L+durationSeconds
    val valid=if(countdown) duration > 0 else selected.toLocalDateTime() == local && selected.toInstant().isAfter(Instant.ofEpochMilli(clockMillis))
    DeskCard("") {
        DeskTextField(title,{title=it},label={Text("提醒事项")},enabled=!busy,modifier=Modifier.fillMaxWidth())
        key(countdown) {
            if(countdown) DeskCountdownPicker(durationHours,durationMinutes,durationSeconds,!busy,{durationHours=it},{durationMinutes=it},{durationSeconds=it})
            else DeskReminderTimePicker(today,(selectedDate.toEpochDay()-today.toEpochDay()).toInt(),hours,minutes,!busy,
                {dayEpoch=today.plusDays(it.toLong()).toEpochDay()},{hours=it},{minutes=it})
        }
        Text(if(countdown) {
            if(valid) java.time.LocalTime.of(durationHours,durationMinutes,durationSeconds).format(DateTimeFormatter.ofPattern("HH:mm:ss"))+" 后提醒" else "请选择大于零的倒计时"
        } else if(valid) local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) else "请选择未来的有效时间",
            modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.bodySmall,color=if(valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            DeskOutlinedButton({countdown=!countdown},modifier=Modifier.weight(1f),enabled=!busy) {Text(if(countdown) "切换日期" else "切换倒计时")}
            DeskButton({
                val now=Instant.now()
                if(countdown && duration > 0) create(title,now.plusSeconds(duration).toString())
                else if(!countdown && selected.toLocalDateTime() == local && selected.toInstant().isAfter(now)) create(title,selected.toInstant().toString())
                else clockMillis=now.toEpochMilli()
            },modifier=Modifier.weight(1f),enabled=!busy && title.isNotBlank() && valid) {Text("创建提醒")}
        }
    }
}

@Composable private fun NotificationSettings(graph: AppGraph,state: HomeState,notify: ()->Unit,exact: ()->Unit,settings: ()->Unit) {
    val context=graph.context
    val manager=context.getSystemService(NotificationManager::class.java)
    val alarms=context.getSystemService(AlarmManager::class.java)
    val notificationsAllowed=manager.areNotificationsEnabled()
    val exactAllowed=Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    val channel=manager.getNotificationChannel("reminders")
    val channelReady=(channel?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH
    val pushReady=graph.push.configured && state.pushStatus?.get("push_available")?.jsonPrimitive?.booleanOrNull == true &&
        state.pushStatus?.get("token_registered_locally")?.jsonPrimitive?.booleanOrNull == true
    NotificationSettingsCard(notificationsAllowed,exactAllowed,channelReady,pushReady,notify,exact,settings) {
        ThemePreference(state.theme,graph::setTheme)
    }
}

@Composable internal fun NotificationSettingsCard(notificationsAllowed: Boolean,exactAllowed: Boolean,channelReady: Boolean,pushReady: Boolean,
    notify: ()->Unit,exact: ()->Unit,settings: ()->Unit,appearance: (@Composable ()->Unit)?=null) {
    DeskCard("通知与外观") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(SettingsGlyphs.Bell,null,modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("手机通知",style=MaterialTheme.typography.titleSmall)
                Text(when {
                    !notificationsAllowed -> "通知未开启"
                    !exactAllowed -> "准时提醒未授权"
                    !channelReady -> "提醒通知需检查"
                    else -> "定时提醒已就绪"
                },style=MaterialTheme.typography.bodyMedium)
                Text(if(pushReady) "即时告警已连接" else "即时告警未启用",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DeskIconAction("通知设置",Icons.Default.Settings,settings)
        }
        if(!notificationsAllowed) DeskOutlinedButton(notify,modifier=Modifier.fillMaxWidth()) {Text("开启通知")}
        if(!exactAllowed) DeskOutlinedButton(exact,modifier=Modifier.fillMaxWidth()) {Text("允许准时提醒")}
        if(appearance!=null) {
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            appearance()
        }
    }
}

internal fun date(value: String,zone: String): String = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of(zone)).format(Instant.parse(value)) }.getOrDefault("尚未同步")
internal fun status(value: String) = mapOf("pending" to "等待提醒","snoozed" to "已延后","completed" to "已完成","failed" to "失败","warning" to "需留意","unknown" to "未知","success" to "成功","running" to "执行中","queued" to "排队中","dispatching" to "提交中","tracking_error" to "跟踪异常","up" to "在线","down" to "离线")[value] ?: value
