package app.mydesk.android

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BackgroundReceivingTest {
    private fun clear()=RuntimeEnvironment.getApplication().getSharedPreferences("background-receiving",0).edit().clear().commit()
    @Test fun optInPersistsStartsOneForegroundServiceAndTurningOffReleasesItsConnection() {
        clear()
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val owners=mutableListOf<Boolean>()
        val receiving=BackgroundReceiving(context,{true},{owners+=it})
        assertFalse(receiving.state.value.enabled)
        receiving.enable(true)
        assertTrue(receiving.state.value.enabled)
        assertEquals(BackgroundReceiveService::class.java.name,shadowOf(context).nextStartedService.component!!.className)
        assertTrue(BackgroundReceiving(context,{true},{}).state.value.enabled)
        assertTrue(receiving.attached())
        assertEquals(listOf(true),owners)
        assertTrue(receiving.state.value.active)
        receiving.enable(false)
        assertFalse(receiving.state.value.enabled)
        assertFalse(receiving.state.value.active)
        assertEquals(listOf(true,false),owners)
    }
    @Test fun disabledNotificationsBlockOptInAndReportAnActionableError() {
        clear()
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val receiving=BackgroundReceiving(context,{true},{})
        receiving.enable(true)
        assertFalse(receiving.state.value.enabled)
        assertTrue(receiving.state.value.error.contains("通知"))
        assertNull(shadowOf(context).nextStartedService)
    }
    @Test fun logoutStopsReceptionAndCannotRestartItWhileLoggedOut() {
        clear()
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        var authenticated=true
        val owners=mutableListOf<Boolean>()
        val receiving=BackgroundReceiving(context,{authenticated},{owners+=it})
        receiving.enable(true);shadowOf(context).nextStartedService
        receiving.attached();authenticated=false
        receiving.suspendReceiving()
        assertFalse(receiving.state.value.active)
        assertFalse(owners.last())
        receiving.startIfEnabled()
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(receiving.attached())
    }
    @Test fun statusNotificationIsSilentPrivateOngoingAndHasAStopAction() {
        val context=RuntimeEnvironment.getApplication()
        val notification=BackgroundReceiveService.notification(context,false)
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel=manager.getNotificationChannel(notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW,channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(notification.fullScreenIntent)
        assertEquals(BackgroundReceiveService.STOP,shadowOf(notification.actions.single().actionIntent).savedIntent.action)
        val info=context.packageManager.getServiceInfo(ComponentName(context,BackgroundReceiveService::class.java),0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,info.foregroundServiceType)
    }
}
