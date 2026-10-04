package app.mydesk.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

internal object DeskNotificationChannels {
    fun create(context: Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        val audio=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        listOf(Triple("reminders","即时提醒",NotificationManager.IMPORTANCE_HIGH),Triple("alerts","重要告警",NotificationManager.IMPORTANCE_HIGH),Triple("tasks","任务结果",NotificationManager.IMPORTANCE_DEFAULT)).forEach {(id,name,importance)->
            val channel=manager.getNotificationChannel(id) ?: NotificationChannel(id,name,importance).apply {
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI,audio)
                enableVibration(id != "tasks")
            }
            channel.description=when(id) {"reminders"->"到期提醒、完成与延后操作；开启系统横幅可在使用其他应用时弹出。";"alerts"->"服务器和网络异常、任务失败等重要告警。";else->"自动任务的正常执行结果。"}
            manager.createNotificationChannel(channel)
        }
    }
}
