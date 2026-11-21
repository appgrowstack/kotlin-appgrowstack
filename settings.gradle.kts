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
        // 公開した本体（com.github.appgrowstack:kotlin-app-<物>）
        maven("https://jitpack.io")
    }
}

rootProject.name = "kotlin-appgrowstack"

// つなぐ型。本体の SDK に頼らない（Kotlin/JVM だけ）
include(":appgrowstack-core")

// コネクタは本体ごとのモジュール（<物>-appgrowstack）。アプリは使うモジュールだけを入れ、そのモジュールが頼る本体だけを取る。
// 本体は JitPack の公開した版（com.github.appgrowstack:kotlin-app-<物>:<版>）で頼る。APPGROWSTACK_LOCAL があれば、
// モノリポの隣のフォルダを組んでその座標に差し替える（公開していない今の本体でテストする）
include(":campaign-appgrowstack")
include(":links-appgrowstack")
include(":support-appgrowstack")
include(":sessions-appgrowstack")
include(":config-appgrowstack")

if (System.getenv("APPGROWSTACK_LOCAL") != null) {
    includeBuild("../kotlin-app-campaign") {
        dependencySubstitution {
            substitute(module("com.github.appgrowstack:kotlin-app-campaign")).using(project(":campaign-core"))
        }
    }
    includeBuild("../kotlin-app-links") {
        dependencySubstitution {
            substitute(module("com.github.appgrowstack:kotlin-app-links")).using(project(":links-core"))
        }
    }
    includeBuild("../kotlin-app-support") {
        dependencySubstitution {
            substitute(module("com.github.appgrowstack:kotlin-app-support")).using(project(":support-core"))
        }
    }
    includeBuild("../kotlin-app-sessions") {
        dependencySubstitution {
            substitute(module("com.github.appgrowstack.kotlin-app-sessions:sessions-core")).using(project(":sessions-core"))
        }
    }
    includeBuild("../kotlin-app-config") {
        dependencySubstitution {
            substitute(module("com.github.appgrowstack.kotlin-app-config:config-core")).using(project(":config-core"))
        }
    }
}
