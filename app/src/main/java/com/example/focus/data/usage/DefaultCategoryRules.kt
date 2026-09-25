package com.example.focus.data.usage

import com.example.focus.data.db.ProductivityLevel
import com.example.focus.data.db.TimeCategory

/**
 * 内置时间分类与默认分类规则。
 *
 * 设计要点：
 * 1. 分类与生产力分开：同一个「社交」分类对不同人不一定是「分心」，用户可改。
 * 2. 内置规则只作为兜底，用户规则永远优先。
 * 3. 优先按 App 名称关键词匹配（比包名稳，中文 App 名好识别），
 *    少数知名 App 用包名精确匹配。
 */
object DefaultCategoryRules {

    // 内置分类的固定 id，便于默认规则引用。
    // id 保持历史值不重排（1/2/3/5/8 不连续是有意的）：
    // 老用户库里这些 id 已被规则引用，换分类集合只改名不换号，避免映射错位。
    const val CAT_LEARNING = 1L
    const val CAT_SOCIAL = 2L
    const val CAT_ENTERTAINMENT = 3L
    const val CAT_TOOLS = 5L
    const val CAT_OTHER = 8L

    /** 内置分类（首次启动时写入数据库）：只保留最常用的五个 */
    val CATEGORIES = listOf(
        TimeCategory(CAT_LEARNING, "学习", "#4A6B4A", 0, true),
        TimeCategory(CAT_SOCIAL, "社交", "#FFB547", 1, true),
        TimeCategory(CAT_ENTERTAINMENT, "娱乐", "#FF684A", 2, true),
        TimeCategory(CAT_TOOLS, "工具", "#8E8A88", 3, true),
        TimeCategory(CAT_OTHER, "其他", "#B8AFA6", 4, true),
    )

    /** 包名精确匹配（少量知名 App，优先级高于关键词） */
    private val BY_PACKAGE: Map<String, Pair<Long, ProductivityLevel>> = mapOf(
        "com.tencent.mm" to (CAT_SOCIAL to ProductivityLevel.DISTRACTING),
        "com.tencent.mobileqq" to (CAT_SOCIAL to ProductivityLevel.DISTRACTING),
        "com.ss.android.ugc.aweme" to (CAT_ENTERTAINMENT to ProductivityLevel.DISTRACTING),
        "tv.danmaku.bili" to (CAT_ENTERTAINMENT to ProductivityLevel.DISTRACTING),
        "com.netease.cloudmusic" to (CAT_ENTERTAINMENT to ProductivityLevel.PERSONAL),
    )

    /** 名称关键词匹配：命中即归类（顺序敏感，先具体后宽泛） */
    private val BY_KEYWORD: List<Pair<List<String>, Pair<Long, ProductivityLevel>>> = listOf(
        // 学习
        listOf("背单词", "百词斩", "墨墨", "欧路", "词典") to (CAT_LEARNING to ProductivityLevel.FOCUS),
        listOf("chatgpt", "deepseek", "claude", "kimi", "通义", "豆包", "文心") to (CAT_LEARNING to ProductivityLevel.FOCUS),
        listOf("kindle", "微信读书", "掌阅", "多看") to (CAT_LEARNING to ProductivityLevel.FOCUS),
        listOf("notion", "obsidian", "flomo", "有道云", "原子笔记", "笔记", "备忘录") to (CAT_LEARNING to ProductivityLevel.FOCUS),
        listOf("学习", "课程", "题库", "考研", "四六级", "网课", "学堂", "mooc") to (CAT_LEARNING to ProductivityLevel.FOCUS),

        // 社交
        listOf("微信", "wechat", "qq", "钉钉", "飞书", "telegram", "微博", "知乎", "小红书") to (CAT_SOCIAL to ProductivityLevel.DISTRACTING),

        // 娱乐（刷视频 / 游戏 / 音乐合并为一类）
        listOf("抖音", "快手", "哔哩", "bilibili", "视频", "影视", "腾讯视频", "爱奇艺", "优酷", "直播") to (CAT_ENTERTAINMENT to ProductivityLevel.DISTRACTING),
        listOf("游戏", "game", "原神", "王者", "和平精英", "米哈游", "mihoyo") to (CAT_ENTERTAINMENT to ProductivityLevel.DISTRACTING),
        listOf("音乐", "网易云", "喜马拉雅", "播客", "fm", "radio", "听书") to (CAT_ENTERTAINMENT to ProductivityLevel.PERSONAL),

        // 工具（系统类 App 大多落这里）
        listOf("相机", "相册", "图库", "文件", "日历", "天气", "计算器", "设置", "时钟", "闹钟", "邮件", "输入法", "钱包", "支付", "地图", "导航") to (CAT_TOOLS to ProductivityLevel.NEUTRAL),
    )

    /**
     * 匹配内置默认规则；未命中返回 null（交给上层显示为「其他」）。
     */
    fun match(appName: String, packageName: String): Pair<Long, ProductivityLevel>? {
        BY_PACKAGE[packageName]?.let { return it }
        val lowerName = appName.lowercase()
        val lowerPkg = packageName.lowercase()
        BY_KEYWORD.forEach { (keywords, result) ->
            if (keywords.any { keyword ->
                    lowerName.contains(keyword.lowercase()) || lowerPkg.contains(keyword.lowercase())
                }
            ) {
                return result
            }
        }
        return null
    }
}
