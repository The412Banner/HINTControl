package com.banner.hintcontrol.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One reading of the gateway's radio values. */
@Entity(tableName = "samples", indices = [Index(value = ["profileId", "time"])])
data class SignalSample(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val time: Long,
    val nrRsrp: Int? = null,
    val nrSinr: Int? = null,
    val nrRsrq: Int? = null,
    val nrRssi: Int? = null,
    val nrBand: String? = null,
    val nrCell: Long? = null,
    val lteRsrp: Int? = null,
    val lteSinr: Int? = null,
    val lteRsrq: Int? = null,
    val lteRssi: Int? = null,
    val lteBand: String? = null,
    val lteCell: Long? = null,
    val uptime: Long? = null,
)

/** A device seen on the gateway, with the name the user gave it. */
@Entity(tableName = "devices", primaryKeys = ["profileId", "deviceKey"])
data class KnownDevice(
    val profileId: String,
    val deviceKey: String,
    val alias: String? = null,
    val lastName: String? = null,
    val lastIp: String? = null,
    val link: String? = null,
    val firstSeen: Long,
    val lastSeen: Long,
)

enum class EventType(val label: String) {
    RebootDetected("Gateway restarted"),
    RebootSent("Reboot sent"),
    ScheduledReboot("Scheduled reboot"),
    ScheduledRebootFailed("Scheduled reboot failed"),
    Offline("Gateway offline"),
    Online("Gateway back online"),
    BandChange("Band changed"),
    NewDevice("New device"),
    WifiSaved("Wi-Fi settings saved"),
    WeakSignal("Weak signal"),
    LoginChanged("Admin login changed"),
    FirmwareUpdated("Firmware updated"),
}

@Entity(tableName = "events", indices = [Index(value = ["profileId", "time"])])
data class GatewayEvent(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val time: Long,
    val type: EventType,
    val detail: String? = null,
)

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: SignalSample)

    @Query("SELECT * FROM samples WHERE profileId = :profileId AND time >= :since ORDER BY time")
    fun since(profileId: String, since: Long): Flow<List<SignalSample>>

    @Query("SELECT * FROM samples WHERE profileId = :profileId AND time >= :since ORDER BY time")
    suspend fun sinceOnce(profileId: String, since: Long): List<SignalSample>

    @Query("SELECT * FROM samples WHERE profileId = :profileId ORDER BY time DESC LIMIT 1")
    suspend fun latest(profileId: String): SignalSample?

    @Query("SELECT COUNT(*) FROM samples WHERE profileId = :profileId")
    fun count(profileId: String): Flow<Int>

    @Query("DELETE FROM samples WHERE time < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM samples WHERE profileId = :profileId")
    suspend fun clear(profileId: String)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE profileId = :profileId")
    fun all(profileId: String): Flow<List<KnownDevice>>

    @Query("SELECT * FROM devices WHERE profileId = :profileId")
    suspend fun allOnce(profileId: String): List<KnownDevice>

    @Upsert
    suspend fun upsert(devices: List<KnownDevice>)

    @Query("UPDATE devices SET alias = :alias WHERE profileId = :profileId AND deviceKey = :key")
    suspend fun setAlias(profileId: String, key: String, alias: String?)

    @Query("DELETE FROM devices WHERE profileId = :profileId")
    suspend fun clear(profileId: String)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GatewayEvent)

    @Query("SELECT * FROM events WHERE profileId = :profileId ORDER BY time DESC LIMIT :limit")
    fun recent(profileId: String, limit: Int = 300): Flow<List<GatewayEvent>>

    @Query("SELECT * FROM events WHERE profileId = :profileId AND type = :type ORDER BY time DESC LIMIT 1")
    suspend fun last(profileId: String, type: EventType): GatewayEvent?

    @Query("DELETE FROM events WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    @Query("DELETE FROM events WHERE time < :before")
    suspend fun deleteBefore(before: Long)
}

@Database(entities = [SignalSample::class, KnownDevice::class, GatewayEvent::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun devices(): DeviceDao
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): HistoryDatabase =
            Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
    }
}
