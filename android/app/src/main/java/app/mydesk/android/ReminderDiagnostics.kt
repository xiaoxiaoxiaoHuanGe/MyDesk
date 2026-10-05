package app.mydesk.android

import android.util.Log

/** Privacy-safe stage markers for diagnosing background delivery on signed builds.
 * Callers must never pass titles, IDs, URLs, credentials, or payloads. */
internal object ReminderDiagnostics {
    fun event(stage: String,details: String="") {
        Log.i("MyDeskReminder","stage=$stage at_ms=${System.currentTimeMillis()} $details")
    }
}
