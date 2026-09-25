package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 被用户隐藏的「专注 App 自动记录」片段。
 *
 * 自动记录是从系统使用记录实时派生的，没有自己的行，也就没有「删掉」这回事。
 * 所以删除记在这里：派生时凡是与这些区间大幅重叠的片段都不再展示。
 * 好处是删除可持久、可撤销（撤销就是把这条记录删掉），且不改动任何原始数据。
 */
@Entity(tableName = "hidden_usage_segments")
data class HiddenUsageSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    /** 段内的 App 名，仅用于展示与排查 */
    val appName: String,
)

@Dao
interface HiddenUsageSegmentDao {

    @Query("SELECT * FROM hidden_usage_segments ORDER BY startMs ASC")
    fun observeAll(): Flow<List<HiddenUsageSegment>>

    @Query("SELECT * FROM hidden_usage_segments")
    suspend fun getAllOnce(): List<HiddenUsageSegment>

    @Insert
    suspend fun insert(segment: HiddenUsageSegment): Long

    @Query("DELETE FROM hidden_usage_segments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM hidden_usage_segments")
    suspend fun clear()
}
