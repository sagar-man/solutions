pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // mavenCentral first: only the AndroidX/AGP artifacts of the :app module
        // come from Google's repository, and asking it for everything else just
        // earns a 403 per lookup.
        mavenCentral()
        google()
    }
}

rootProject.name = "access-audit"

include(":core")
include(":cli")

// The :app module needs the Android SDK and the Android Gradle Plugin (which is
// served from Google's Maven repository). Both are frequently absent on CI boxes
// and on machines that only care about the analysis engine, so we only wire the
// module in when an SDK is actually reachable. `./gradlew :core:test` therefore
// works on a bare JDK, while a normal Android Studio checkout still builds the
// full app. See Readme -> "Building".
val sdkFromProperties: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.inputStream()
    ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("sdk.dir") }
val sdkDir = sdkFromProperties
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

if (sdkDir != null && file(sdkDir).isDirectory) {
    include(":app")
} else {
    logger.lifecycle(
        "access-audit: no Android SDK found (set ANDROID_HOME or sdk.dir in local.properties) - " +
            "skipping the :app module. The :core analysis engine and :cli still build."
    )
}
