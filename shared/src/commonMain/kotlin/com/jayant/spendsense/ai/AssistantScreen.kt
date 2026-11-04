package com.jayant.spendsense.ai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jayant.spendsense.ui.SquareField
import com.jayant.spendsense.ui.components.NeoPopSurface
import com.jayant.spendsense.ui.theme.spend

private val SUGGESTIONS = listOf(
    "Where did I spend \u20B9254 yesterday?",
    "How much did I spend on food last week?",
    "What's my biggest expense this month?",
    "How much do people owe me from splits?",
)

@Composable
fun AssistantScreen() {
    val ctx = com.jayant.spendsense.platformContext()
    val vm: AssistantViewModel = viewModel { AssistantViewModel(ctx) }
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(Unit) { vm.refreshKey() }
    LaunchedEffect(state.messages.size, state.loading) {
        val count = state.messages.size + if (state.loading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit(text: String) {
        if (text.isBlank() || state.loading) return
        vm.send(text)
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Header(canClear = state.messages.isNotEmpty() && !state.loading, onClear = vm::clear)
        if (!state.hasKey) OfflineStrip()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && !state.loading) {
                EmptyState(onPick = ::submit)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(state.messages, key = { it.id }) { MessageRow(it) }
                    if (state.loading) item(key = "typing") { TypingIndicator() }
                }
            }
        }

        InputBar(
            value = input,
            enabled = !state.loading,
            onChange = { input = it },
            onSend = { submit(input) },
        )
        // Room for the app's bottom bar; the keyboard replaces it while typing.
        Spacer(Modifier.height(if (imeVisible) 0.dp else 72.dp))
    }
}

@Composable
private fun Header(canClear: Boolean, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ASK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (canClear) {
            Text(
                "CLEAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onClear).padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun OfflineStrip() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RectangleShape))
        Spacer(Modifier.size(8.dp))
        Text(
            "Offline mode \u00B7 add an AI key in Settings for full AI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    val line = MaterialTheme.spend.hairline
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
        Text("Ask about your money", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(32.dp))
        Column(Modifier.fillMaxWidth().drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }) {
            SUGGESTIONS.forEach { s ->
                Row(
                    Modifier.fillMaxWidth()
                        .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                        .clickable { onPick(s) }
                        .heightIn(min = 56.dp).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(12.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessage) {
    if (m.fromUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                m.text,
                modifier = Modifier.widthIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RectangleShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                renderMarkdownLite(m.text),
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (m.txns.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().drawBehind {
                    drawLine(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }) { m.txns.forEach { TxnMiniCard(it) } }
            }
        }
    }
}

/** Renders **bold** spans; leaves everything else untouched. */
private fun renderMarkdownLite(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1 && i != parts.lastIndex) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) }
        else append(if (i % 2 == 1) "**$part" else part)
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(6.dp).alpha(a).background(MaterialTheme.colorScheme.onSurface, RectangleShape))
        }
    }
}

@Composable
private fun InputBar(value: String, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareField(
            value = value,
            onChange = onChange,
            placeholder = "Ask about a payment, total or split",
            modifier = Modifier.weight(1f),
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.size(12.dp))
        val canSend = enabled && value.isNotBlank()
        NeoPopSurface(
            color = MaterialTheme.colorScheme.onBackground,
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(52.dp),
            depth = 3.dp,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Send",
                tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(22.dp),
            )
        }
    }
}
