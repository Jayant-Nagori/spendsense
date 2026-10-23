package com.jayant.spendsense.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom sheet that asks "was it all yours?" for one transaction and persists via TransactionRepository.setSplit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(txn: Transaction, onDismiss: () -> Unit, startAsSplit: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding()) {
            var note: String? = null
            SplitContent(txn, saveLabel = "Save", startAsSplit = startAsSplit, onNote = { note = it }, onSave = { isSplit, share ->
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                val text = note
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val repo = TransactionRepository.get(context)
                        if (text != null) repo.setNote(txn.id, text)
                        repo.setSplit(txn.id, isSplit, share)
                    }
                    sheetState.hide()
                    onDismiss()
                }
            })
        }
    }
}
