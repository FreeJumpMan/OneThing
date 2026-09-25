package com.example.focus.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.db.AppCategoryRule
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.ProductivityLevel
import com.example.focus.data.db.TimeCategory
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.usage.AppUsageItem
import com.example.focus.data.usage.DefaultCategoryRules
import com.example.focus.data.usage.UsageStatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 分类 + 已配置的 App 数量 */
data class CategoryWithCount(
    val category: TimeCategory,
    val appCount: Int,
)

class CategorySettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val categoryDao = db.timeCategoryDao()
    private val ruleDao = db.appCategoryRuleDao()
    private val usageRepo = UsageStatsRepository(application)
    private val settingsStore = SettingsStore(application)

    /** 分类列表（带已配置 App 数量） */
    val categories: StateFlow<List<CategoryWithCount>> =
        combine(categoryDao.observeAll(), ruleDao.observeAll()) { cats, rules ->
            cats.map { category ->
                CategoryWithCount(
                    category = category,
                    appCount = rules.count { it.categoryId == category.id },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户自定义规则：包名 → 规则 */
    val rules: StateFlow<Map<String, AppCategoryRule>> =
        ruleDao.observeAll()
            .map { list -> list.associateBy { it.packageName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 候选 App（最近 30 天用过的） */
    private val _apps = MutableStateFlow<List<AppUsageItem>>(emptyList())
    val apps: StateFlow<List<AppUsageItem>> = _apps.asStateFlow()

    private val _loadingApps = MutableStateFlow(false)
    val loadingApps: StateFlow<Boolean> = _loadingApps.asStateFlow()

    fun loadApps() {
        if (_apps.value.isNotEmpty() || _loadingApps.value) return
        viewModelScope.launch {
            _loadingApps.value = true
            val today = LocalDate.now()
            _apps.value = if (usageRepo.hasUsageAccess()) {
                usageRepo.loadRange(today.minusDays(29), today)
            } else {
                emptyList()
            }
            _loadingApps.value = false
        }
    }

    // ===== 分类维护 =====

    fun addCategory(name: String, colorHex: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val nextOrder = (categoryDao.getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1
            categoryDao.upsert(
                TimeCategory(
                    name = trimmed,
                    colorHex = colorHex,
                    sortOrder = nextOrder,
                    isBuiltIn = false,
                )
            )
        }
    }

    fun updateCategory(category: TimeCategory, name: String, colorHex: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            categoryDao.upsert(category.copy(name = trimmed, colorHex = colorHex))
        }
    }

    /**
     * 删除分类：同时清掉指向它的用户规则（那些 App 会回到内置默认归类），
     * 以及日历同步排除集合里的旧 id（否则设置页的「N 个已开启」会虚低）。
     */
    fun deleteCategory(category: TimeCategory) {
        viewModelScope.launch {
            ruleDao.getAll()
                .filter { it.categoryId == category.id }
                .forEach { ruleDao.delete(it.packageName) }
            categoryDao.delete(category)

            val id = category.id.toString()
            val excluded = settingsStore.settings.first().excludedCalendarCategories
            if (id in excluded) settingsStore.setExcludedCalendarCategories(excluded - id)
        }
    }

    // ===== App 归类 =====

    /** 把某个 App 归到指定分类（写入用户规则，优先级高于内置规则） */
    fun assignApp(item: AppUsageItem, category: TimeCategory) {
        viewModelScope.launch {
            val level = DefaultCategoryRules.match(item.appName, item.packageName)?.second
                ?: ProductivityLevel.NEUTRAL
            ruleDao.upsert(
                AppCategoryRule(
                    packageName = item.packageName,
                    categoryId = category.id,
                    productivity = level.name,
                    isUserDefined = true,
                )
            )
        }
    }

    /** 清除某个 App 的用户规则（回到内置默认归类） */
    fun clearAppRule(packageName: String) {
        viewModelScope.launch { ruleDao.delete(packageName) }
    }
}
