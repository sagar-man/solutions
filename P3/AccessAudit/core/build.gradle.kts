import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately dependency-free (kotlin-stdlib only). This module holds every
// piece of logic worth unit testing - the appops/dumpsys parsers and the risk
// rules - so it must stay runnable on a plain JVM, with no Android SDK and no
// network access to Google's Maven repository.
dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    // 17 is the bytecode level the Android module needs. We set the target
    // rather than requesting a toolchain so the module also builds on a box
    // that only has a newer JDK installed.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
