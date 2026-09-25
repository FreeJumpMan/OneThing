# 一事 混淆规则（release 构建）

# Room：实体与数据库实现由注解处理器生成，保留防反射类
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# 保留异常行号，便于排查线上问题
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin 元数据
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**
