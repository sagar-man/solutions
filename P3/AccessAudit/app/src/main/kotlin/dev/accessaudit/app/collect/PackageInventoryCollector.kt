package dev.accessaudit.app.collect

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.accessaudit.core.model.AppIdentity
import dev.accessaudit.core.model.ExecutableArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Tier 0: the static inventory, built entirely from `PackageManager`.
 *
 * This is the only collector that always works, on any device, with no setup. It
 * cannot say what an app *did* - only what it is allowed to do, where it came
 * from, and whether it carries code that was not in the installed package.
 */
public class PackageInventoryCollector(private val context: Context) : AccessCollector {

    override val id: String = "package-manager"
    override val tier: CollectorTier = CollectorTier.STATIC

    override suspend fun isAvailable(): Boolean = true

    override suspend fun collect(windowStartMs: Long, nowMs: Long): CollectionResult =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = installedPackages(pm)
            val identities = packages.mapNotNull { info ->
                // One malformed package must not abort the whole inventory.
                runCatching { toIdentity(pm, info) }.getOrNull()
            }
            CollectionResult(
                collectorId = id,
                tier = tier,
                available = true,
                identities = identities,
            )
        }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
    }

    private fun toIdentity(pm: PackageManager, info: PackageInfo): AppIdentity {
        val appInfo = info.applicationInfo ?: ApplicationInfo()
        val requested = info.requestedPermissions?.toSet() ?: emptySet()

        // requestedPermissionsFlags is a parallel array; a permission is only
        // actually usable when REQUESTED_PERMISSION_GRANTED is set. Reading the
        // manifest list alone would badly overstate what each app can do.
        val granted = buildSet {
            val flags = info.requestedPermissionsFlags
            info.requestedPermissions?.forEachIndexed { index, permission ->
                val flag = flags?.getOrNull(index) ?: 0
                if (flag and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) add(permission)
            }
        }

        return AppIdentity(
            packageName = info.packageName,
            label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(info.packageName),
            uid = appInfo.uid,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            targetSdk = appInfo.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
            installerPackage = installerOf(pm, info.packageName),
            firstInstallMs = info.firstInstallTime,
            lastUpdateMs = info.lastUpdateTime,
            isSystem = appInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            isDebuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            usesCleartextTraffic = appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
            allowsBackup = appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
            signingSha256 = signingDigests(pm, info.packageName),
            sharedUserId = @Suppress("DEPRECATION") info.sharedUserId,
            requestedPermissions = requested,
            grantedPermissions = granted,
            accessibilityServices = accessibilityServices(info),
            deviceAdminReceivers = deviceAdminReceivers(info),
            declaresForegroundService = info.services?.isNotEmpty() == true,
            startsOnBoot = requested.contains("android.permission.RECEIVE_BOOT_COMPLETED"),
            executableArtifacts = executableArtifacts(appInfo),
        )
    }

    private fun installerOf(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = pm.getInstallSourceInfo(packageName)
            // initiatingPackageName is the more honest field: installingPackageName
            // can be rewritten by a session installer, the initiator cannot.
            source.initiatingPackageName ?: source.installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    /**
     * SHA-256 of each signing certificate, so a "same package name, different
     * signer" swap is visible between scans.
     */
    private fun signingDigests(pm: PackageManager, packageName: String): List<String> = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return emptyList()
            val certificates = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            certificates?.map { hex(digest.digest(it.toByteArray())) } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                ?.map { hex(digest.digest(it.toByteArray())) } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun accessibilityServices(info: PackageInfo): List<String> =
        info.services.orEmpty()
            .filter { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
            .map { "${it.packageName}/${it.name}" }

    /** A device-admin receiver is gated by BIND_DEVICE_ADMIN, same shape as above. */
    private fun deviceAdminReceivers(info: PackageInfo): List<String> =
        info.receivers.orEmpty()
            .filter { it.permission == "android.permission.BIND_DEVICE_ADMIN" }
            .map { "${it.packageName}/${it.name}" }

    /**
     * Looks for code the app could load that was not part of the installed APK.
     *
     * We can only stat our *own* process's directories on a modern device, so for
     * other apps this reports the shipped native library directory. The writable
     * -directory case is what matters and is detectable on rooted devices or via
     * the shell collector; here we mark artifacts outside the read-only code path.
     */
    private fun executableArtifacts(appInfo: ApplicationInfo): List<ExecutableArtifact> {
        val nativeDir = appInfo.nativeLibraryDir ?: return emptyList()
        val dir = File(nativeDir)
        if (!dir.isDirectory) return emptyList()
        val dataDir = appInfo.dataDir
        return runCatching {
            dir.listFiles().orEmpty().take(50).map { file ->
                ExecutableArtifact(
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    // Anything under the app's writable data dir arrived after
                    // install; the APK's own lib dir is read-only.
                    inWritableDir = dataDir != null &&
                        file.absolutePath.startsWith(dataDir) &&
                        !file.absolutePath.contains("/lib/"),
                    executableBit = file.canExecute(),
                )
            }
        }.getOrDefault(emptyList())
    }
}
