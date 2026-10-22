package com.jayant.spendsense.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val txns: List<Transaction> = emptyList(),
    val isError: Boolean = false,
)

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = false,
    val hasKey: Boolean = true,
)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val data = RepoAssistantData(app)
    private val assistant = Assistant(
        engine = AssistantEngine(AnthropicApi { settings.anthropicApiKey }, AssistantTools(data)),
        local = LocalAnswerer(data),
        hasKey = { settings.anthropicApiKey.isNotBlank() },
    )
    private var nextId = 1L

    private val _state = MutableStateFlow(AssistantUiState(hasKey = settings.anthropicApiKey.isNotBlank()))
    val state: StateFlow<AssistantUiState> = _state

    /** Call when the screen becomes visible: the key may have been added in Settings. */
    fun refreshKey() {
        val has = settings.anthropicApiKey.isNotBlank()
        _state.update { it.copy(hasKey = has) }
    }

    fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.loading) return
        _state.update {
            it.copy(messages = it.messages + ChatMessage(nextId++, true, q), loading = true, hasKey = settings.anthropicApiKey.isNotBlank())
        }
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { assistant.ask(q) }
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(nextId++, false, reply.text, reply.txns, reply.mode == ReplyMode.ERROR),
                    loading = false,
                )
            }
        }
    }

    fun clear() {
        if (_state.value.loading) return
        assistant.reset()
        _state.update { it.copy(messages = emptyList()) }
    }
}
