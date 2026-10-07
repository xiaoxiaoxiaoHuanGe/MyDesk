package app.mydesk.android

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class InboxMigrationTest {
    @Test fun versionOneDatabaseUpgradePreservesRemindersQueuedOperationsAndDeliveries()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val path=context.getDatabasePath("mydesk.db");path.parentFile!!.mkdirs();context.deleteDatabase("mydesk.db")
        val reminder=Reminder("r1","旧提醒","2026-10-08T08:00:00Z","pending","rev")
        val action=PendingAction("old-op","r1","rev","snooze",30,"2026-10-07T08:00:00Z")
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            db.execSQL("CREATE TABLE snapshot (id INTEGER NOT NULL PRIMARY KEY,json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE reminders (id TEXT NOT NULL PRIMARY KEY,json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE outbox (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,operationId TEXT NOT NULL,json TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_outbox_operationId ON outbox (operationId)")
            db.execSQL("CREATE TABLE deliveries (id TEXT NOT NULL PRIMARY KEY,revision TEXT NOT NULL)")
            db.execSQL("INSERT INTO snapshot VALUES (1,?)",arrayOf("{\"reminders\":[],\"timezone\":\"Asia/Shanghai\"}"))
            db.execSQL("INSERT INTO reminders VALUES (?,?)",arrayOf(reminder.id,deskJson.encodeToString(reminder)))
            db.execSQL("INSERT INTO outbox(operationId,json) VALUES (?,?)",arrayOf(action.operationId,deskJson.encodeToString(action)))
            db.execSQL("INSERT INTO deliveries VALUES ('r1','rev')")
            db.version=1
        }
        val store=NativeStore(context)
        try {
            assertEquals(action,store.pending().single())
            assertEquals("旧提醒",store.visible().single().title)
            assertEquals("rev",store.delivered("r1"))
            assertTrue(store.inboxCache().isEmpty());assertTrue(store.inboxPending().isEmpty())
        } finally {store.close();context.deleteDatabase("mydesk.db")}
    }
}
