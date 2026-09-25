package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 用户手动标记的「专注 App」。
 *
 * 由用户主动指定（不让系统猜测），用于把 App 使用时间区分为
 * 「有效专注」与「普通使用」两类。
 */
@Entity(tableName = "focus_apps")
data class FocusApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val enabled: Boolean,
)

@Dao
interface FocusAppDao {

    @Query("SELECT * FROM focus_apps")
    fun observeAll(): Flow<List<FocusApp>>

    @Query("SELECT * FROM focus_apps WHERE enabled = 1")
    suspend fun getEnabled(): List<FocusApp>

    @Query("SELECT * FROM focus_apps WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): FocusApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FocusApp)

    @Query("DELETE FROM focus_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
