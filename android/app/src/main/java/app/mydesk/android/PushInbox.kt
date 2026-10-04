package app.mydesk.android

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Kept separately from business cache: receipts survive process death and snapshot replacement. */
class PushInbox(context: Context,memory: Boolean=false) {
    private val db=if(memory) Room.inMemoryDatabaseBuilder(context,PushDatabase::class.java).build()
        else Room.databaseBuilder(context,PushDatabase::class.java,"mydesk-push.db").addMigrations(PUSH_MIGRATION).build()
    private val dao=db.push()
    suspend fun receive(message: PushMessage,display: ()->String): Boolean=db.withTransaction {
        if(dao.get(message.eventId) != null) return@withTransaction false
        val phase=display()
        require(phase in setOf("received","display_requested","blocked"))
        dao.put(PushReceipt(message.eventId,message.serverId,message.deviceId,phase,registrationId=message.registrationId,kind=message.kind,reference=message.reference,revision=message.revision,expiresAt=message.expiresAt.toEpochMilli()))
        true
    }
    suspend fun pending(): List<PushReceipt> = dao.pending()
    suspend fun acknowledge(event: String) { dao.acknowledge(event) }
    suspend fun phase(event: String,phase: String) {require(phase in setOf("display_requested","blocked","superseded"));dao.phase(event,phase)}
    suspend fun clear() { dao.clear() }
    fun close()=db.close()
}
@Entity(tableName="push_receipts")
data class PushReceipt(@PrimaryKey val eventId: String,val serverId: String,val deviceId: String,val phase: String,val acknowledged: Boolean=false,
    @ColumnInfo(defaultValue="''") val registrationId: String="",@ColumnInfo(defaultValue="''") val kind: String="",
    @ColumnInfo(defaultValue="''") val reference: String="",@ColumnInfo(defaultValue="''") val revision: String="",@ColumnInfo(defaultValue="0") val expiresAt: Long=0)
@Dao interface PushDao {
    @Query("SELECT * FROM push_receipts WHERE eventId=:event") suspend fun get(event: String): PushReceipt?
    @Query("SELECT * FROM push_receipts WHERE acknowledged=0") suspend fun pending(): List<PushReceipt>
    @Insert suspend fun put(receipt: PushReceipt)
    @Query("UPDATE push_receipts SET acknowledged=1 WHERE eventId=:event") suspend fun acknowledge(event: String)
    @Query("UPDATE push_receipts SET phase=:phase WHERE eventId=:event AND phase='received'") suspend fun phase(event: String,phase: String)
    @Query("DELETE FROM push_receipts") suspend fun clear()
}
private val PUSH_MIGRATION=object: Migration(1,2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("registrationId","kind","reference","revision").forEach {db.execSQL("ALTER TABLE push_receipts ADD COLUMN $it TEXT NOT NULL DEFAULT ''")}
        db.execSQL("ALTER TABLE push_receipts ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
    }
}
@Database(entities=[PushReceipt::class],version=2,exportSchema=false)
abstract class PushDatabase: RoomDatabase() { abstract fun push(): PushDao }
