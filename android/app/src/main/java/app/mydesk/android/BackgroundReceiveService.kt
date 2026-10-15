package app.mydesk.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class BackgroundReceiveService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var watcher: Job?=null
    override fun onCreate() {
        super.onCreate()
        try {
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notification(this,false),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION,notification(this,false))
        } catch(_: RuntimeException) {
            graph.backgroundReceiving.failed("后台接收未能启动，请检查系统通知和后台运行设置")
            stopSelf();return
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {graph.backgroundReceiving.enable(false);stopSelf();return START_NOT_STICKY}
        scope.launch {
            graph.ready.await()
            if(!graph.backgroundReceiving.state.value.active && !graph.backgroundReceiving.attached()) {stopSelf();return@launch}
            if(watcher?.isActive!=true) watcher=scope.launch {
                graph.state.map {it.authenticated to it.realtimeConnected}.distinctUntilChanged().collect {(authenticated,connected)->
                    if(!authenticated || !graph.backgroundReceiving.state.value.enabled || !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                        stopSelf()
                    } else getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(this@BackgroundReceiveService,connected))
                }
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        graph.backgroundReceiving.detached()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onTimeout(startId: Int,fgsType: Int) {stopSelf()}
    companion object {
        const val STOP="app.mydesk.android.STOP_BACKGROUND_RECEIVING"
        private const val NOTIFICATION=240031
        internal fun notification(context: Context,connected: Boolean): Notification {
            DeskNotificationChannels.create(context)
            val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open=PendingIntent.getActivity(context,NOTIFICATION,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),flags)
            val stop=PendingIntent.getService(context,NOTIFICATION,Intent(context,BackgroundReceiveService::class.java).setAction(STOP),flags)
            return Notification.Builder(context,DeskNotificationChannels.RECEIVER)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("MyDesk 后台实时接收")
                .setContentText(if(connected) "已连接，正在接收最近通知" else "正在连接，网络恢复后自动重试")
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(open)
                .addAction(Notification.Action.Builder(null,"停止接收",stop).build()).build()
        }
    }
}
