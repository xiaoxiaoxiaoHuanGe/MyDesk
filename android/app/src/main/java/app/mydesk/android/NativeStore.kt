package app.mydesk.android

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

class NativeStore(context: Context, memory: Boolean = false) {
    private val db=if (memory) Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
                   else Room.databaseBuilder(context,DeskDatabase::class.java,"mydesk.db").build()
    private val dao=db.desk()
    val state=combine(dao.watchSnapshot(),dao.watchActions(),dao.watchReminders()) { snapshot, actions, reminders ->
        CacheState(snapshot?.let { deskJson.parseToJsonElement(it.json).jsonObject }, ReminderPolicy.visible(reminders.map { deskJson.decodeFromString(it.json) }, actions.map { deskJson.decodeFromString(it.json) }), actions.size)
    }
    suspend fun accept(snapshot: String) = db.withTransaction {
        val data=deskJson.parseToJsonElement(snapshot).jsonObject
        val reminders=deskJson.decodeFromJsonElement<List<Reminder>>(data["reminders"] ?: JsonArray(emptyList()))
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
    suspend fun delivered(id: String): String? = dao.delivery(id)?.revision
    suspend fun markDelivered(id: String, revision: String) = dao.delivery(DeliveryRow(id,revision))
    suspend fun clear() = db.withTransaction { dao.clearActions(); dao.clearReminders(); dao.clearSnapshot(); dao.clearDeliveries() }
    fun close() = db.close()
}

data class CacheState(val snapshot: JsonObject?,val reminders: List<Reminder>,val queued: Int)
@Entity(tableName="snapshot") data class SnapshotRow(@PrimaryKey val id: Int=1,val json: String)
@Entity(tableName="reminders") data class ReminderRow(@PrimaryKey val id: String,val json: String)
@Entity(tableName="outbox",indices=[Index(value=["operationId"],unique=true)])
data class ActionRow(@PrimaryKey(autoGenerate=true) val sequence: Long=0,val operationId: String,val json: String)
@Entity(tableName="deliveries") data class DeliveryRow(@PrimaryKey val id: String,val revision: String)
@Dao interface DeskDao {
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
@Database(entities=[SnapshotRow::class,ReminderRow::class,ActionRow::class,DeliveryRow::class],version=1,exportSchema=false)
abstract class DeskDatabase: RoomDatabase() { abstract fun desk(): DeskDao }
