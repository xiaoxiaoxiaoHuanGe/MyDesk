package app.mydesk.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

class AlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        ReminderDiagnostics.event("received","foreground=${intent.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0}")
        val pending=goAsync()
        context.graph.scope.launch {
            try {
                val graph=context.graph
                ReminderDiagnostics.event("await_ready")
                graph.ready.await()
                ReminderDiagnostics.event("ready","authenticated=${graph.state.value.authenticated}")
                if (graph.state.value.authenticated) {
                    val reminders=graph.store.visible()
                    ReminderDiagnostics.event("cache_loaded","count=${reminders.size}")
                    var displayed=0
                    reminders.forEach {if(graph.scheduler.deliver(it.id,it.revision)) displayed++}
                    ReminderDiagnostics.event("delivery_done","display_requests=$displayed")
                    graph.scheduler.apply(graph.store.visible())
                } else graph.scheduler.cancelAll()
            } catch(error: Exception) {
                ReminderDiagnostics.event("failed","error_type=${error.javaClass.simpleName}")
                throw error
            } finally {pending.finish();ReminderDiagnostics.event("finished")}
        }
    }
}

class NotificationActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        context.graph.scope.launch {
            try {
                val graph=context.graph
                graph.ready.await()
                if (!graph.state.value.authenticated) return@launch
                val action=NotificationInput.action(intent) ?: return@launch
                runCatching { graph.repo.act(action.id,action.revision,action.action,action.minutes) }
                graph.requestSync()
            } finally { pending.finish() }
        }
    }
}

class RescheduleReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        context.graph.scope.launch {
            try {
                val graph=context.graph
                graph.ready.await()
                if (graph.state.value.authenticated) { graph.scheduler.apply(graph.store.visible()); graph.requestSync() }
                else graph.scheduler.cancelAll()
            } finally { pending.finish() }
        }
    }
}
