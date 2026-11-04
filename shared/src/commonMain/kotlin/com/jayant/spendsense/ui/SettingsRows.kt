package com.jayant.spendsense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.ui.components.NeoPopSurface
import com.jayant.spendsense.ui.theme.spend

/** Uppercase grey section label followed by flat rows. */
@Composable
fun SettingsGroup(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(
            label.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

/** Flat row: title + one grey line on the left, [trailing] on the right, hairline underneath. */
@Composable
fun SettingsRow(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val line = MaterialTheme.spend.hairline
    Row(
        modifier.fillMaxWidth()
            .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** "ON" in lime, "OFF" in grey. */
@Composable
fun StatusText(on: Boolean) {
    Text(
        if (on) "ON" else "OFF", style = MaterialTheme.typography.labelSmall,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatusChevron(on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusText(on)
        if (!on) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Square NeoPOP toggle: selected = white face / black text, unselected = black face / hairline. */
@Composable
fun SquareToggle(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    NeoPopSurface(
        color = if (selected) fg else bg,
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        depth = 3.dp,
        edge = if (selected) Color(0xFF6E6E6E) else Color(0xFF3A3A3A),
        border = if (selected) null else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f),
    ) {
        Text(
            label.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = if (selected) bg else fg, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** ON/OFF switch drawn as a square NeoPOP block. */
@Composable
fun OnOffToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    SquareToggle(if (checked) "On" else "Off", checked, { onChange(!checked) }, Modifier.width(64.dp))
}

/** Square text field with a 1dp hairline border that turns white on focus. */
@Composable
fun SquareField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fg = MaterialTheme.colorScheme.onBackground
    BasicTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        modifier = modifier.fillMaxWidth()
            .border(1.dp, if (focused) fg else fg.copy(alpha = 0.22f), RectangleShape),
        singleLine = singleLine, maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = fg),
        cursorBrush = SolidColor(fg),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        },
    )
}
