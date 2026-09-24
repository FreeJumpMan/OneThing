package com.example.focus.data.backup

import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.CalendarSync
import com.example.focus.data.db.FocusSession
import com.example.focus.data.db.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 数据备份管理器：把专注记录、事项、日历同步映射导出为 JSON，
 * 或在卸载重装/换机后从 JSON 恢复。数据完全本地，不经过任何网络。
 */
class BackupManager(private val db: AppDatabase) {

    companion object {
        private const val FORMAT_VERSION = 1
        private const val APP_TAG = "一事"
    }

    /** 导出三张业务表为 JSON 字符串 */
    suspend fun exportData(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))

        val sessions = JSONArray()
        db.focusDao().getAllOnce().forEach { s ->
            sessions.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("startTimeMs", s.startTimeMs)
                put("endTimeMs", s.endTimeMs)
                put("durationMs", s.durationMs)
                put("date", s.date)
            })
        }
        root.put("focusSessions", sessions)

        val syncs = JSONArray()
        db.calendarSyncDao().getAllOnce().forEach { c ->
            syncs.put(JSONObject().apply {
                put("sessionId", c.sessionId)
                put("eventId", c.eventId)
                put("eventUri", c.eventUri)
            })
        }
        root.put("calendarSyncs", syncs)

        val todos = JSONArray()
        db.todoItemDao().getAllOnce().forEach { t ->
            todos.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("createdAt", t.createdAt)
            })
        }
        root.put("todoItems", todos)

        root.toString(2)
    }

    /**
     * 从 JSON 恢复数据，清空现有业务表后整体导入。
     * @return null 表示成功，否则返回错误信息
     */
    suspend fun importData(json: String): String? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(json)
            if (!root.isNull("formatVersion") && root.getInt("formatVersion") > FORMAT_VERSION) {
                return@withContext "备份文件版本过高，请升级应用后再导入"
            }

            val sessionArr = root.optJSONArray("focusSessions") ?: JSONArray()
            val syncArr = root.optJSONArray("calendarSyncs") ?: JSONArray()
            val todoArr = root.optJSONArray("todoItems") ?: JSONArray()

            db.focusDao().clear()
            db.calendarSyncDao().clear()
            db.todoItemDao().clear()

            if (sessionArr.length() > 0) {
                db.focusDao().insertAll(
                    (0 until sessionArr.length()).map { i ->
                        val o = sessionArr.getJSONObject(i)
                        FocusSession(
                            id = o.getLong("id"),
                            name = o.getString("name"),
                            startTimeMs = o.getLong("startTimeMs"),
                            endTimeMs = o.getLong("endTimeMs"),
                            durationMs = o.getLong("durationMs"),
                            date = o.getString("date"),
                        )
                    }
                )
            }

            if (syncArr.length() > 0) {
                db.calendarSyncDao().insertAll(
                    (0 until syncArr.length()).map { i ->
                        val o = syncArr.getJSONObject(i)
                        CalendarSync(
                            sessionId = o.getLong("sessionId"),
                            eventId = o.getLong("eventId"),
                            eventUri = o.getString("eventUri"),
                        )
                    }
                )
            }

            if (todoArr.length() > 0) {
                db.todoItemDao().insertAll(
                    (0 until todoArr.length()).map { i ->
                        val o = todoArr.getJSONObject(i)
                        TodoItem(
                            id = o.getLong("id"),
                            name = o.getString("name"),
                            createdAt = o.getLong("createdAt"),
                        )
                    }
                )
            }
            null
        } catch (e: Exception) {
            e.message ?: "备份文件解析失败"
        }
    }
}
