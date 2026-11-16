package com.damy.pantry.reminder

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

object AppToday {
    val flow = MutableStateFlow(deviceToday())
    fun today(): LocalDate = deviceToday().also { flow.value = it }
    fun refresh() {
        flow.value = deviceToday()
    }
    fun simulate(date: LocalDate) = Unit
}

object ReminderDebug {
    @Composable
    fun Tools(onSimulate: (LocalDate) -> Unit, onFire: () -> Unit) = Unit
}
