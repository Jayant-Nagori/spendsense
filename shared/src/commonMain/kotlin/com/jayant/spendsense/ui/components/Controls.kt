package com.jayant.spendsense.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * NeoPOP toggle: selected = white face / black label, unselected = black face / hairline / grey label.
 * [accent] overrides the selected face colour (default white).
 */
@Composable
fun SpendChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    accent: Color = Color.Unspecified,
) {
    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    val face by animateColorAsState(if (selected) (if (accent.isSpecified()) accent else fg) else bg, tween(150), label = "chipFace")
    val text by animateColorAsState(if (selected) bg else MaterialTheme.colorScheme.onSurfaceVariant, tween(150), label = "chipText")
    NeoPopSurface(
        color = face,
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp).semantics { this.selected = selected; role = Role.Button },
        depth = 3.dp,
        edge = if (selected) lerp(face, Color.Black, 0.5f) else lerp(bg, fg, 0.22f),
        border = if (selected) null else fg.copy(alpha = 0.22f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            // Icons follow the label colour so they stay visible on the white selected face.
            leading?.let { CompositionLocalProvider(LocalContentColor provides text) { it() } }
            Text(label, style = MaterialTheme.typography.labelLarge, color = text, maxLines = 1)
        }
    }
}

private fun Color.isSpecified() = this != Color.Unspecified

/** Small square NeoPOP icon button (back, month arrows, steppers). */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    NeoPopSurface(
        color = bg, onClick = onClick, enabled = enabled,
        modifier = modifier.size(size), depth = 2.dp,
        edge = lerp(bg, fg, 0.22f), border = fg.copy(alpha = 0.22f),
    ) {
        Icon(icon, description, tint = fg, modifier = Modifier.size(20.dp))
    }
}

/** Borderless icon button, 40dp square touch target (settings gear). */
@Composable
fun PlainIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).pressable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** Square white NeoPOP "+ ADD" block for the bottom-right of Home and Activity. */
@Composable
fun AddFab(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "ADD") {
    NeoPopSurface(
        color = MaterialTheme.colorScheme.onBackground, onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp), depth = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(20.dp))
            Text(
                label, style = MaterialTheme.typography.labelLarge.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing),
                color = MaterialTheme.colorScheme.background, maxLines = 1,
            )
        }
    }
}

/** Square checkbox: white fill with black tick when on, hairline outline when off. */
@Composable
fun SquareCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onBackground
    Box(
        modifier.size(24.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (checked) fg else Color.Transparent)
            .border(1.dp, if (checked) fg else fg.copy(alpha = 0.4f), MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(16.dp))
    }
}
