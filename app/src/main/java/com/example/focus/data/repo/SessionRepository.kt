package com.example.focus.data.repo

import com.example.focus.data.db.CumulativeStats
import com.example.focus.data.db.DayStats
import com.example.focus.data.db.FocusDao
import com.example.focus.data.db.FocusSession
import com.example.focus.data.db.SliceStat
import kotlinx.coroutines.flow.Flow

/**
 * 会话仓库，UI 层唯一的数据入口。
 */
class SessionRepository(private val dao: FocusDao) {

    fun observeAll(): Flow<List<FocusSession>> = dao.observeAll()

    fun observeByDate(date: String): Flow<List<FocusSession>> = dao.observeByDate(date)

    fun observeDistinctDates(start: String, end: String): Flow<List<String>> =
        dao.observeDistinctDates(start, end)

    fun observeCumulative(): Flow<CumulativeStats> = dao.observeCumulative()

    fun observeDayStats(date: String): Flow<DayStats> = dao.observeDayStats(date)

    fun observePieByName(start: String, end: String): Flow<List<SliceStat>> =
        dao.observePieByName(start, end)

    /** 某天各事项的累计时长 */
    fun observeSumsByNameOnDate(date: String): Flow<List<SliceStat>> =
        dao.observeSumsByNameOnDate(date)

    /** 月内每日记录数（日历热力点） */
    fun observeCountsByDay(start: String, end: String): Flow<List<SliceStat>> =
        dao.observeCountsByDay(start, end)

    /** 时间区间内的总时长 */
    fun observeTotalInRange(start: String, end: String): Flow<Long> =
        dao.observeTotalInRange(start, end)

    fun observeHourBars(start: String, end: String): Flow<List<SliceStat>> =
        dao.observeHourBars(start, end)

    fun observeDailyLine(start: String, end: String): Flow<List<SliceStat>> =
        dao.observeDailyLine(start, end)

    fun observeMonthly(start: String, end: String): Flow<List<SliceStat>> =
        dao.observeMonthly(start, end)

    suspend fun insert(session: FocusSession): Long = dao.insert(session)

    suspend fun update(session: FocusSession) = dao.update(session)

    suspend fun delete(session: FocusSession) = dao.delete(session)
}
