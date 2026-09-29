package dev.accessaudit.core.model

/** Where an app came from, which strongly conditions how much trust it earns. */
public enum class InstallSource(public val trusted: Boolean, public val label: String) {
    PRE_INSTALLED(true, "pre-installed"),
    PLAY_STORE(true, "Google Play"),
    VENDOR_STORE(true, "vendor store"),
    OTHER_STORE(false, "third-party store"),
    /** Installed by a file manager, browser, adb or another app: a sideload. */
    SIDELOADED(false, "sideloaded"),
    UNKNOWN(false, "unknown"),
    ;

    public companion object {
        private val PLAY = setOf("com.android.vending", "com.google.android.feedback")
        private val VENDOR = setOf(
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
            "com.xiaomi.mipicks",
            "com.oppo.market",
            "com.heytap.market",
            "com.vivo.appstore",
        )
        private val KNOWN_ALT_STORES = setOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.aurora.store",
            "com.github.yeriomin.yalpstore",
        )

        /**
         * Classifies the installer package name reported by
         * `PackageManager.getInstallSourceInfo`.
         *
         * A null installer means nobody claimed the install: that is either a
         * system image app (caller passes `isSystem = true`) or an `adb install`
         * / `pm install` sideload, which is exactly what we want to surface.
         */
        public fun classify(installerPackage: String?, isSystem: Boolean): InstallSource = when {
            isSystem -> PRE_INSTALLED
            installerPackage == null || installerPackage.isBlank() -> SIDELOADED
            installerPackage in PLAY -> PLAY_STORE
            installerPackage in VENDOR -> VENDOR_STORE
            installerPackage in KNOWN_ALT_STORES -> OTHER_STORE
            else -> SIDELOADED
        }
    }
}

/**
 * A file on disk that the app can execute or load as code.
 *
 * Native libraries shipped inside the APK are normal. A `.so`, `.dex` or `.jar`
 * sitting in the app's writable data directory means code arrived after review,
 * which is the classic dynamic-code-loading dodge.
 */
public data class ExecutableArtifact(
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    /** True when the file lives under a directory the app itself can write to. */
    val inWritableDir: Boolean = false,
    /** True when the POSIX execute bit is set. */
    val executableBit: Boolean = false,
)

/** Static facts about an installed package. Everything here is readable without any permission. */
public data class AppIdentity(
    val packageName: String,
    val label: String = packageName,
    val uid: Int = -1,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val targetSdk: Int = 0,
    val minSdk: Int = 0,
    val installerPackage: String? = null,
    val firstInstallMs: Long = 0,
    val lastUpdateMs: Long = 0,
    val isSystem: Boolean = false,
    val isDebuggable: Boolean = false,
    /** Non-null only when the app opted out of the default (safer) network policy. */
    val usesCleartextTraffic: Boolean = false,
    val allowsBackup: Boolean = false,
    val signingSha256: List<String> = emptyList(),
    val sharedUserId: String? = null,
    /** Permissions listed in the manifest, whether or not they were granted. */
    val requestedPermissions: Set<String> = emptySet(),
    /** Subset of [requestedPermissions] the platform has actually granted. */
    val grantedPermissions: Set<String> = emptySet(),
    /** Special "appop" style access the user grants from Settings, e.g. all-files access. */
    val specialAccess: Set<String> = emptySet(),
    /** Fully-qualified names of declared AccessibilityService components. */
    val accessibilityServices: List<String> = emptyList(),
    /** Declared device-admin / device-owner receivers. */
    val deviceAdminReceivers: List<String> = emptyList(),
    val declaresForegroundService: Boolean = false,
    /** Receivers listening for BOOT_COMPLETED - how an app gets persistence. */
    val startsOnBoot: Boolean = false,
    val executableArtifacts: List<ExecutableArtifact> = emptyList(),
) {
    public val installSource: InstallSource
        get() = InstallSource.classify(installerPackage, isSystem)

    public fun grants(permission: String): Boolean = permission in grantedPermissions

    public fun requests(permission: String): Boolean = permission in requestedPermissions

    /** Permissions asked for but never granted - useful as "wanted but refused". */
    public val ungrantedPermissions: Set<String>
        get() = requestedPermissions - grantedPermissions
}
