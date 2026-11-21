import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 入れた人の流入元をサーバーで決める（kotlin-app-links のコネクタ）
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
    api("com.github.appgrowstack:kotlin-app-links:0.1.0")
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
            artifactId = "links-appgrowstack"
        }
    }
}
