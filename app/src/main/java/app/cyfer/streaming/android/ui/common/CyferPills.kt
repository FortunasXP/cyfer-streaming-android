package app.cyfer.streaming.android.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cyfer.streaming.android.ui.theme.CyferAccent
import app.cyfer.streaming.android.ui.theme.CyferBlack
import app.cyfer.streaming.android.ui.theme.CyferCardSurface
import app.cyfer.streaming.android.ui.theme.CyferCardSurfaceLight
import app.cyfer.streaming.android.ui.theme.CyferTextSecondary
import app.cyfer.streaming.android.ui.theme.CyferTextTertiary
import app.cyfer.streaming.android.ui.theme.CyferWhite

/**
 * Cyfer's three canonical pill components. Replaces the 7+ ad-hoc
 * Surface/Text combos that had drifted across the screens.
 *
 *   • [CyferTagPill]    — tiny non-interactive badges in stream rows
 *                         (provider kind, resolution, language). 3 dp radius.
 *   • [CyferOutlinePill] — outlined variant of the tag pill for low-signal
 *                          metadata (channel layout, lang codes). 3 dp radius.
 *   • [CyferChip]       — toggleable filter/sort chip with optional count.
 *                         Used for picker filters, browse genres, my list
 *                         filter/sort, calendar filter. 14 dp radius.
 *   • [CyferTabPill]    — full-size tab pill for horizontal tab strips
 *                         (Settings tabs). 20 dp radius.
 *
 * The corner-radius progression (3 → 14 → 20) is the unified scale —
 * never invent a new in-between value.
 */

// ─────────────────────────── Tag (3 dp radius) ───────────────────────────

@Composable
fun CyferTagPill(
    text: String,
    background: Color = CyferCardSurfaceLight,
    foreground: Color = CyferWhite,
) {
    Surface(color = background, shape = RoundedCornerShape(3.dp)) {
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun CyferOutlinePill(text: String, border: Color = CyferTextTertiary) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = text,
            color = CyferTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

// ─────────────────────────── Chip (14 dp radius) ───────────────────────────

@Composable
fun CyferChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) CyferAccent else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, CyferCardSurfaceLight),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = label,
                color = if (selected) CyferBlack else CyferTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    color = if (selected) CyferBlack.copy(alpha = 0.65f) else CyferTextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ─────────────────────────── Tab pill (20 dp radius) ───────────────────────────

@Composable
fun CyferTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) CyferAccent else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, CyferCardSurfaceLight),
    ) {
        Text(
            text = label,
            color = if (selected) CyferBlack else CyferTextSecondary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
