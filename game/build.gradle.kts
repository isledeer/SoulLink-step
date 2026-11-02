import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 版本由根项目 classpath（build-logic 的 kotlin-gradle-plugin）提供，
    // 这里显式写版本会报 "already on the classpath with an unknown version"
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.org.json)
    testImplementation(libs.junit)
}
