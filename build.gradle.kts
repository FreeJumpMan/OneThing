plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

/*
 * 构建目录隔离。
 *
 * 项目放在共享目录（VMware hgfs）里，若 Linux 与 Windows 共用同一个 build/：
 * 1. Kotlin 增量缓存里的路径格式会冲突（Windows 会把 /mnt/... 当作相对路径而报错）；
 * 2. hgfs 偶发读取 .class 文件失败（size 返回 -1）。
 *
 * 因此命令行构建时用环境变量把产物指到**本地磁盘**（非共享目录）：
 *   export YISHI_LINUX_BUILD_DIR=$HOME/.yishi-build
 * Android Studio 侧不设该变量，继续用项目内默认 build/，两边互不干扰。
 */
val linuxBuildDir = System.getenv("YISHI_LINUX_BUILD_DIR")
if (!linuxBuildDir.isNullOrBlank()) {
    allprojects {
        layout.buildDirectory.set(file("$linuxBuildDir/${name}"))
    }
}
