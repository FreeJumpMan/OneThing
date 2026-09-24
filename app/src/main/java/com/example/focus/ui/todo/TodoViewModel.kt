package com.example.focus.ui.todo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.TodoItem
import com.example.focus.data.repo.SessionRepository
import com.example.focus.service.TimerService
import com.example.focus.service.TimerStateHolder
import com.example.focus.service.TimerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 专注板块 ViewModel：事项列表（添加/删除）+ 今日各项投入时长，
 * 点击项目启动前台计时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val todoDao = db.todoItemDao()
    private val repo = SessionRepository(db.focusDao())

    val uiState: StateFlow<TimerUiState> = TimerStateHolder.state

    val items: StateFlow<List<TodoItem>> =
        todoDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 今日日期流（跨天时列表上的时长自动归零） */
    private val today: StateFlow<String> = flow {
        while (true) {
            emit(LocalDate.now().toString())
            delay(30_000)
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now().toString())

    /** 今日各事项的投入时长：名称 → 毫秒 */
    val todayByName: StateFlow<Map<String, Long>> =
        today.flatMapLatest { date ->
            repo.observeSumsByNameOnDate(date)
        }.map { list -> list.associate { it.label to it.totalMs } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun add(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            todoDao.insert(TodoItem(name = trimmed, createdAt = System.currentTimeMillis()))
        }
    }

    fun delete(item: TodoItem) {
        viewModelScope.launch { todoDao.delete(item) }
    }

    /** 点击事项开始计时；已在计时中则不响应 */
    fun start(item: TodoItem) {
        if (TimerStateHolder.state.value.started) return
        TimerService.start(getApplication(), item.name)
    }

    fun pause() = TimerService.pause(getApplication())

    fun resume() = TimerService.resume(getApplication())

    fun stop() = TimerService.stop(getApplication())
}
