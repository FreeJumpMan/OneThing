package com.example.focus.data.backup

import androidx.room.withTransaction
import com.example.focus.data.db.AppCategoryRule
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.CalendarSync
import com.example.focus.data.db.FocusApp
import com.example.focus.data.db.FocusSession
import com.example.focus.data.db.HiddenUsageSegment
import com.example.focus.data.db.TimeCategory
import com.example.focus.data.db.TimelineEvent
import com.example.focus.data.db.TodoItem
import com.example.focus.data.prefs.AppSettings
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.prefs.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 备份文件的体检结果：能读就给出摘要，读不了就说清原因 */
sealed class BackupCheck {
    data class Valid(val summary: BackupSummary) : BackupCheck()
    data class Invalid(val message: String) : BackupCheck()
}

/** 导入前给用户看的概览 */
data class BackupSummary(
    val exportedAt: String,
    val formatVersion: Int,
    val sessionCount: Int,
    val todoCount: Int,
    val categoryCount: Int,
    val ruleCount: Int,
    val focusAppCount: Int,
    val calendarMappingCount: Int,
    val hasSettings: Boolean,
)

/**
 * 数据备份管理器：把业务数据与设置导出为 JSON，
 * 或在卸载重装/换机后从 JSON 恢复。数据完全本地，不经过任何网络。
 *
 * 三条硬规则（导入是破坏性操作，顺序比功能重要）：
 * 1. **先认领**：`app` 字段不是「一事」的文件一律拒绝，绝不清库；
 * 2. **先解析后落库**：整份 JSON 全部解析校验通过，才在单个事务里清表写表，
 *    中途任何一行坏了都整体回滚，原数据不动；
 * 3. **表按 presence 替换**：备份里没有的段（老版本备份）保持原样，不清空。
 */
