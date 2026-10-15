/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Shared "Material Expressive 3" building blocks used by every screen.
 */

package org.lineageos.settings.galaxian.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Default horizontal gutter matching the home-screen card grid. */
val PageGutter: Dp = 20.dp

// ──────────────────────────────────────────────────────────────────────────
// Pressable card — scales down slightly while pressed (expressive touch).
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun PressableCard(
    onClick:   (() -> Unit)?,
    modifier:  Modifier = Modifier,
    shape:     Shape = MaterialTheme.shapes.extraLarge,
    colors:    CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    content:   @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed    by interaction.collectIsPressedAsState()
    val pressScale  = animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(),
        label = "pressScale",
    ).value

    val animated = Modifier.graphicsLayer {
        scaleX = pressScale
        scaleY = pressScale
    }

    if (onClick != null) {
        Card(
            onClick           = onClick,
            interactionSource = interaction,
            modifier          = modifier.then(animated),
            shape             = shape,
            colors            = colors,
            content           = content,
        )
    } else {
        Card(
            modifier = modifier.then(animated),
            shape    = shape,
            colors   = colors,
            content  = content,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Icon tile — expressive rounded container for leading icons.
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun IconTile(
    icon:       ImageVector,
    modifier:   Modifier = Modifier,
    size:       Dp = 44.dp,
    container:  Color = MaterialTheme.colorScheme.primaryContainer,
    tint:       Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier           = modifier
            .size(size)
            .clip(MaterialTheme.shapes.large)
            .background(container),
        contentAlignment   = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = tint,
            modifier           = Modifier.size(size * 0.55f),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Grouped card shape — for stacked lists sharing one rounded silhouette.
// ──────────────────────────────────────────────────────────────────────────

@Composable
@ReadOnlyComposable
fun groupedCardShape(index: Int, total: Int): Shape {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    return when {
        total == 1         -> outer
        index == 0         -> outer.copy(bottomStart = inner.bottomStart, bottomEnd = inner.bottomEnd)
        index == total - 1 -> outer.copy(topStart = inner.topStart, topEnd = inner.topEnd)
        else               -> inner
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Stat card — compact label/value tile for metric grids.
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun StatCard(
    label:   String,
    value:   String,
    modifier: Modifier = Modifier,
    icon:    ImageVector? = null,
    tint:    Color = MaterialTheme.colorScheme.primary,
    sub:     String? = null,
) {
    Card(
        modifier = modifier,
        shape    = MaterialTheme.shapes.large,
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector        = icon,
                        contentDescription = null,
                        tint               = tint,
                        modifier           = Modifier.size(16.dp),
                    )
                }
                Text(
                    text     = label,
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text     = value,
                style    = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    text     = sub,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Monitor bar — animated expressive progress bar with label + live value.
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun MonitorBar(
    label:   String,
    valueText: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    color:   Color = MaterialTheme.colorScheme.primary,
    barHeight: Dp = 10.dp,
) {
    val animated = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.spring(),
        label = "monitorBar",
    ).value

    Column(modifier = modifier) {
        Row(
            modifier               = Modifier.fillMaxWidth(),
            horizontalArrangement  = Arrangement.SpaceBetween,
            verticalAlignment      = Alignment.CenterVertically,
        ) {
            Text(
                text     = label,
                style    = MaterialTheme.typography.labelLarge,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (secondaryText != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text     = secondaryText,
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress        = { animated },
            modifier        = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(CircleShape),
            color           = color,
            trackColor      = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap       = StrokeCap.Round,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Temperature chip — tinted by heat (cool → warm → hot).
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun TempChip(
    label: String,
    tempC: Float?,
    modifier: Modifier = Modifier,
) {
    val color = when {
        tempC == null -> MaterialTheme.colorScheme.onSurfaceVariant
        tempC < 42f   -> MaterialTheme.colorScheme.primary
        tempC < 55f   -> MaterialTheme.colorScheme.tertiary
        else          -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text  = if (tempC != null) "${"%.1f".format(tempC)} °C" else "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Screen header used by monitor screens (title + live badge).
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title:  String,
    modifier: Modifier = Modifier,
) {
    Text(
        text  = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 36.dp, top = 24.dp, bottom = 8.dp),
    )
}
