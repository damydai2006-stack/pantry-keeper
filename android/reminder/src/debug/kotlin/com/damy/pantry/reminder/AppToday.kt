package com.damy.pantry.reminder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.damy.pantry.data.shanghaiToday
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

object AppToday {
    val flow = MutableStateFlow(shanghaiToday())
    fun today(): LocalDate = flow.value
    fun simulate(date: LocalDate) {
        flow.value = date
    }
}

object ReminderDebug {
    @Composable
    fun Tools(onSimulate: (LocalDate) -> Unit, onFire: () -> Unit) {
        Column {
            Text("模拟今天", fontSize = 16.sp)
            TextButton(onClick = { onSimulate(LocalDate(2026, 11, 15)) }) { Text("模拟今天") }
            TextButton(onClick = onFire) { Text("立即生成今日汇总") }
        }
    }
}
