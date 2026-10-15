package app.mydesk.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class BackgroundReceiveState(val enabled: Boolean=false,val active: Boolean=false,val starting: Boolean=false,val error: String="")

/** User opt-in persists locally; only a visible Activity starts the foreground service. */
internal class BackgroundReceiving(private val context: Context,private val authenticated: ()->Boolean,private val retain: (Boolean)->Unit) {
    private val prefs=context.getSharedPreferences("background-receiving",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(BackgroundReceiveState(enabled=prefs.getBoolean("enabled",false)))
    val state=mutable.asStateFlow()
    private fun permitted(): Boolean {
        DeskNotificationChannels.create(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(DeskNotificationChannels.RECEIVER)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    @Synchronized fun enable(value: Boolean) {
        if(value && !authenticated()) {failed("请先登录再开启后台接收");return}
        if(value && !permitted()) {failed("请先开启手机通知和后台接收状态通知");return}
        check(prefs.edit().putBoolean("enabled",value).commit()) {"后台接收设置未能保存，请重试"}
        mutable.update {it.copy(enabled=value,error="")}
        if(value) startIfEnabled() else suspendReceiving()
    }
    @Synchronized fun startIfEnabled() {
        if(!state.value.enabled || !authenticated() || state.value.active || state.value.starting) return
        if(!permitted()) {failed("请先开启手机通知和后台接收状态通知");return}
        mutable.update {it.copy(starting=true,error="")}
        try {context.startForegroundService(Intent(context,BackgroundReceiveService::class.java))}
        catch(_: RuntimeException) {failed("后台接收未能启动，请在 APP 前台重试")}
    }
    @Synchronized fun attached(): Boolean {
        if(!state.value.enabled || !authenticated()) return false
        if(!permitted()) {failed("手机通知已关闭，请开启后重试后台接收");return false}
        retain(true)
        mutable.update {it.copy(active=true,starting=false,error="")}
        return true
    }
    @Synchronized fun detached() {
        retain(false)
        mutable.update {it.copy(active=false,starting=false)}
    }
    @Synchronized fun suspendReceiving() {
        detached()
        context.stopService(Intent(context,BackgroundReceiveService::class.java))
    }
    @Synchronized fun failed(message: String) {mutable.update {it.copy(active=false,starting=false,error=message)}}
}
