package com.damy.pantry.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.damy.pantry.data.CachedItem
import com.damy.pantry.data.PantryRepository
import com.pantrykeeper.core.reminder.ReminderRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

object ReminderNav {
    var open: Boolean = false
}

private val brand = Color(0xFF1E4A62)
private val bg = Color(0xFFF4F1EB)
private val amber = Color(0xFF8A3D00)
private val amberSoft = Color(0xFFFEF0E4)

fun notificationsAllowed(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
fun notificationsAllowed(): Boolean = notificationsAllowed(LocalContext.current)

@Composable
fun NotificationBanner(onOpen: () -> Unit) {
    if (notificationsAllowed()) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).background(amberSoft, RoundedCornerShape(12.dp)).padding(12.dp).testTag("notif_banner"),
    ) {
        Text("未开启通知，可能错过到期提醒。", color = amber, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpen, modifier = Modifier.testTag("notif_settings")) { Text("去设置", color = amber, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun ReminderList(items: List<CachedItem>, today: LocalDate) {
    val stock = items.filter { it.status == "in_stock" }
    val groups = com.damy.pantry.data.groupItems(today, stock, "")
    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(20.dp).testTag("remind_list")) {
        Text("提醒", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("已过期置顶。档位为空的物品仍在这里，只是不发通知。", fontSize = 16.sp, color = Color(0xFF5A615B), modifier = Modifier.padding(top = 4.dp))
        if (stock.isEmpty()) {
            Text("暂无在库物品需要关注", fontSize = 16.sp, color = Color(0xFF5A615B), modifier = Modifier.padding(top = 24.dp).testTag("remind_empty"))
            return
        }
        groups.forEach { group ->
            val tone = groupTone(group.bucket)
            Text("${group.title} ${group.items.size}件", fontWeight = FontWeight.Bold, color = tone, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            group.items.forEach { item ->
                val expiry = kotlinx.datetime.LocalDate.parse(item.expiryDate)
                val days = ReminderRules.daysRemaining(today, expiry)
                val label = com.damy.pantry.data.daysLabel(days)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color.White, RoundedCornerShape(12.dp)).heightIn(min = 72.dp).testTag("remind_${item.name}"),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(6.dp).heightIn(min = 72.dp).background(tone))
                    Column(Modifier.weight(1f).padding(12.dp)) {
                        Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(item.expiryDate, fontSize = 16.sp, color = Color(0xFF3E4640))
                    }
                    Text(label, color = tone, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }
    }
}

private fun groupTone(bucket: com.damy.pantry.data.ListBucket): Color = when (bucket) {
    com.damy.pantry.data.ListBucket.EXPIRED -> Color(0xFF9B1C1C)
    com.damy.pantry.data.ListBucket.SOON -> Color(0xFFB54708)
    com.damy.pantry.data.ListBucket.MONTH -> Color(0xFF8A5A00)
    com.damy.pantry.data.ListBucket.LATER -> Color(0xFF166534)
}

@Composable
fun NotifyTimeScreen(repo: PantryRepository, scope: CoroutineScope, onBack: () -> Unit) {
    val context = LocalContext.current
    var time by remember { mutableStateOf(repo.session.notifyTime ?: "09:00") }
    var explain by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(20.dp).testTag("notify_time")) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("每天提醒时间", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("按这台手机的本地时区。默认 09:00。", fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp))
        listOf("07:00", "09:00", "18:00", "21:00").forEach { option ->
            Text(
                option,
                fontSize = 18.sp,
                fontWeight = if (option == time) FontWeight.Bold else FontWeight.Normal,
                color = if (option == time) brand else Color(0xFF1A1D1A),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { time = option }.padding(vertical = 12.dp).testTag("time_$option"),
            )
        }
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) explain = true
                else scope.launch {
                    runCatching { repo.updateNotifyTime(time) }
                    repo.session.notifyTime = time
                    ReminderRuntime.reschedule(context, time)
                    onBack()
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("time_save"),
            colors = ButtonDefaults.buttonColors(containerColor = brand),
        ) { Text("保存") }
        ReminderDebug.Tools(
            onSimulate = { AppToday.simulate(it) },
            onFire = {
                val items = repo.items.value
                ReminderRuntime.postSummary(context, summaryText(AppToday.today(), items))
            },
        )
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("开启到期提醒") },
            text = { Text("用于每天定时发送到期汇总，不会用于营销。", modifier = Modifier.testTag("notif_explain")) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("允许通知") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("以后再说") } },
        )
    }
}

fun openNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    context.startActivity(intent)
}

@Composable
fun rememberToday(): LocalDate {
    AppToday.refresh()
    val value by AppToday.flow.collectAsState()
    return value
}

@Composable
fun ReminderAccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(20.dp).testTag("reminder_access")) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("让提醒准时送达", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "每天只发一条汇总。通知、精确闹钟和后台运行都打开后，到点才会响。",
            fontSize = 16.sp,
            color = Color(0xFF3E4640),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        AccessRow(
            title = "通知",
            body = if (notificationsAllowed(context)) "已开启" else "未开启。不开启就收不到每天的汇总。",
            tag = "access_notifications",
        ) { openNotificationSettings(context) }
        AccessRow(
            title = "闹钟和提醒",
            body = if (exactAlarmGranted(context)) "已允许精确闹钟" else "未允许。未授权时提醒落在 10 分钟窗口里，不作为到点验收。",
            tag = "access_exact",
        ) { openExactAlarmSettings(context) }
        AccessRow(
            title = "自启动和电池",
            body = "国产手机请允许自启动、允许后台运行，并关闭电池优化。",
            tag = "access_battery",
        ) { openBatterySettings(context) }
    }
}

@Composable
private fun AccessRow(title: String, body: String, tag: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(body, fontSize = 16.sp, color = Color(0xFF3E4640), modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text("去设置", color = brand, fontWeight = FontWeight.Bold) }
    }
}

fun exactAlarmGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true
    val alarm = context.getSystemService(android.app.AlarmManager::class.java) ?: return true
    return alarm.canScheduleExactAlarms()
}

fun openExactAlarmSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openBatterySettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
