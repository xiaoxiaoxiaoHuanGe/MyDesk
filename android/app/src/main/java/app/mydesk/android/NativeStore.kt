package app.mydesk.android

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

class NativeStore(context: Context, memory: Boolean = false) {
    private val db=if (memory) Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
                   else Room.databaseBuilder(context,DeskDatabase::class.java,"mydesk.db").addMigrations(object: Migration(1,2) {
                       override fun migrate(db: SupportSQLiteDatabase) {
                           db.execSQL("CREATE TABLE IF NOT EXISTS inbox_read (operationId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
                           db.execSQL("CREATE TABLE IF NOT EXISTS inbox_cache (id TEXT NOT NULL PRIMARY KEY, seq INTEGER NOT NULL, json TEXT NOT NULL)")
                       }
                   }).build()
    private val dao=db.desk()
    val state=combine(dao.watchSnapshot(),dao.watchActions(),dao.watchReminders(),dao.watchInboxReads()) { snapshot, actions, reminders, reads ->
        CacheState(snapshot?.let {applyInboxReads(deskJson.parseToJsonElement(it.json).jsonObject,reads.map {row->deskJson.parseToJsonElement(row.json).jsonObject})}, ReminderPolicy.visible(reminders.map { deskJson.decodeFromString(it.json) }, actions.map { deskJson.decodeFromString(it.json) }), actions.size+reads.size)
    }
    suspend fun accept(snapshot: String) = db.withTransaction {
        val data=deskJson.parseToJsonElement(snapshot).jsonObject
        val reminders=deskJson.decodeFromJsonElement<List<Reminder>>(data["reminders"] ?: JsonArray(emptyList()))
        for(row in data.obj("inbox").rows("recent")) {
            val old=dao.inboxMessage(row.text("id"))?.let {deskJson.parseToJsonElement(it.json).jsonObject}
            val merged=buildJsonObject {old?.forEach {(k,v)->put(k,v)};row.forEach {(k,v)->put(k,v)}}
            dao.inboxCache(InboxCacheRow(row.text("id"),row.text("seq").toLong(),merged.toString()))
        }
        dao.trimInbox()
        dao.snapshot(SnapshotRow(json=snapshot))
        dao.clearReminders()
        dao.reminders(reminders.map { ReminderRow(it.id,deskJson.encodeToString(it)) })
    }
    suspend fun visible(): List<Reminder> = ReminderPolicy.visible(dao.reminders().map { deskJson.decodeFromString(it.json) }, pending())
    suspend fun enqueue(id: String, revision: String, action: String, minutes: Int = 10): PendingAction = db.withTransaction {
        require(action in setOf("complete","cancel","snooze") && minutes in setOf(10,30,60))
        val reminder=visible().firstOrNull { it.id == id && it.revision == revision }
        require(reminder != null) { "提醒已更新或处理，请查看当前状态" }
        val value=PendingAction(UUID.randomUUID().toString(),id,revision,action,minutes,Instant.now().toString())
        dao.action(ActionRow(operationId=value.operationId,json=deskJson.encodeToString(value)))
        value
    }
    suspend fun pending(): List<PendingAction> = dao.actions().map { deskJson.decodeFromString(it.json) }
    suspend fun receipt(operationId: String, result: Reminder) = db.withTransaction {
        val remaining=ReminderPolicy.acknowledge(pending(),operationId,result.revision)
        dao.deleteAction(operationId)
        remaining.forEach { dao.updateAction(it.operationId,deskJson.encodeToString(it)) }
        dao.reminders(listOf(ReminderRow(result.id,deskJson.encodeToString(result))))
    }
    suspend fun conflict(id: String) = db.withTransaction {
        pending().filter { it.reminderId == id }.forEach { dao.deleteAction(it.operationId) }
    }
    suspend fun enqueueInboxRead(payload: JsonObject)=db.withTransaction {
        dao.inboxRead(InboxReadRow(UUID.randomUUID().toString(),payload.toString()))
    }
    suspend fun inboxPending()=dao.inboxReads()
    suspend fun inboxReceipt(id: String) {dao.deleteInboxRead(id)}
    suspend fun cacheInbox(rows: List<JsonObject>)=db.withTransaction {
        rows.forEach {row->dao.inboxCache(InboxCacheRow(row.text("id"),row.text("seq").toLong(),row.toString()))}
        dao.trimInbox()
    }
    suspend fun inboxCache(): List<JsonObject> = dao.inboxCache().map {deskJson.parseToJsonElement(it.json).jsonObject}
    suspend fun inboxMessage(id: String): JsonObject?=dao.inboxMessage(id)?.let {deskJson.parseToJsonElement(it.json).jsonObject}
    suspend fun delivered(id: String): String? = dao.delivery(id)?.revision
    suspend fun markDelivered(id: String, revision: String) = dao.delivery(DeliveryRow(id,revision))
    suspend fun clear() = db.withTransaction { dao.clearActions(); dao.clearReminders(); dao.clearSnapshot(); dao.clearDeliveries();dao.clearInboxReads();dao.clearInboxCache() }
    fun close() = db.close()
}

internal fun applyInboxReads(snapshot: JsonObject,reads: List<JsonObject>): JsonObject {
    if(reads.isEmpty())return snapshot
    val inbox=snapshot.obj("inbox");val latest=inbox.text("latest_seq","0").toLongOrNull() ?: 0
    val all=reads.any {it["through_seq"]?.jsonPrimitive?.longOrNull?.let {seq->seq>=latest}==true}
    val changed=inbox.rows("recent").count {row->row.text("read_at")=="null"&&reads.any {inboxReadMatches(row,it)}}
    return buildJsonObject {
        snapshot.forEach {(k,v)->put(k,v)}
        put("inbox",buildJsonObject {
            inbox.forEach {(k,v)->put(k,v)}
            put("unread",if(all) 0 else ((inbox.text("unread","0").toIntOrNull() ?: 0)-changed).coerceAtLeast(0))
            put("recent",JsonArray(inbox.rows("recent").map {row->if(reads.any {inboxReadMatches(row,it)}) buildJsonObject {row.forEach {(k,v)->put(k,v)};put("read_at","pending")} else row}))
        })
    }
}
data class CacheState(val snapshot: JsonObject?,val reminders: List<Reminder>,val queued: Int)
@Entity(tableName="snapshot") data class SnapshotRow(@PrimaryKey val id: Int=1,val json: String)
@Entity(tableName="reminders") data class ReminderRow(@PrimaryKey val id: String,val json: String)
@Entity(tableName="outbox",indices=[Index(value=["operationId"],unique=true)])
data class ActionRow(@PrimaryKey(autoGenerate=true) val sequence: Long=0,val operationId: String,val json: String)
@Entity(tableName="deliveries") data class DeliveryRow(@PrimaryKey val id: String,val revision: String)
@Entity(tableName="inbox_read") data class InboxReadRow(@PrimaryKey val operationId: String,val json: String)
@Entity(tableName="inbox_cache") data class InboxCacheRow(@PrimaryKey val id: String,val seq: Long,val json: String)
@Dao interface DeskDao {
    @Query("SELECT * FROM inbox_read ORDER BY rowid") fun watchInboxReads(): Flow<List<InboxReadRow>>
    @Query("SELECT * FROM inbox_read ORDER BY rowid") suspend fun inboxReads(): List<InboxReadRow>
    @Insert suspend fun inboxRead(row: InboxReadRow)
    @Query("DELETE FROM inbox_read WHERE operationId=:id") suspend fun deleteInboxRead(id: String)
    @Query("DELETE FROM inbox_read") suspend fun clearInboxReads()
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun inboxCache(row: InboxCacheRow)
    @Query("SELECT * FROM inbox_cache ORDER BY seq DESC") suspend fun inboxCache(): List<InboxCacheRow>
    @Query("SELECT * FROM inbox_cache WHERE id=:id") suspend fun inboxMessage(id: String): InboxCacheRow?
    @Query("DELETE FROM inbox_cache WHERE seq IN (SELECT seq FROM inbox_cache ORDER BY seq DESC LIMIT -1 OFFSET 10000)") suspend fun trimInbox()
    @Query("DELETE FROM inbox_cache") suspend fun clearInboxCache()
    @Query("SELECT * FROM snapshot WHERE id=1") fun watchSnapshot(): Flow<SnapshotRow?>
    @Query("SELECT * FROM outbox ORDER BY sequence") fun watchActions(): Flow<List<ActionRow>>
    @Query("SELECT * FROM reminders") fun watchReminders(): Flow<List<ReminderRow>>
    @Query("SELECT * FROM reminders") suspend fun reminders(): List<ReminderRow>
    @Query("SELECT * FROM outbox ORDER BY sequence") suspend fun actions(): List<ActionRow>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun snapshot(row: SnapshotRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun reminders(rows: List<ReminderRow>)
    @Insert suspend fun action(row: ActionRow)
    @Query("UPDATE outbox SET json=:json WHERE operationId=:id") suspend fun updateAction(id: String,json: String)
    @Query("DELETE FROM outbox WHERE operationId=:id") suspend fun deleteAction(id: String)
    @Query("SELECT * FROM deliveries WHERE id=:id") suspend fun delivery(id: String): DeliveryRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun delivery(row: DeliveryRow)
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Query("DELETE FROM outbox") suspend fun clearActions()
    @Query("DELETE FROM snapshot") suspend fun clearSnapshot()
    @Query("DELETE FROM deliveries") suspend fun clearDeliveries()
}
@Database(entities=[SnapshotRow::class,ReminderRow::class,ActionRow::class,DeliveryRow::class,InboxReadRow::class,InboxCacheRow::class],version=2,exportSchema=false)
abstract class DeskDatabase: RoomDatabase() { abstract fun desk(): DeskDao }
