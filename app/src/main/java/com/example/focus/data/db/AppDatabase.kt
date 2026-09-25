package com.example.focus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FocusSession::class,
        CalendarSync::class,
        TodoItem::class,
        FocusApp::class,
        TimeCategory::class,
        AppCategoryRule::class,
        TimelineEvent::class,
        HiddenUsageSegment::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun focusDao(): FocusDao

    abstract fun calendarSyncDao(): CalendarSyncDao

    abstract fun todoItemDao(): TodoItemDao

    abstract fun focusAppDao(): FocusAppDao

    abstract fun timeCategoryDao(): TimeCategoryDao

    abstract fun appCategoryRuleDao(): AppCategoryRuleDao

    abstract fun timelineEventDao(): TimelineEventDao

    abstract fun hiddenUsageSegmentDao(): HiddenUsageSegmentDao

    companion object {

        /** v1 → v2：新增 calendar_syncs 映射表，日历幂等改为本地维护 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `calendar_syncs` (
                        `sessionId` INTEGER NOT NULL,
                        `eventId` INTEGER NOT NULL,
                        `eventUri` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3：新增 todo_items 事项表 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todo_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v3 → v4：新增 focus_apps 专注 App 标记表 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `focus_apps` (
                        `packageName` TEXT NOT NULL,
                        `appName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4 → v5：新增时间分类与 App 分类规则表 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `time_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_category_rules` (
                        `packageName` TEXT NOT NULL,
                        `categoryId` INTEGER NOT NULL,
                        `productivity` TEXT NOT NULL,
                        `isUserDefined` INTEGER NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v5 → v6：新增时间块与日历事件的映射表 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `timeline_events` (
                        `key` TEXT NOT NULL,
                        `eventId` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v6 → v7：新增「被隐藏的自动记录」表（自动记录只能隐藏，不能真删） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hidden_usage_segments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `appName` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focus.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
