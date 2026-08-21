package ai.synheart.core.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small shared building blocks. Kept in one file because there are only a
 * handful and none of them is interesting on its own — the point of this
 * example is the SDK integration, not the widget tree.
 */

/** A titled card with an optional trailing composable and a vertical body. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        // Flat with a hairline outline, matching the Flutter example's card
        // theme. Elevation alone is invisible here: the card and the page share
        // a surface colour, so without the border the sections run together.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * A compact state chip. [tone] drives the colour so status reads at a glance
 * without the caller picking colours.
 */
enum class PillTone { NEUTRAL, GOOD, WARN, BAD }

@Composable
fun StatusPill(label: String, tone: PillTone = PillTone.NEUTRAL) {
    val scheme = MaterialTheme.colorScheme
    val (fg, bg) = when (tone) {
        PillTone.GOOD -> Color(0xFF1B5E20) to Color(0xFFD7F0DC)
        PillTone.WARN -> Color(0xFF7A4B00) to Color(0xFFFBE9C7)
        PillTone.BAD -> Color(0xFF8C1D18) to Color(0xFFF9DEDC)
        PillTone.NEUTRAL -> scheme.onSurfaceVariant to scheme.surfaceContainerHighest
    }
    Text(
        label,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A label/value row with a monospace value, for identifiers and numbers. */
@Composable
fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.Start, Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(128.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A consent toggle with an explanation of what granting it actually permits. */
@Composable
fun ConsentToggle(
    title: String,
    description: String,
    value: Boolean,
    onChanged: (Boolean) -> Unit,
    /**
     * What the runtime currently enforces. When this disagrees with [value] the
     * user has a pending edit that has not been submitted yet.
     */
    enforced: Boolean? = null,
) {
    val pending = enforced != null && enforced != value
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (pending) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill("unsaved", PillTone.WARN)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = value, onCheckedChange = onChanged)
    }
}

/** A full-width banner for an error the user needs to read in full. */
@Composable
fun ErrorBanner(message: String, onDismiss: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .background(scheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = scheme.onErrorContainer,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = scheme.onErrorContainer,
        )
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(18.dp),
                    tint = scheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * A monospace code block, for config listings and symbol lists.
 *
 * Scrolls sideways rather than wrapping. Wrapped code reflows mid-expression and
 * stops looking like something you can copy — a `SynheartConfig(` listing broken
 * across lines at arbitrary points reads as three statements instead of one.
 */
@Composable
fun CodeBlock(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(10.dp),
            )
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
