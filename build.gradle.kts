plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

/*
 * 构建目录隔离。
 *
 * 项目放在共享目录里，Linux 与 Windows 若共用同一个 build/ 目录，
 * Kotlin 增量编译缓存中的路径格式会互相冲突
 * （Windows 会把 Linux 的 /mnt/... 当作相对路径而报错）。
 *
 * 因此：命令行构建时设环境变量 YISHI_LINUX_BUILD=1，产物单独落到 .build-linux/；
 * Android Studio 侧不设该变量，继续使用默认 build/，两边互不干扰。
 */
if (System.getenv("YISHI_LINUX_BUILD") == "1") {
    allprojects {
        layout.buildDirectory.set(file("${rootDir}/.build-linux/${name}"))
    }
}
