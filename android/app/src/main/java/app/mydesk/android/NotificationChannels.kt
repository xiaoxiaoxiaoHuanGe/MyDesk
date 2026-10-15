package app.mydesk.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.app.Notification
import android.provider.Settings

internal object DeskNotificationChannels {
    const val INBOX="inbox_messages"
    const val RECEIVER="background_receiving"
    fun create(context: Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        val audio=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        listOf(Triple("reminders","即时提醒",NotificationManager.IMPORTANCE_HIGH),Triple("alerts","重要告警",NotificationManager.IMPORTANCE_HIGH),Triple("tasks","任务结果",NotificationManager.IMPORTANCE_DEFAULT),Triple(INBOX,"外部通知",NotificationManager.IMPORTANCE_HIGH)).forEach {(id,name,importance)->
            val legacy=if(id==INBOX)manager.getNotificationChannel("inbox") else null
            val preserve=legacy!=null && (legacy.importance!=NotificationManager.IMPORTANCE_DEFAULT || (Build.VERSION.SDK_INT>=29 && legacy.hasUserSetImportance()))
            val channel=manager.getNotificationChannel(id) ?: NotificationChannel(id,name,if(preserve)legacy!!.importance else importance).apply {
                setSound(if(legacy!=null)legacy.sound else Settings.System.DEFAULT_NOTIFICATION_URI,audio)
                enableVibration(legacy?.shouldVibrate() ?: (id != "tasks"))
                lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            }
            channel.description=when(id) {"reminders"->"到期提醒、完成与延后操作；开启系统横幅可在使用其他应用时弹出。";"alerts"->"服务器和网络异常、任务失败等重要告警。";INBOX->"最近通知的新消息、Codex 重置与 CPE 短信；支持通知栏、横幅和锁屏提醒。";else->"自动任务的正常执行结果。"}
            manager.createNotificationChannel(channel)
        }
        if(manager.getNotificationChannel(RECEIVER)==null) manager.createNotificationChannel(
            NotificationChannel(RECEIVER,"后台接收状态",NotificationManager.IMPORTANCE_LOW).apply {
                description="后台实时接收的常驻连接状态；新消息通过外部通知频道提醒。"
                setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            })
    }
}
