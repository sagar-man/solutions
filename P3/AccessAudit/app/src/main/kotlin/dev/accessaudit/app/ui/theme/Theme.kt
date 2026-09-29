package dev.accessaudit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.accessaudit.core.model.RiskLevel
import dev.accessaudit.core.model.Severity

/**
 * Severity colours.
 *
 * Deliberately not red-only: colour is paired with the severity word everywhere
 * it appears, because a colour-blind user must be able to triage this list. The
 * dark-theme variants are lightened rather than reused so contrast holds on both.
 */
public object RiskColors {
    public val critical: Color = Color(0xFFB3261E)
    public val high: Color = Color(0xFFD1581A)
    public val medium: Color = Color(0xFF9A6700)
    public val low: Color = Color(0xFF3F6212)
    public val info: Color = Color(0xFF4A5568)

    public val criticalDark: Color = Color(0xFFFFB4AB)
    public val highDark: Color = Color(0xFFFFB68C)
    public val mediumDark: Color = Color(0xFFE8C468)
    public val lowDark: Color = Color(0xFFA3D177)
    public val infoDark: Color = Color(0xFFA0AEC0)

    @Composable
    public fun forSeverity(severity: Severity): Color {
        val dark = isSystemInDarkTheme()
        return when (severity) {
            Severity.CRITICAL -> if (dark) criticalDark else critical
            Severity.HIGH -> if (dark) highDark else high
            Severity.MEDIUM -> if (dark) mediumDark else medium
            Severity.LOW -> if (dark) lowDark else low
            Severity.INFO -> if (dark) infoDark else info
        }
    }

    @Composable
    public fun forLevel(level: RiskLevel): Color = when (level) {
        RiskLevel.HIGH_RISK -> forSeverity(Severity.CRITICAL)
        RiskLevel.SUSPICIOUS -> forSeverity(Severity.HIGH)
        RiskLevel.NOTABLE -> forSeverity(Severity.MEDIUM)
        RiskLevel.CLEAN -> forSeverity(Severity.LOW)
    }
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F4E79),
    secondary = Color(0xFF4A5568),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9EC8F0),
    secondary = Color(0xFFA0AEC0),
)

@Composable
public fun AccessAuditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
