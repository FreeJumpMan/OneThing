package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 专注会话的数据访问层。
 * 统计板块的七项指标全部由这里的聚合查询产出。
 */
@Dao
interface FocusDao {

    @Insert
    suspend fun insert(session: FocusSession): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<FocusSession>)

    @Update
    suspend fun update(session: FocusSession)

    @Delete
    suspend fun delete(session: FocusSession)

    @Query("SELECT * FROM focus_sessions")
    suspend fun getAllOnce(): List<FocusSession>

    @Query("DELETE FROM focus_sessions")
    suspend fun clear()

    /** 历史记录：全部会话，按开始时间倒序 */
    @Query("SELECT * FROM focus_sessions ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<FocusSession>>

    /** 某一天的会话，按开始时间升序 */
    @Query("SELECT * FROM focus_sessions WHERE date = :date ORDER BY startTimeMs ASC")
    fun observeByDate(date: String): Flow<List<FocusSession>>

    /** 某一天的会话（一次性查询，供日历同步规划用） */
    @Query("SELECT * FROM focus_sessions WHERE date = :date ORDER BY startTimeMs ASC")
    suspend fun getByDateOnce(date: String): List<FocusSession>

    /** 时间段内有记录的所有日期，供日历画标记点 */
    @Query("SELECT DISTINCT date FROM focus_sessions WHERE date BETWEEN :start AND :end")
    fun observeDistinctDates(start: String, end: String): Flow<List<String>>

    /** 1. 累计专注：次数、总时长、日均时长（有记录的天数做分母） */
    @Query(
        """
        SELECT COUNT(*) AS sessionCount,
               COALESCE(SUM(durationMs), 0) AS totalMs,
               COALESCE(SUM(durationMs) / NULLIF(COUNT(DISTINCT date), 0), 0) AS dailyAvgMs
        FROM focus_sessions
        """
    )
    fun observeCumulative(): Flow<CumulativeStats>

    /** 2. 当日专注 */
    @Query(
        """
        SELECT COUNT(*) AS sessionCount, COALESCE(SUM(durationMs), 0) AS totalMs
        FROM focus_sessions WHERE date = :date
        """
    )
    fun observeDayStats(date: String): Flow<DayStats>

    /** 4. 饼图按事项类目（会话名称）分组，时间范围由调用方传入 */
    @Query(
        """
        SELECT name AS label, SUM(durationMs) AS totalMs
        FROM focus_sessions WHERE date BETWEEN :start AND :end
        GROUP BY name ORDER BY totalMs DESC
        """
    )
    fun observePieByName(start: String, end: String): Flow<List<SliceStat>>

    /** 某日期区间的原始会话记录（用于与 App 使用时间合并去重） */
    @Query(
        """
        SELECT * FROM focus_sessions WHERE date BETWEEN :start AND :end
        ORDER BY startTimeMs ASC
        """
    )
    fun observeInRange(start: String, end: String): Flow<List<FocusSession>>

    /** 月内每日记录数（日历热力点：记录越多点越多） */
    @Query(
        """
        SELECT substr(date, 9, 2) AS label, COUNT(*) AS totalMs
        FROM focus_sessions WHERE date BETWEEN :start AND :end
        GROUP BY label
        """
    )
    fun observeCountsByDay(start: String, end: String): Flow<List<SliceStat>>

    /** 时间区间内的总时长（月环比用） */
    @Query(
        """
        SELECT COALESCE(SUM(durationMs), 0) FROM focus_sessions
        WHERE date BETWEEN :start AND :end
        """
    )
    fun observeTotalInRange(start: String, end: String): Flow<Long>

    /** 某天各事项的累计时长（首页列表右侧显示今日投入） */
    @Query(
        """
        SELECT name AS label, SUM(durationMs) AS totalMs
        FROM focus_sessions WHERE date = :date
        GROUP BY name
        """
    )
    fun observeSumsByNameOnDate(date: String): Flow<List<SliceStat>>

    /** 5. 本月专注时段分布（按小时） */
    @Query(
        """
        SELECT strftime('%H', startTimeMs / 1000, 'unixepoch', 'localtime') AS label,
               SUM(durationMs) AS totalMs
        FROM focus_sessions WHERE date BETWEEN :start AND :end
        GROUP BY label ORDER BY label
        """
    )
    fun observeHourBars(start: String, end: String): Flow<List<SliceStat>>

    /** 6. 月度每日专注时长（折线图数据） */
    @Query(
        """
        SELECT date AS label, SUM(durationMs) AS totalMs
        FROM focus_sessions WHERE date BETWEEN :start AND :end
        GROUP BY date ORDER BY date
        """
    )
    fun observeDailyLine(start: String, end: String): Flow<List<SliceStat>>

    /** 7. 年度专注统计（按月） */
    @Query(
        """
        SELECT substr(date, 1, 7) AS label, SUM(durationMs) AS totalMs
        FROM focus_sessions WHERE date BETWEEN :start AND :end
        GROUP BY label ORDER BY label
        """
    )
    fun observeMonthly(start: String, end: String): Flow<List<SliceStat>>
}

data class CumulativeStats(val sessionCount: Int, val totalMs: Long, val dailyAvgMs: Long)

data class DayStats(val sessionCount: Int, val totalMs: Long)

data class SliceStat(val label: String, val totalMs: Long)
