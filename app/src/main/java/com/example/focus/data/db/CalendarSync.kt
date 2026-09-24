package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 会话与日历事件的本地映射表。
 *
 * 为什么不直接用系统日历的 sync_data 字段做幂等标记？
 * 部分国产 ROM（vivo/iQOO 的 viewevents 视图）不暴露该列，
 * 查询会直接报 no such column。自维护映射表后完全摆脱 ROM 依赖，
 * 任何设备上幂等逻辑行为一致。
 */
@Entity(tableName = "calendar_syncs")
data class CalendarSync(
    @PrimaryKey val sessionId: Long,
    val eventId: Long,
    val eventUri: String,
)

@Dao
interface CalendarSyncDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sync: CalendarSync)

    @Query("SELECT * FROM calendar_syncs WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: Long): CalendarSync?

    @Query("SELECT * FROM calendar_syncs")
    suspend fun getAllOnce(): List<CalendarSync>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(syncs: List<CalendarSync>)

    @Query("DELETE FROM calendar_syncs")
    suspend fun clear()

    @Query("DELETE FROM calendar_syncs WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)
}
