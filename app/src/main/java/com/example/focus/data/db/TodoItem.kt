package com.example.focus.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 事项（要做的事），可自定义添加/删除，点击开始专注计时。
 */
@Entity(tableName = "todo_items")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Dao
interface TodoItemDao {

    @Query("SELECT * FROM todo_items ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<TodoItem>>

    @Query("SELECT * FROM todo_items")
    suspend fun getAllOnce(): List<TodoItem>

    @Insert
    suspend fun insert(item: TodoItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TodoItem>)

    @Delete
    suspend fun delete(item: TodoItem)

    @Query("DELETE FROM todo_items")
    suspend fun clear()
}
