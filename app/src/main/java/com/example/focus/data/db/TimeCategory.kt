package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 时间的生产力属性（与「分类」分开，同一个分类对不同人可以不同属性） */
enum class ProductivityLevel(val label: String) {
    FOCUS("专注"),
    OTHER_WORK("其他工作"),
    NEUTRAL("中性"),
    PERSONAL("个人"),
    DISTRACTING("分心"),
}

/**
 * 时间分类，回答「我在做什么」。
 * 内置分类 isBuiltIn = true，用户可以新增/改名/改色，但不建议删除内置项。
 */
@Entity(tableName = "time_categories")
data class TimeCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val sortOrder: Int,
    val isBuiltIn: Boolean = false,
)

/**
 * App → 分类 的规则。
 * 优先级：用户规则 > 内置默认规则 > 未分类。
 */
@Entity(tableName = "app_category_rules")
data class AppCategoryRule(
    @PrimaryKey val packageName: String,
    val categoryId: Long,
    /** ProductivityLevel.name */
    val productivity: String,
    val isUserDefined: Boolean = false,
)

@Dao
interface TimeCategoryDao {

    @Query("SELECT * FROM time_categories ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<TimeCategory>>

    @Query("SELECT * FROM time_categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<TimeCategory>

    @Query("SELECT * FROM time_categories WHERE id = :id")
    suspend fun getById(id: Long): TimeCategory?

    @Query("SELECT COUNT(*) FROM time_categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: TimeCategory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<TimeCategory>)

    @Delete
    suspend fun delete(category: TimeCategory)
}

@Dao
interface AppCategoryRuleDao {

    @Query("SELECT * FROM app_category_rules")
    fun observeAll(): Flow<List<AppCategoryRule>>

    @Query("SELECT * FROM app_category_rules")
    suspend fun getAll(): List<AppCategoryRule>

    @Query("SELECT * FROM app_category_rules WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): AppCategoryRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AppCategoryRule)

    @Query("DELETE FROM app_category_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
