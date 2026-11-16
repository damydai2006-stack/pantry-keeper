package com.damy.pantry.debug

import androidx.compose.runtime.Composable

object DebugInput {
    fun bind(block: (String) -> Unit) = Unit
}

object LoginDebug {
    @Composable
    fun FillButton(onFill: (String, String) -> Unit) = Unit
}