class BackupManager(
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
) {

    companion object {
        /**
         * 备份格式版本。
         * v1：专注记录 / 事项 / 日历映射 / 时间块映射
         * v2：新增时间分类、分类规则、专注 App 与设置项
         * v3：新增「被隐藏的自动记录」
         */
        private const val FORMAT_VERSION = 3
        private const val APP_TAG = "一事"
    }

    /** 解析后的备份内容（全部在内存里，校验通过才落库） */
    private data class ParsedBackup(
        val summary: BackupSummary,
        val sessions: List<FocusSession>,
        val todos: List<TodoItem>,
        val categories: List<TimeCategory>,
        val rules: List<AppCategoryRule>,
        val focusApps: List<FocusApp>,
        val calendarSyncs: List<CalendarSync>,
        val timelineEvents: List<TimelineEvent>,
        val hiddenSegments: List<HiddenUsageSegment>,
        val settings: AppSettings?,
        val presentKeys: Set<String>,
    )

    // ===== 导出 =====

    /** 导出全部业务数据与设置为 JSON 字符串 */
    suspend fun exportData(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("formatVersion", FORMAT_VERSION)
        root.put(
            "exportedAt",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        )

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

        val todos = JSONArray()
        db.todoItemDao().getAllOnce().forEach { t ->
            todos.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("createdAt", t.createdAt)
            })
        }
        root.put("todoItems", todos)

        val categories = JSONArray()
        db.timeCategoryDao().getAll().forEach { c ->
            categories.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("colorHex", c.colorHex)
                put("sortOrder", c.sortOrder)
                put("isBuiltIn", c.isBuiltIn)
            })
        }
        root.put("timeCategories", categories)

        val rules = JSONArray()
        db.appCategoryRuleDao().getAll().forEach { r ->
            rules.put(JSONObject().apply {
                put("packageName", r.packageName)
                put("categoryId", r.categoryId)
                put("productivity", r.productivity)
                put("isUserDefined", r.isUserDefined)
            })
        }
        root.put("appCategoryRules", rules)

        val focusApps = JSONArray()
        db.focusAppDao().getAllOnce().forEach { a ->
            focusApps.put(JSONObject().apply {
                put("packageName", a.packageName)
                put("appName", a.appName)
                put("enabled", a.enabled)
            })
        }
        root.put("focusApps", focusApps)

        // 日历映射：不入库会导致恢复后重新同步时出现重复事件
        val syncs = JSONArray()
        db.calendarSyncDao().getAllOnce().forEach { c ->
            syncs.put(JSONObject().apply {
                put("sessionId", c.sessionId)
                put("eventId", c.eventId)
                put("eventUri", c.eventUri)
            })
        }
        root.put("calendarSyncs", syncs)

        val timelines = JSONArray()
        db.timelineEventDao().getAll().forEach { t ->
            timelines.put(JSONObject().apply {
                put("key", t.key)
                put("eventId", t.eventId)
            })
        }
        root.put("timelineEvents", timelines)

        val hidden = JSONArray()
        db.hiddenUsageSegmentDao().getAllOnce().forEach { h ->
            hidden.put(JSONObject().apply {
                put("startMs", h.startMs)
                put("endMs", h.endMs)
                put("appName", h.appName)
            })
        }
        root.put("hiddenUsageSegments", hidden)

        root.put("settings", settingsToJson(settingsStore.settings.first()))

        root.toString(2)
    }

    // ===== 导入 =====

    /** 只体检不落库：给导入前的确认弹窗提供摘要；失败返回可展示的原因 */
    suspend fun checkBackup(json: String): BackupCheck = withContext(Dispatchers.IO) {
        runCatching { parse(json) }.fold(
            onSuccess = { BackupCheck.Valid(it.summary) },
            onFailure = { BackupCheck.Invalid(it.message ?: "备份文件解析失败") },
        )
    }

    /**
     * 从 JSON 恢复数据。整份文件先解析校验，再单事务清表写入。
     *
     * @param keepCalendarMappings 是否同时恢复备份里的日历映射。
     *        同一台手机重装 → true（保留，避免重复往日历里插事件）；
     *        换到新手机（日历里没有原来写入的事件）→ false，
     *        否则残留的映射会指向别的日历事件。无论哪种情况，当前映射都会先清空，
     *        因为旧记录已被整体替换。
     * @return null 表示成功，否则返回错误信息
     */
    suspend fun importData(json: String, keepCalendarMappings: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            val parsed = runCatching { parse(json) }.getOrElse {
                return@withContext it.message ?: "备份文件解析失败"
            }

            runCatching {
                db.withTransaction {
                    replaceTable(parsed.presentKeys, "focusSessions") {
                        db.focusDao().clear()
                        if (parsed.sessions.isNotEmpty()) db.focusDao().insertAll(parsed.sessions)
                    }
                    replaceTable(parsed.presentKeys, "todoItems") {
                        db.todoItemDao().clear()
                        if (parsed.todos.isNotEmpty()) db.todoItemDao().insertAll(parsed.todos)
                    }
                    replaceTable(parsed.presentKeys, "timeCategories") {
                        db.timeCategoryDao().clear()
                        if (parsed.categories.isNotEmpty()) {
                            db.timeCategoryDao().upsertAll(parsed.categories)
                        }
                    }
                    replaceTable(parsed.presentKeys, "appCategoryRules") {
                        db.appCategoryRuleDao().clear()
                        if (parsed.rules.isNotEmpty()) {
                            db.appCategoryRuleDao().upsertAll(parsed.rules)
                        }
                    }
                    replaceTable(parsed.presentKeys, "focusApps") {
                        db.focusAppDao().clear()
                        if (parsed.focusApps.isNotEmpty()) {
                            db.focusAppDao().upsertAll(parsed.focusApps)
                        }
                    }
                    replaceTable(parsed.presentKeys, "hiddenUsageSegments") {
                        db.hiddenUsageSegmentDao().clear()
                        parsed.hiddenSegments.forEach { db.hiddenUsageSegmentDao().insert(it) }
                    }

                    // 映射表是派生数据：旧记录已被替换，映射一律作废先清空
                    db.calendarSyncDao().clear()
                    db.timelineEventDao().clear()
                    if (keepCalendarMappings) {
                        if (parsed.calendarSyncs.isNotEmpty()) {
                            db.calendarSyncDao().insertAll(parsed.calendarSyncs)
                        }
                        if (parsed.timelineEvents.isNotEmpty()) {
                            db.timelineEventDao().upsertAll(parsed.timelineEvents)
                        }
                    }
                }
                // 设置在事务外单独写（DataStore 不是 Room 的表，无法参与同一事务）
                parsed.settings?.let { settingsStore.replaceAll(it) }
                null
            }.getOrElse { it.message ?: "导入失败" }
        }

    /** 备份里说了这张表才替换；没说的（老版本备份）保持原样，不清空 */
    private suspend inline fun replaceTable(presentKeys: Set<String>, key: String, block: () -> Unit) {
        if (key in presentKeys) block()
    }

    // ===== 解析与校验 =====

    private fun parse(json: String): ParsedBackup {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw IllegalArgumentException("不是有效的 JSON 文件")
        }

        // 1. 认领：不是「一事」的备份，一律拒绝，绝不动数据
        if (root.optString("app", "") != APP_TAG) {
            throw IllegalArgumentException("这不是「一事」的备份文件")
        }

        // 2. 版本
        val formatVersion = root.optInt("formatVersion", 0)
        if (formatVersion <= 0) throw IllegalArgumentException("备份文件缺少版本信息")
        if (formatVersion > FORMAT_VERSION) {
            throw IllegalArgumentException("备份文件版本过高，请升级应用后再导入")
        }

        val presentKeys = root.keys().asSequence().toSet()

        val sessionArr = root.optJSONArray("focusSessions") ?: JSONArray()
        val todoArr = root.optJSONArray("todoItems") ?: JSONArray()
        val categoryArr = root.optJSONArray("timeCategories") ?: JSONArray()
        val ruleArr = root.optJSONArray("appCategoryRules") ?: JSONArray()
        val focusAppArr = root.optJSONArray("focusApps") ?: JSONArray()
        val syncArr = root.optJSONArray("calendarSyncs") ?: JSONArray()
        val timelineArr = root.optJSONArray("timelineEvents") ?: JSONArray()
        val hiddenArr = root.optJSONArray("hiddenUsageSegments") ?: JSONArray()

        val sessions = (0 until sessionArr.length()).map { i ->
            val o = objectAt(sessionArr, i, "专注记录")
            rowGuard("专注记录", i) {
                FocusSession(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    startTimeMs = o.getLong("startTimeMs"),
                    endTimeMs = o.getLong("endTimeMs"),
                    durationMs = o.getLong("durationMs"),
                    date = o.getString("date"),
                )
            }
        }

        val todos = (0 until todoArr.length()).map { i ->
            val o = objectAt(todoArr, i, "事项")
            rowGuard("事项", i) {
                TodoItem(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    createdAt = o.getLong("createdAt"),
                )
            }
        }

        val categories = (0 until categoryArr.length()).map { i ->
            val o = objectAt(categoryArr, i, "时间分类")
            rowGuard("时间分类", i) {
                TimeCategory(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    colorHex = o.getString("colorHex"),
                    sortOrder = o.optInt("sortOrder", i),
                    isBuiltIn = o.optBoolean("isBuiltIn", false),
                )
            }
        }

        val rules = (0 until ruleArr.length()).map { i ->
            val o = objectAt(ruleArr, i, "分类规则")
            rowGuard("分类规则", i) {
                AppCategoryRule(
                    packageName = o.getString("packageName"),
                    categoryId = o.getLong("categoryId"),
                    productivity = o.optString("productivity", "NEUTRAL"),
                    isUserDefined = o.optBoolean("isUserDefined", true),
                )
            }
        }

        val focusApps = (0 until focusAppArr.length()).map { i ->
            val o = objectAt(focusAppArr, i, "专注 App")
            rowGuard("专注 App", i) {
                FocusApp(
                    packageName = o.getString("packageName"),
                    appName = o.optString("appName", o.getString("packageName")),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        }

        val calendarSyncs = (0 until syncArr.length()).map { i ->
            val o = objectAt(syncArr, i, "日历映射")
            rowGuard("日历映射", i) {
                CalendarSync(
                    sessionId = o.getLong("sessionId"),
                    eventId = o.getLong("eventId"),
                    eventUri = o.optString("eventUri", ""),
                )
            }
        }

        val timelineEvents = (0 until timelineArr.length()).map { i ->
            val o = objectAt(timelineArr, i, "时间块映射")
            rowGuard("时间块映射", i) {
                TimelineEvent(
                    key = o.getString("key"),
                    eventId = o.getLong("eventId"),
                )
            }
        }

        // 设置段（v1 备份没有）
        val settings = root.optJSONObject("settings")?.let { parseSettings(it) }

        val hiddenSegments = (0 until hiddenArr.length()).map { i ->
            val o = objectAt(hiddenArr, i, "隐藏的自动记录")
            rowGuard("隐藏的自动记录", i) {
                HiddenUsageSegment(
                    id = o.optLong("id", 0L),
                    startMs = o.getLong("startMs"),
                    endMs = o.getLong("endMs"),
                    appName = o.optString("appName", ""),
                )
            }
        }

        val summary = BackupSummary(
            exportedAt = root.optString("exportedAt", "未知"),
            formatVersion = formatVersion,
            sessionCount = sessions.size,
            todoCount = todos.size,
            categoryCount = categories.size,
            ruleCount = rules.size,
            focusAppCount = focusApps.size,
            calendarMappingCount = calendarSyncs.size + timelineEvents.size,
            hasSettings = settings != null,
        )

        return ParsedBackup(
            summary = summary,
            sessions = sessions,
            todos = todos,
            categories = categories,
            rules = rules,
            focusApps = focusApps,
            calendarSyncs = calendarSyncs,
            timelineEvents = timelineEvents,
            hiddenSegments = hiddenSegments,
            settings = settings,
            presentKeys = presentKeys,
        )
    }

    private fun objectAt(arr: JSONArray, index: Int, label: String): JSONObject =
        arr.optJSONObject(index) ?: throw IllegalArgumentException("备份中的${label}第 ${index + 1} 条格式不正确")

    /** 单行解析失败时给出可读的定位信息，而不是原始 JSONException */
    private inline fun <T> rowGuard(label: String, index: Int, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw IllegalArgumentException("备份中的${label}第 ${index + 1} 条字段缺失或格式不正确")
        }

    // ===== 设置段 =====

    private fun settingsToJson(s: AppSettings): JSONObject = JSONObject().apply {
        put("themeMode", s.themeMode.name)
        put("switchToleranceMinutes", s.switchToleranceMinutes)
        put("excludedCalendarCategories", JSONArray(s.excludedCalendarCategories.toList()))
        put("autoSyncCalendar", s.autoSyncCalendar)
        put("autoSyncAppUsage", s.autoSyncAppUsage)
        put("timelineCalendarColor", s.timelineCalendarColor)
    }

    private fun parseSettings(o: JSONObject): AppSettings {
        val mode = runCatching { ThemeMode.valueOf(o.optString("themeMode")) }
            .getOrNull() ?: ThemeMode.FOLLOW_SYSTEM

        val excluded = mutableSetOf<String>()
        o.optJSONArray("excludedCalendarCategories")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { excluded += it }
            }
        }

        return AppSettings(
            themeMode = mode,
            switchToleranceMinutes = o.optInt("switchToleranceMinutes", 2),
            excludedCalendarCategories = excluded,
            autoSyncCalendar = o.optBoolean("autoSyncCalendar", false),
            autoSyncAppUsage = o.optBoolean("autoSyncAppUsage", false),
            timelineCalendarColor = o.optInt("timelineCalendarColor", 0xFF48B59B.toInt()),
        )
    }
}
