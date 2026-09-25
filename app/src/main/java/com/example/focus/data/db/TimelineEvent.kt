package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 时间块与日历事件的映射。
 *
 * key 形如 "timeline_{日期}_{序号}"。
 * 为什么不用系统日历的 sync_data 字段？部分国产 ROM（vivo/iQOO 的 viewevents 视图）
 * 不暴露该列，读写都会报 no such column，所以映射完全自维护。
 */
@Entity(tableName = "timeline_events")
data class TimelineEvent(
    @PrimaryKey val key: String,
    val eventId: Long,
)

@Dao
interface TimelineEventDao {

    @Query("SELECT * FROM timeline_events")
    suspend fun getAll(): List<TimelineEvent>

    @Query("SELECT * FROM timeline_events WHERE key LIKE :pattern")
    suspend fun getByPattern(pattern: String): List<TimelineEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TimelineEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TimelineEvent>)

    @Query("DELETE FROM timeline_events WHERE key LIKE :pattern")
    suspend fun deleteByPattern(pattern: String)

    @Query("DELETE FROM timeline_events")
    suspend fun clear()
}
