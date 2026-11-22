import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AppConfig の組をサーバーから取る（kotlin-app-config のコネクタ）
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
    api("com.github.appgrowstack.kotlin-app-config:config-core:0.2.0")
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
            artifactId = "config-appgrowstack"
        }
    }
}
