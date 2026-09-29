package dev.accessaudit.core.risk

import dev.accessaudit.core.model.ExecutableArtifact
import dev.accessaudit.core.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val P = "android.permission."

class BackgroundSensitiveAccessRuleTest {

    @Test
    fun `flags microphone use with no visible UI`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(profile(tallies = listOf(tally("RECORD_AUDIO", total = 2, background = 2))), context()),
        )
        assertEquals(1, findings.size)
        assertEquals(Severity.MEDIUM, findings.single().severity)
    }

    @Test
    fun `escalates to HIGH on a sustained pattern`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(profile(tallies = listOf(tally("RECORD_AUDIO", total = 5, background = 4))), context()),
        )
        assertEquals(Severity.HIGH, findings.single().severity)
    }

    @Test
    fun `escalates on a long hold even with a single access`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(
                profile(
                    tallies = listOf(
                        tally("RECORD_AUDIO", total = 1, background = 1, durationMs = 300_000),
                    ),
                ),
                context(),
            ),
        )
        assertEquals(Severity.HIGH, findings.single().severity)
    }

    @Test
    fun `ignores foreground-only access`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(profile(tallies = listOf(tally("CAMERA", total = 9, background = 0))), context()),
        )
        assertTrue(findings.isEmpty(), "camera use while the app is on screen is expected")
    }

    @Test
    fun `ignores low-sensitivity background ops`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(profile(tallies = listOf(tally("BLUETOOTH_SCAN", total = 5, background = 5))), context()),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `skips system apps`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(
                profile(
                    app = app(packageName = "com.android.systemui", isSystem = true),
                    tallies = listOf(tally("RECORD_AUDIO", total = 9, background = 9)),
                ),
                context(),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `respects a user suppression`() {
        val findings = BackgroundSensitiveAccessRule.evaluate(
            RuleInput(
                profile(tallies = listOf(tally("RECORD_AUDIO", total = 9, background = 9))),
                context(suppressions = setOf("com.example.app:BG_SENSITIVE_ACCESS")),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `suppressing one rule does not mute the others`() {
        val input = RuleInput(
            profile(
                app = app(granted = setOf("${P}MANAGE_EXTERNAL_STORAGE")),
                tallies = listOf(tally("RECORD_AUDIO", total = 9, background = 9)),
            ),
            context(suppressions = setOf("com.example.app:BG_SENSITIVE_ACCESS")),
        )
        assertTrue(BackgroundSensitiveAccessRule.evaluate(input).isEmpty())
        assertTrue(AllFilesAccessRule.evaluate(input).isNotEmpty())
    }
}

class AllFilesAccessRuleTest {

    @Test
    fun `sideloaded all-files access is CRITICAL`() {
        val findings = AllFilesAccessRule.evaluate(
            RuleInput(
                profile(app = app(installer = null, granted = setOf("${P}MANAGE_EXTERNAL_STORAGE"))),
                context(),
            ),
        )
        assertEquals(Severity.CRITICAL, findings.single().severity)
    }

    @Test
    fun `store-installed all-files access is HIGH, not CRITICAL`() {
        val findings = AllFilesAccessRule.evaluate(
            RuleInput(profile(app = app(granted = setOf("${P}MANAGE_EXTERNAL_STORAGE"))), context()),
        )
        assertEquals(Severity.HIGH, findings.single().severity)
    }

    @Test
    fun `stays quiet without the grant`() {
        assertTrue(AllFilesAccessRule.evaluate(RuleInput(profile(), context())).isEmpty())
    }
}

class AccessibilityAbuseRuleTest {

    @Test
    fun `accessibility plus overlay is CRITICAL`() {
        val findings = AccessibilityAbuseRule.evaluate(
            RuleInput(
                profile(
                    app = app(
                        granted = setOf("${P}SYSTEM_ALERT_WINDOW"),
                        accessibilityServices = listOf("com.example.app/.SpyService"),
                    ),
                ),
                context(),
            ),
        )
        assertEquals(Severity.CRITICAL, findings.single().severity)
        assertTrue(findings.single().evidence.any { it.contains("SpyService") })
    }

    @Test
    fun `a store-installed accessibility tool alone is HIGH`() {
        val findings = AccessibilityAbuseRule.evaluate(
            RuleInput(
                profile(app = app(accessibilityServices = listOf("com.example.app/.Reader"))),
                context(),
            ),
        )
        assertEquals(Severity.HIGH, findings.single().severity)
    }
}

class SmsInterceptionRuleTest {

    @Test
    fun `background SMS reads on a networked app are CRITICAL`() {
        val findings = SmsInterceptionRule.evaluate(
            RuleInput(
                profile(
                    app = app(granted = setOf("${P}READ_SMS", "${P}INTERNET")),
                    tallies = listOf(tally("READ_SMS", total = 4, background = 4)),
                ),
                context(),
            ),
        )
        assertEquals(Severity.CRITICAL, findings.single().severity)
    }

    @Test
    fun `SMS permission with no network and no observed use is not flagged`() {
        val findings = SmsInterceptionRule.evaluate(
            RuleInput(profile(app = app(granted = setOf("${P}READ_SMS"))), context()),
        )
        assertTrue(findings.isEmpty())
    }
}

class DynamicCodeLoadingRuleTest {

    @Test
    fun `code in a writable directory is HIGH`() {
        val findings = DynamicCodeLoadingRule.evaluate(
            RuleInput(
                profile(
                    app = app(
                        executables = listOf(
                            ExecutableArtifact(
                                path = "/data/data/com.example.app/files/payload.dex",
                                sizeBytes = 4096,
                                inWritableDir = true,
                                executableBit = true,
                            ),
                        ),
                    ),
                ),
                context(),
            ),
        )
        assertEquals(Severity.HIGH, findings.single().severity)
        assertTrue(findings.single().evidence.single().contains("payload.dex"))
    }

    @Test
    fun `libraries shipped inside the APK are not flagged`() {
        val findings = DynamicCodeLoadingRule.evaluate(
            RuleInput(
                profile(
                    app = app(
                        executables = listOf(
                            ExecutableArtifact(
                                path = "/data/app/com.example.app-x/lib/arm64/libnative.so",
                                sizeBytes = 200_000,
                                inWritableDir = false,
                            ),
                        ),
                    ),
                ),
                context(),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a sideloaded installer gets its own finding id`() {
        val findings = DynamicCodeLoadingRule.evaluate(
            RuleInput(
                profile(app = app(installer = null, granted = setOf("${P}REQUEST_INSTALL_PACKAGES"))),
                context(),
            ),
        )
        assertEquals("DYNAMIC_CODE.INSTALLER", findings.single().ruleId)
    }
}

class UnusedDangerousPermissionRuleTest {

    @Test
    fun `an unused grant is only LOW`() {
        val findings = UnusedDangerousPermissionRule.evaluate(
            RuleInput(profile(app = app(granted = setOf("${P}CAMERA"))), context(windowHours = 24)),
        )
        assertEquals(Severity.LOW, findings.single().severity)
    }

    @Test
    fun `a used grant is not reported`() {
        val findings = UnusedDangerousPermissionRule.evaluate(
            RuleInput(
                profile(
                    app = app(granted = setOf("${P}CAMERA")),
                    tallies = listOf(tally("CAMERA", total = 3)),
                ),
                context(windowHours = 24),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `stays silent when the window is too short to conclude anything`() {
        val findings = UnusedDangerousPermissionRule.evaluate(
            RuleInput(profile(app = app(granted = setOf("${P}CAMERA"))), context(windowHours = 2)),
        )
        assertTrue(findings.isEmpty(), "two hours of data cannot show a permission is unused")
    }
}

class NeverForegroundRuleTest {

    @Test
    fun `flags sensitive access by an app that was never opened`() {
        val findings = NeverForegroundRule.evaluate(
            RuleInput(
                profile(
                    tallies = listOf(tally("FINE_LOCATION", total = 20)),
                    foregroundTimeMs = 0,
                    sources = setOf("appops", "usage-stats"),
                ),
                context(windowHours = 48),
            ),
        )
        assertEquals(Severity.HIGH, findings.single().severity)
    }

    @Test
    fun `needs usage-stats data to fire at all`() {
        val findings = NeverForegroundRule.evaluate(
            RuleInput(
                profile(
                    tallies = listOf(tally("FINE_LOCATION", total = 20)),
                    foregroundTimeMs = 0,
                    sources = setOf("appops"),
                ),
                context(windowHours = 48),
            ),
        )
        assertTrue(findings.isEmpty(), "without usage stats, 0ms foreground time is unknown, not proven")
    }

    @Test
    fun `an app the user actually used is not flagged`() {
        val findings = NeverForegroundRule.evaluate(
            RuleInput(
                profile(
                    tallies = listOf(tally("FINE_LOCATION", total = 20)),
                    foregroundTimeMs = 30 * 60_000L,
                    sources = setOf("appops", "usage-stats"),
                ),
                context(windowHours = 48),
            ),
        )
        assertTrue(findings.isEmpty())
    }
}

class AccessFrequencyOutlierRuleTest {

    @Test
    fun `flags an app far above the device median`() {
        val findings = AccessFrequencyOutlierRule.evaluate(
            RuleInput(
                profile(tallies = listOf(tally("FINE_LOCATION", total = 500))),
                context(medians = mapOf("FINE_LOCATION" to 10)),
            ),
        )
        assertEquals(Severity.MEDIUM, findings.single().severity)
        assertTrue(findings.single().evidence.single().contains("device median 10"))
    }

    @Test
    fun `an absolute floor stops a tiny median flagging everything`() {
        val findings = AccessFrequencyOutlierRule.evaluate(
            RuleInput(
                // 20x the median, but 20 accesses in a day is not "polling".
                profile(tallies = listOf(tally("FINE_LOCATION", total = 20))),
                context(medians = mapOf("FINE_LOCATION" to 1)),
            ),
        )
        assertTrue(findings.isEmpty())
    }
}

class StaleTargetSdkRuleTest {

    @Test
    fun `flags a pre-scoped-storage target on a modern device`() {
        val findings = StaleTargetSdkRule.evaluate(
            RuleInput(profile(app = app(targetSdk = 25)), context(deviceSdkInt = 34)),
        )
        assertEquals(Severity.MEDIUM, findings.single().severity)
    }

    @Test
    fun `does not flag a current target`() {
        assertTrue(
            StaleTargetSdkRule.evaluate(
                RuleInput(profile(app = app(targetSdk = 34)), context(deviceSdkInt = 34)),
            ).isEmpty(),
        )
    }

    @Test
    fun `does not flag on an old device where the target is normal`() {
        assertTrue(
            StaleTargetSdkRule.evaluate(
                RuleInput(profile(app = app(targetSdk = 28)), context(deviceSdkInt = 28)),
            ).isEmpty(),
        )
    }
}

class RepeatedDenialRuleTest {

    @Test
    fun `flags persistent retries of a denied op`() {
        val findings = RepeatedDenialRule.evaluate(
            RuleInput(
                profile(tallies = listOf(tally("READ_CONTACTS", total = 0, denied = 40))),
                context(),
            ),
        )
        assertEquals(Severity.MEDIUM, findings.single().severity)
    }

    @Test
    fun `a couple of denials is not a pattern`() {
        assertTrue(
            RepeatedDenialRule.evaluate(
                RuleInput(profile(tallies = listOf(tally("READ_CONTACTS", total = 0, denied = 2))), context()),
            ).isEmpty(),
        )
    }
}

class WeakHardeningRuleTest {

    @Test
    fun `a debuggable build is MEDIUM`() {
        val findings = WeakHardeningRule.evaluate(
            RuleInput(profile(app = app(isDebuggable = true)), context()),
        )
        assertEquals(Severity.MEDIUM, findings.single().severity)
    }

    @Test
    fun `backup alone is only LOW`() {
        val findings = WeakHardeningRule.evaluate(
            RuleInput(profile(app = app(allowsBackup = true)), context()),
        )
        assertEquals(Severity.LOW, findings.single().severity)
    }

    @Test
    fun `a clean manifest produces nothing`() {
        assertTrue(WeakHardeningRule.evaluate(RuleInput(profile(), context())).isEmpty())
    }
}

class ExfiltrationShapeRuleTest {

    @Test
    fun `requires the reads to have actually happened`() {
        val app = app(
            installer = null,
            granted = setOf("${P}INTERNET", "${P}READ_CONTACTS", "${P}READ_SMS"),
        )
        // Permissions held, nothing observed: no finding.
        assertTrue(ExfiltrationShapeRule.evaluate(RuleInput(profile(app = app), context())).isEmpty())

        val withUse = profile(
            app = app,
            tallies = listOf(tally("READ_CONTACTS", total = 5), tally("READ_SMS", total = 5)),
        )
        assertTrue(ExfiltrationShapeRule.evaluate(RuleInput(withUse, context())).isNotEmpty())
    }

    @Test
    fun `no network access means no exfiltration shape`() {
        val findings = ExfiltrationShapeRule.evaluate(
            RuleInput(
                profile(
                    app = app(granted = setOf("${P}READ_CONTACTS", "${P}READ_SMS")),
                    tallies = listOf(tally("READ_CONTACTS", total = 5)),
                ),
                context(),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a store app gets a milder verdict than a sideload`() {
        val tallies = listOf(tally("READ_CONTACTS", total = 5), tally("READ_SMS", total = 5))
        val permissions = setOf("${P}INTERNET", "${P}READ_CONTACTS", "${P}READ_SMS")
        val store = ExfiltrationShapeRule.evaluate(
            RuleInput(profile(app = app(granted = permissions), tallies = tallies), context()),
        ).single()
        val sideload = ExfiltrationShapeRule.evaluate(
            RuleInput(profile(app = app(installer = null, granted = permissions), tallies = tallies), context()),
        ).single()
        assertTrue(sideload.severity > store.severity)
    }
}
