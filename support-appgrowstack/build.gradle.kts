import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 問い合わせ・返事・添付・ヘルプの記事をサーバーで扱い、つなぐ型の直近の記録を文脈に添える（kotlin-app-support のコネクタ）
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    api(project(":appgrowstack-core"))
    // 頼る本体の版。publish-sdk.sh が公開済みかを確かめる。APPGROWSTACK_LOCAL ではモノリポの今の物に差し替わる
    api("com.github.appgrowstack:kotlin-app-support:0.3.0")
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "support-appgrowstack"
        }
    }
}
