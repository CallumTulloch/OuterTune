package com.dd3boh.outertune.ui.screens.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Saved with the navigation entry, independently of keyboard focus and result view models. */
class SearchInputState(initialQuery: String = "") {
    var query by mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length)))
    var submittedQuery by mutableStateOf(initialQuery)
    var showingSuggestions by mutableStateOf(initialQuery.isEmpty())
    var focusRequested by mutableStateOf(initialQuery.isEmpty())
    var handledEntryRequest by mutableStateOf(0L)

    fun edit(value: TextFieldValue) {
        query = value
        showingSuggestions = true
    }

    fun submit(text: String) {
        query = TextFieldValue(text, TextRange(text.length))
        submittedQuery = text
        showingSuggestions = text.isBlank()
        focusRequested = false
    }

    fun reset() {
        query = TextFieldValue()
        submittedQuery = ""
        showingSuggestions = true
        focusRequested = true
    }

    companion object {
        val Saver = listSaver<SearchInputState, Any>(
            save = { listOf(it.query.text, it.query.selection.start, it.query.selection.end,
                it.submittedQuery, it.showingSuggestions, it.focusRequested, it.handledEntryRequest) },
            restore = { values -> SearchInputState().apply {
                query = TextFieldValue(values[0] as String, TextRange(values[1] as Int, values[2] as Int))
                submittedQuery = values[3] as String
                showingSuggestions = values[4] as Boolean
                focusRequested = values[5] as Boolean
                handledEntryRequest = values[6] as Long
            } },
        )
    }
}
