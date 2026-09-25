package com.example.focus.data.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.focus.data.db.CalendarSync
import com.example.focus.data.db.CalendarSyncDao
import com.example.focus.data.db.TimelineEvent
import com.example.focus.data.db.TimelineEventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 日历同步管理器：把专注会话写入系统日历。
 *
 * 设计要点：
 * 1. 幂等靠本地映射表（CalendarSyncDao）而非系统 sync_data 字段。
 *    国产 ROM（vivo/iQOO viewevents 视图）不暴露 sync_data 列，
 *    查询会报 no such column，因此映射完全自维护，跨 ROM 行为一致。
 * 2. 时区：DTSTART/DTEND 传 epoch 毫秒（UTC），EVENT_TIMEZONE 传本地时区，
 *    系统会自动换算显示，漏掉时区字段会导致事件时间错位。
 * 3. 选日历：优先主日历，其次名字含"我的/本地/Phone"的本地日历，
 *    并且只选可写（CAL_ACCESS_CONTRIBUTOR 及以上）、可见的日历。
 *
 * 使用前提：调用方已获得 READ_CALENDAR + WRITE_CALENDAR 运行时权限
 * （查询日历列表需要 READ，写入事件需要 WRITE，国产 ROM 不保证同组同授）。
 */
class CalendarSyncManager(
    private val context: Context,
    private val syncDao: CalendarSyncDao,
    private val timelineDao: TimelineEventDao,
) {

    private val resolver get() = context.contentResolver

    /**
     * 是否已获得日历读写权限。
     * 读取（READ）用于查询日历列表，写入（WRITE）用于创建事件，
     * 两个都必需。某些 ROM 不按权限组一起授予，必须逐个检查。
     */
    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

    private data class CalendarInfo(
        val id: Long,
        val name: String,
        val isPrimary: Boolean,
    )

    /** 找一个可写的可见日历，返回其 id；没有返回 null */
    suspend fun findWritableCalendarId(): Long? =
        withContext(Dispatchers.IO) { queryWritableCalendars().firstOrNull()?.id }

    private fun queryWritableCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = buildString {
            append("${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ")
            append(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            append(" AND ${CalendarContract.Calendars.VISIBLE} = 1")
        }
        val result = mutableListOf<CalendarInfo>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val primaryCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            while (cursor.moveToNext()) {
                result += CalendarInfo(
                    id = cursor.getLong(idCol),
                    name = cursor.getString(nameCol) ?: "",
                    isPrimary = cursor.getInt(primaryCol) != 0,
                )
            }
        }
        return result.sortedWith(
            // 优先"工作"日历：vivo 的"个人/工作"分类就是日历维度，
            // 事件写进 vivo work 即归为"工作"（如 id=4 的 vivo work）
            compareByDescending<CalendarInfo> {
                it.name.contains("work", ignoreCase = true) || it.name.contains("工作")
            }
                .thenByDescending { it.isPrimary }
                .thenByDescending {
                    it.name.contains("我的") ||
                        it.name.contains("本地") ||
                        it.name.contains("Phone")
                }
        )
    }

    /**
     * 幂等同步一条会话到日历。
     * 本地映射表里有记录 → 更新对应事件；没有 → 插入并记录映射。
     * 返回日历事件 id。
     *
     * @param sessionId app 内会话 id，映射表主键
     * @param title 事项名称，即日历事件标题
     * @param startTimeMs 开始时间，epoch 毫秒
     * @param endTimeMs 结束时间，epoch 毫秒
     * @param durationMs 专注时长，仅用于事件描述
     */
    suspend fun syncSession(
        sessionId: Long,
        title: String,
        startTimeMs: Long,
        endTimeMs: Long,
        durationMs: Long,
    ): Long = withContext(Dispatchers.IO) {
        val calendarId = requireNotNull(findWritableCalendarId()) {
            "没有找到可写的日历，请先在系统日历设置里确认存在可见日历"
        }
        val existing = syncDao.getBySessionId(sessionId)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, "专注时长 ${formatDuration(durationMs)}")
            put(CalendarContract.Events.DTSTART, startTimeMs)
            put(CalendarContract.Events.DTEND, endTimeMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val eventId: Long
        if (existing != null) {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.eventId)
            resolver.update(uri, values, null, null)
            eventId = existing.eventId
        } else {
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: error("插入日历事件失败")
            eventId = ContentUris.parseId(uri)
            syncDao.upsert(
                CalendarSync(
                    sessionId = sessionId,
                    eventId = eventId,
                    eventUri = uri.toString(),
                )
            )
        }
        eventId
    }

    /** 删除某条会话对应的日历事件，app 内删除会话时联动调用 */
    suspend fun removeSessionEvents(sessionId: Long) = withContext(Dispatchers.IO) {
        val sync = syncDao.getBySessionId(sessionId) ?: return@withContext
        runCatching {
            resolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, sync.eventId),
                null, null,
            )
        }
        syncDao.deleteBySessionId(sessionId)
    }

    /** 仅清除本地同步映射，保留系统日历中的事件（用户选择"只删软件内记录"时调用） */
    suspend fun clearSessionMapping(sessionId: Long) = withContext(Dispatchers.IO) {
        syncDao.deleteBySessionId(sessionId)
    }

    /** 查询某条会话是否已同步到日历（查本地映射），用于 UI 显示"已同步"标记 */
    suspend fun isSessionSynced(sessionId: Long): Boolean =
        withContext(Dispatchers.IO) { syncDao.getBySessionId(sessionId) != null }

    // ===== 时间块同步（App 时间轴 → 日历）=====

    /**
     * 清空某天由时间轴同步生成的事件。
     * 时间块会随分类规则变化而重组，所以重新同步前先整体清掉，避免残留旧块。
     */
    suspend fun clearTimelineEvents(date: String): Int = withContext(Dispatchers.IO) {
        val pattern = "timeline_${date}_%"
        val existing = timelineDao.getByPattern(pattern)
        var deleted = 0
        existing.forEach { item ->
            deleted += runCatching {
                resolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.eventId),
                    null, null,
                )
            }.getOrDefault(0)
        }
        timelineDao.deleteByPattern(pattern)
        deleted
    }

    /**
     * 插入一个时间块事件（标题为分类名）。
     * 事件 id 记在本地映射表里（不用系统的 sync_data，国产 ROM 不暴露该列）。
     */
    suspend fun insertTimelineBlock(
        date: String,
        index: Int,
        title: String,
        description: String,
        startMs: Long,
        endMs: Long,
    ): Long? = withContext(Dispatchers.IO) {
        val calendarId = findWritableCalendarId() ?: return@withContext null
        runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@runCatching null
            val eventId = ContentUris.parseId(uri)
            timelineDao.upsert(TimelineEvent("timeline_${date}_$index", eventId))
            eventId
        }.getOrNull()
    }

    // ===== 诊断工具：用于确认国产 ROM 日历的自定义字段（如 vivo 的"个人/工作"分类）=====
    // 注意：所有查询都必须包 runCatching，provider 权限拒绝是运行时行为，
    // 未经处理的 SecurityException 会直接让 app 崩溃。

    /** 读取 events 表的全部列名 */
    suspend fun inspectEventColumns(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                CalendarContract.Events.CONTENT_URI, null, null, null, null,
            )?.use { cursor -> cursor.columnNames.toList() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 读取 calendars 表的全部列名 */
    suspend fun inspectCalendarColumns(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI, null, null, null, null,
            )?.use { cursor -> cursor.columnNames.toList() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 按标题查一条事件的所有非空字段值（过滤 null，方便对照分类字段） */
    suspend fun inspectEventRow(title: String): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                CalendarContract.Events.CONTENT_URI, null,
                "${CalendarContract.Events.TITLE} = ?", arrayOf(title), null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val map = mutableMapOf<String, String>()
                    cursor.columnNames.forEach { name ->
                        val idx = cursor.getColumnIndex(name)
                        if (!cursor.isNull(idx)) {
                            map[name] = when {
                                cursor.getType(idx) == android.database.Cursor.FIELD_TYPE_FLOAT ->
                                    cursor.getDouble(idx).toString()

                                else -> cursor.getString(idx) ?: "?"
                            }
                        }
                    }
                    map
                } else {
                    emptyMap()
                }
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /** 列出所有日历：id | 名称 | 是否主日历 | 可见性（确认"个人/工作"是否挂在日历上） */
    suspend fun inspectCalendars(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.VISIBLE,
            )
            val list = mutableListOf<String>()
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val primaryCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
                val visibleCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
                while (c.moveToNext()) {
                    val primary = if (c.getInt(primaryCol) != 0) "主日历" else ""
                    val visible = if (c.getInt(visibleCol) != 0) "可见" else "隐藏"
                    list += "${c.getLong(idCol)} | ${c.getString(nameCol)} | $primary | $visible"
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }
}
