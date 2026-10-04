package app.mydesk.android

import android.util.Log

/** Debug-only stage markers. Callers must never pass titles, IDs, URLs, credentials, or payloads. */
internal object ReminderDiagnostics {
    fun event(stage: String,details: String="") {
        if(BuildConfig.DEBUG) Log.i("MyDeskReminder","stage=$stage at_ms=${System.currentTimeMillis()} $details")
    }
}
