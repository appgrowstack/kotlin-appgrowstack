pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlin-appgrowstack"

// つなぐ型。本体の SDK に頼らない（Kotlin/JVM だけ）
include(":appgrowstack-core")

// コネクタは本体ごとのモジュール（<物>-appgrowstack）で、作った時に足す。アプリは使うモジュールだけを入れ、そのモジュールが頼る本体だけを取る。
// 本体は、APPGROWSTACK_LOCAL があればモノリポの隣のフォルダから組み（includeBuild("../kotlin-app-<物>")）、無ければ JitPack の公開した版
