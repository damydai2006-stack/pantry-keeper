package com.damy.pantry.reminder

import androidx.compose.runtime.Composable
import com.damy.pantry.data.shanghaiToday
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

object AppToday {
    val flow = MutableStateFlow(shanghaiToday())
    fun today(): LocalDate = shanghaiToday().also { flow.value = it }
    fun simulate(date: LocalDate) = Unit
}

object ReminderDebug {
    @Composable
    fun Tools(onSimulate: (LocalDate) -> Unit, onFire: () -> Unit) = Unit
}
