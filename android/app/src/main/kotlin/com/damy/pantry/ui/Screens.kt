package com.damy.pantry.ui

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damy.pantry.data.AppEvent
import com.damy.pantry.data.CachedItem
import com.damy.pantry.data.Family
import com.damy.pantry.data.HistoryViewModel
import com.damy.pantry.data.ItemFormViewModel
import com.damy.pantry.data.ListBucket
import com.damy.pantry.data.ListViewModel
import com.damy.pantry.data.LoginViewModel
import com.damy.pantry.data.Member
import com.damy.pantry.data.PantryRepository
import com.damy.pantry.data.categoryLabel
import com.damy.pantry.data.daysLabel
import com.damy.pantry.data.groupItems
import com.damy.pantry.data.undoCountdown
import com.damy.pantry.data.userFacingMessage
import com.pantrykeeper.core.model.FieldLimits
import com.damy.pantry.debug.DebugInput
import com.damy.pantry.debug.LoginDebug
import com.pantrykeeper.core.date.ShelfUnit
import com.pantrykeeper.core.reminder.ReminderRules
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private val card = RoundedCornerShape(16.dp)
private val button = RoundedCornerShape(12.dp)
private val locations = listOf("冰箱", "冷冻", "橱柜", "药箱", "其他")
private val tiers = listOf("D5" to "提前 5 天", "D10" to "提前 10 天", "D30" to "提前 30 天", "M2" to "提前 2 个月", "M3" to "提前 3 个月")

@Composable
fun LoginScreen(vm: LoginViewModel, onFamily: () -> Unit, onHome: () -> Unit) {
    val phone by vm.phone.collectAsState()
    val code by vm.code.collectAsState()
    val agreed by vm.agreed.collectAsState()
    val seconds by vm.secondsLeft.collectAsState()
    val error by vm.error.collectAsState()
    Column(Modifier.fillMaxSize().background(Surface).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Brand), contentAlignment = Alignment.Center) {
            Text("米", color = Surface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("大米保质期", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Text)
        Text("食品、药品、调料，到期不再错过", color = TextSecondary, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Text("手机号", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        OutlinedTextField(
            value = phone,
            onValueChange = { vm.phone.value = it.filter(Char::isDigit).take(11) },
            modifier = Modifier.fillMaxWidth().testTag("login_phone"),
            placeholder = { Text("请输入 11 位手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Spacer(Modifier.height(12.dp))
        Text("验证码", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { vm.code.value = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.weight(1f).testTag("login_code"),
                placeholder = { Text("6 位验证码") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.requestCode() },
                enabled = vm.canRequestCode(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("login_code_btn"),
            ) { Text(if (seconds > 0) "${seconds} 秒后可重发" else "获取验证码") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("login_agree").clickable { vm.agreed.value = !agreed }) {
            Checkbox(checked = agreed, onCheckedChange = { vm.agreed.value = it })
            Text(keepPhrases("我已阅读并同意《用户协议》和《隐私政策》"), fontSize = 16.sp, maxLines = 2)
        }
        if (!agreed || error != null) {
            Text(error ?: "请先勾选用户协议和隐私政策", color = Danger, fontSize = 16.sp)
        }
        Button(
            onClick = { vm.login { hasFamily -> if (hasFamily) onHome() else onFamily() } },
            enabled = vm.canLogin(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_submit"),
            shape = button,
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
        ) { Text("登录 / 注册", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        LoginDebug.FillButton { filledPhone, filledCode ->
            vm.phone.value = filledPhone
            vm.code.value = filledCode
        }
        Text("未注册的手机号验证后将自动创建账号", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun FamilyChooseScreen(onCreate: () -> Unit, onJoin: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("加入家庭", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Choice("创建家庭", "你将成为管理员，并得到邀请码", onCreate, "family_create")
        Choice("用邀请码加入", "输入家人分享的 6 位邀请码", onJoin, "family_join")
        TextButton(onClick = onLogout, modifier = Modifier.testTag("family_logout")) { Text("退出登录", color = TextSecondary) }
    }
}

@Composable
fun NicknameScreen(repo: PantryRepository, onBack: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(repo.session.displayName?.takeIf { it.isNotBlank() && it != "用户" }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        TopBar("昵称", onBack)
        Text("这个名字会显示在家庭成员列表里。", color = TextSecondary, fontSize = 16.sp)
        OutlinedTextField(
            name,
            { name = FieldLimits.takeCodePoints(it, FieldLimits.DISPLAY_NAME_MAX) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("nickname"),
            placeholder = { Text("1 到 20 个字") },
            singleLine = true,
        )
        error?.let { Text(it, color = Danger, fontSize = 16.sp, modifier = Modifier.testTag("nickname_error")) }
        Button(
            onClick = {
                scope.launch {
                    runCatching { repo.updateDisplayName(name) }
                        .onSuccess { onSaved() }
                        .onFailure { error = userFacingMessage(it) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("nickname_save"),
            shape = button,
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
        ) { Text("保存") }
    }
}

@Composable
fun FamilyCreateScreen(repo: PantryRepository, onBack: () -> Unit, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<Family?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        TopBar("创建家庭", onBack)
        if (created == null) {
            Text("家庭名称", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                name,
                { name = FieldLimits.takeCodePoints(it, FieldLimits.FAMILY_NAME_MAX) },
                modifier = Modifier.fillMaxWidth().testTag("family_name").onFocusChanged { state ->
                    if (state.isFocused) DebugInput.bind { name = FieldLimits.takeCodePoints(it, FieldLimits.FAMILY_NAME_MAX) }
                },
                placeholder = { Text("2 到 20 个字") },
            )
            Text("创建后会生成邀请码，发给家人即可加入。", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            error?.let { Text(it, color = Danger, fontSize = 16.sp) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        runCatching { repo.createFamily(name) }.onSuccess { created = it }.onFailure { error = userFacingMessage(it) }
                    }
                },
                enabled = repo.online.collectAsState().value,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("family_save"),
                shape = button,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
            ) { Text("创建", fontWeight = FontWeight.Bold) }
        } else {
            Text("邀请码", fontSize = 16.sp, color = TextSecondary)
            Text(created?.inviteCode.orEmpty(), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("7 天内有效，不限次数。所有成员都能看到并分享，只有管理员能重置。", color = TextSecondary, fontSize = 16.sp)
            OutlinedButton(onClick = {
                copy(context, created?.inviteCode.orEmpty())
                toast(context, "邀请码已复制")
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("复制邀请码") }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).testTag("family_enter"), shape = button, colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
                Text("进入清单")
            }
        }
    }
}

@Composable
fun FamilyJoinScreen(repo: PantryRepository, onBack: () -> Unit, onDone: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        TopBar("用邀请码加入", onBack)
        Text("邀请码", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        OutlinedTextField(
            code,
            { code = it.uppercase().filter { ch -> ch in com.damy.pantry.data.INVITE_ALPHABET }.take(6) },
            modifier = Modifier.fillMaxWidth().testTag("join_code"),
            placeholder = { Text("6 位邀请码") },
        )
        Text("向家庭管理员要 6 位邀请码。不含 0、O、1、I。", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        error?.let { Text(it, color = Danger, fontSize = 16.sp) }
        Button(
            onClick = {
                scope.launch {
                    runCatching { repo.joinFamily(code) }.onSuccess { onDone() }.onFailure { error = userFacingMessage(it) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("join_submit"),
            shape = button,
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
        ) { Text("加入") }
    }
}

@Composable
fun HomeScreen(
    repo: PantryRepository,
    listVm: ListViewModel,
    historyVm: HistoryViewModel,
    today: LocalDate,
    onAddManual: () -> Unit,
    onEdit: (String) -> Unit,
    onStub: (String) -> Unit,
    onCapture: (String) -> Unit,
    onMembers: () -> Unit,
    onLogout: () -> Unit,
    onRemoved: () -> Unit,
    onNickname: () -> Unit = {},
) {
    var tab by remember { mutableStateOf("list") }
    var sheet by remember { mutableStateOf(false) }
    val online by repo.online.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        repo.events.collect { event ->
            when (event) {
                AppEvent.Revoked -> onLogout()
                AppEvent.Removed -> {
                    toast(context, "你已被移出家庭")
                    onRemoved()
                }
                is AppEvent.Toast -> toast(context, event.text)
            }
        }
    }
    val removed by repo.family.collectAsState()
    LaunchedEffect(removed) {
        if (repo.session.token != null && removed == null && tab != "boot") {
            // stay; removal navigation is handled by the event
        }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                "list" -> ListPage(repo, listVm, today, online, onEdit) { if (online) sheet = true else listVm.hint.value = "恢复网络后再修改" }
                "remind" -> StubBody("提醒", "提醒清单将在后续版本接入。分组仍是已过期、7 天内（含今天）、8–30 天和更远。")
                "history" -> HistoryPage(repo, historyVm, online)
                else -> MinePage(repo, online, onMembers, onStub, onNickname) {
                    scope.launch {
                        repo.logout()
                        onLogout()
                    }
                }
            }
        }
        val undo by listVm.undo.collectAsState()
        undo?.let { bar ->
            Row(
                Modifier.fillMaxWidth().background(Text).padding(horizontal = 16.dp, vertical = 8.dp).testTag("undo_bar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(keepPhrases(bar.text()), color = Surface, modifier = Modifier.weight(1f), fontSize = 16.sp, maxLines = 2)
                Text(undoCountdown(bar.seconds), color = Surface, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = { listVm.undo() }, modifier = Modifier.heightIn(min = 48.dp).testTag("undo_button")) { Text("撤销", color = Surface, fontWeight = FontWeight.Bold) }
            }
        }
        if (listVm.selecting.collectAsState().value && tab == "list") {
            BatchBar(listVm)
        } else if (historyVm.selecting.collectAsState().value && tab == "history") {
            HistoryBar(historyVm)
        } else {
            NavigationBar(containerColor = Surface) {
                Tab("list", "清单", Icons.Filled.Home, tab) { tab = "list" }
                Tab("remind", "提醒", Icons.Filled.Info, tab) { tab = "remind" }
                Tab("history", "历史", Icons.Filled.DateRange, tab) { tab = "history" }
                Tab("mine", "我的", Icons.Filled.Person, tab) { tab = "mine" }
            }
        }
    }
    if (sheet) {
        InboundSheet(online, onDismiss = { sheet = false }, onCapture = {
            sheet = false
            onCapture(it)
        }, onManual = {
            sheet = false
            onAddManual()
        })
    }
    val toastText by listVm.toast.collectAsState()
    LaunchedEffect(toastText) { toastText?.let { toast(context, it); listVm.toast.value = null } }
    val historyToast by historyVm.toast.collectAsState()
    LaunchedEffect(historyToast) { historyToast?.let { toast(context, it); historyVm.toast.value = null } }
}

@Composable
private fun ListPage(repo: PantryRepository, vm: ListViewModel, today: LocalDate, online: Boolean, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    val scope = rememberCoroutineScope()
    val items by repo.items.collectAsState()
    val query by vm.query.collectAsState()
    val selecting by vm.selecting.collectAsState()
    val selected by vm.selected.collectAsState()
    val hint by vm.hint.collectAsState()
    val family by repo.family.collectAsState()
    val notice by repo.notice.collectAsState()
    val groups = groupItems(today, items.filter { it.status == "in_stock" }, query)
    val stock = items.filter { it.status == "in_stock" }
    val thumbs by repo.thumbs.collectAsState()
    LaunchedEffect(stock.map { it.photoId }) {
        stock.mapNotNull { it.photoId }.distinct().forEach { repo.ensureThumb(it) }
    }
    val expired = stock.count { ReminderRules.daysRemaining(today, LocalDate.parse(it.expiryDate)) < 0 }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("清单", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(family?.name.orEmpty(), color = TextSecondary, modifier = Modifier.padding(end = 8.dp))
                TextButton(onClick = { vm.toggleManage() }, modifier = Modifier.heightIn(min = 48.dp).testTag("list_manage").alpha(if (online) 1f else 0.4f)) {
                    Text(if (selecting) "取消" else "管理")
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (!online) OfflineBanner(notice)
                com.damy.pantry.data.PendingPhoto.itemIds().forEach { retryId ->
                    val bytes = com.damy.pantry.data.PendingPhoto.bytesFor(retryId)
                    if (bytes != null) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("照片未上传，可在编辑页重试", modifier = Modifier.weight(1f), color = Danger, fontSize = 16.sp)
                            TextButton(onClick = {
                                scope.launch {
                                    val prepared = runCatching { com.damy.pantry.capture.compressForUpload(bytes) }.getOrElse {
                                        repo.notice.value = "照片未上传，可在编辑页重试"
                                        return@launch
                                    }
                                    runCatching { repo.uploadPhoto(retryId, prepared) }
                                        .onSuccess { com.damy.pantry.data.PendingPhoto.remove(retryId); vm.toast.value = "照片已上传" }
                                        .onFailure { repo.notice.value = "照片未上传，可在编辑页重试" }
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("retry_photo")) { Text("重试") }
                        }
                    }
                }
                NotificationOffBanner()
                hint?.let { Text(it, color = Danger, fontSize = 16.sp) }
                OutlinedTextField(query, { vm.query.value = it }, modifier = Modifier.fillMaxWidth().testTag("list_search"), placeholder = { Text("搜索物品名称") }, singleLine = true)
                Text("在库 ${stock.size} 件 · 已过期 $expired 件", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp))
                if (stock.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有物品", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("扫码、拍照或手填，把家里的食品、药品和调料登记进来", color = TextSecondary, fontSize = 16.sp)
                        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp).testTag("empty_add"), shape = button, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("入库") }
                    }
                }
                groups.forEach { group ->
                    Text("${group.title} ${group.items.size}件", fontWeight = FontWeight.Bold, color = bucketColor(group.bucket), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    group.items.forEach { item ->
                        ItemCard(item, today, selecting, item.id in selected, thumbs[item.photoId], { if (selecting) vm.toggle(item.id) else onEdit(item.id) }, { vm.toggle(item.id) })
                    }
                }
                Spacer(Modifier.height(88.dp))
            }
        }
        if (!selecting && stock.isNotEmpty()) {
            Button(
                onClick = onAdd,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).heightIn(min = 48.dp).testTag("list_add").alpha(if (online) 1f else 0.4f),
                shape = button,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("入库", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ItemCard(item: CachedItem, today: LocalDate, selecting: Boolean, checked: Boolean, thumb: ByteArray?, onClick: () -> Unit, onCheck: () -> Unit) {
    val expiry = LocalDate.parse(item.expiryDate)
    val days = ReminderRules.daysRemaining(today, expiry)
    val bucket = com.damy.pantry.data.listBucket(today, expiry)
    val label = daysLabel(days)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(card).background(Surface).clickable(onClick = onClick).heightIn(min = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(88.dp).background(bucketColor(bucket)))
        run {
            val bitmap = remember(thumb) { thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            Box(
                Modifier.padding(start = 12.dp).size(56.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).testTag("thumb_${item.name}"),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(item.name.take(1).ifBlank { "物" }, color = TextTertiary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() }, modifier = Modifier.testTag("check_${item.name}").size(48.dp))
        }
        Column(Modifier.weight(1f).padding(12.dp)) {
            Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${categoryLabel(item.category)} · ${item.location ?: "未填位置"} · ×${item.quantity}", fontSize = 16.sp, color = TextSecondary)
            Text("$expiry", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(keepPhrases(label), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(bucketSoft(bucket)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(bucketIcon(bucket), contentDescription = label, tint = bucketBadgeText(bucket), modifier = Modifier.size(16.dp))
            Text(label, color = bucketBadgeText(bucket), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ItemFormScreen(vm: ItemFormViewModel, repo: PantryRepository, id: String?, onBack: () -> Unit, onSaved: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by repo.items.collectAsState()
    LaunchedEffect(id, items) {
        val pending = com.damy.pantry.capture.PendingCapture.prefill
        if (pending != null && id == null) {
            com.damy.pantry.capture.PendingCapture.prefill = null
            vm.prefill(pending.name, pending.expiry, pending.production, pending.category, pending.shelfValue, pending.shelfUnit, pending.manualMode)
        }
        if (id != null && vm.editing == null) vm.load(items.firstOrNull { it.id == id })
    }
    Column(Modifier.fillMaxSize()) {
        val retryBytes = if (id == null) null else com.damy.pantry.data.PendingPhoto.bytesFor(id)
        if (id != null && retryBytes != null) {
            Button(onClick = {
                scope.launch {
                    val prepared = runCatching { com.damy.pantry.capture.compressForUpload(retryBytes) }.getOrElse {
                        toast(context, "照片未上传，可在编辑页重试")
                        return@launch
                    }
                    runCatching { repo.uploadPhoto(id, prepared) }
                        .onSuccess { com.damy.pantry.data.PendingPhoto.remove(id); toast(context, "照片已上传") }
                        .onFailure { toast(context, "照片未上传，可在编辑页重试") }
                }
            }, modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp).testTag("retry_photo_edit")) { Text("重新上传照片") }
        }
        Box(Modifier.weight(1f)) {
            FormBody(vm, id != null, onBack) { message ->
                if (message != null) toast(context, message)
                else if (id == null) toast(context, "已入库")
                onSaved(message)
            }
        }
    }
}

@Composable
private fun FormBody(vm: ItemFormViewModel, editing: Boolean, onBack: () -> Unit, onSaved: (String?) -> Unit) {
    val name by vm.name.collectAsState()
    val category by vm.category.collectAsState()
    val quantity by vm.quantity.collectAsState()
    val location by vm.location.collectAsState()
    val custom by vm.customLocation.collectAsState()
    val note by vm.note.collectAsState()
    val useDate by vm.useDate.collectAsState()
    val expiry by vm.expiryText.collectAsState()
    val production by vm.productionText.collectAsState()
    val preview by vm.preview.collectAsState()
    val error by vm.error.collectAsState()
    val selectedTiers by vm.tiers.collectAsState()
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(if (editing) "编辑物品" else "填写物品信息", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("名称", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                name,
                { vm.name.value = FieldLimits.takeCodePoints(it, FieldLimits.ITEM_NAME_MAX); vm.refreshPreview() },
                modifier = Modifier.fillMaxWidth().testTag("item_name").onFocusChanged { state ->
                    if (state.isFocused) DebugInput.bind { vm.name.value = FieldLimits.takeCodePoints(it, FieldLimits.ITEM_NAME_MAX); vm.refreshPreview() }
                },
                placeholder = { Text("请输入物品名称") },
            )
            Text("分类", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("food" to "食品", "medicine" to "药品", "condiment" to "调料", "other" to "其他").forEach { (key, label) ->
                    FilterChip(selected = category == key, onClick = { vm.category.value = key }, label = { Text(label) })
                }
            }
            Text("数量", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (quantity > 1) vm.quantity.value = quantity - 1 }) { Text("−") }
                Text("$quantity", modifier = Modifier.padding(horizontal = 12.dp), fontSize = 18.sp)
                OutlinedButton(onClick = { vm.quantity.value = quantity + 1 }) { Text("+") }
            }
            Text("数量只作备注，不随状态变化，也不设单位。", color = TextSecondary, fontSize = 16.sp)
            Text("存放位置", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            var addingLocation by remember { mutableStateOf(custom.isNotBlank()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                locations.forEach { loc ->
                    FilterChip(selected = location == loc && custom.isBlank(), onClick = { vm.location.value = loc; vm.customLocation.value = ""; addingLocation = false }, label = { Text(loc) })
                }
                FilterChip(selected = addingLocation, onClick = { addingLocation = true; vm.location.value = null }, label = { Text("＋ 新增位置") }, modifier = Modifier.testTag("add_location"))
            }
            if (addingLocation) {
                OutlinedTextField(custom, { vm.customLocation.value = FieldLimits.takeCodePoints(it, FieldLimits.LOCATION_MAX) }, modifier = Modifier.fillMaxWidth().testTag("custom_location"), placeholder = { Text("新增位置，最多 12 个字") })
            }
            Text("保质期或到期日", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to ShelfUnit.DAY, 1 to ShelfUnit.MONTH, 6 to ShelfUnit.MONTH, 1 to ShelfUnit.YEAR, 2 to ShelfUnit.YEAR).forEach { (value, unit) ->
                    val label = when (unit) {
                        ShelfUnit.DAY -> "$value 天"
                        ShelfUnit.MONTH -> "$value 个月"
                        ShelfUnit.YEAR -> "$value 年"
                    }
                    FilterChip(selected = !useDate && vm.shelfValue.collectAsState().value == value && vm.shelfUnit.collectAsState().value == unit, onClick = { vm.chooseShelf(value, unit) }, label = { Text(label) }, modifier = Modifier.testTag("shelf_$label"))
                }
            }
            TextButton(onClick = { vm.useDate.value = !useDate; vm.refreshPreview() }, modifier = Modifier.testTag("toggle_date")) {
                Text(if (useDate) "改为按保质期计算" else "改为直接选择到期日")
            }
            if (useDate || expiry.isNotEmpty()) {
                OutlinedTextField(
                    expiry,
                    { vm.useDate.value = true; vm.expiryText.value = it; vm.refreshPreview() },
                    modifier = Modifier.fillMaxWidth().testTag("item_expiry").onFocusChanged { state ->
                        if (state.isFocused) DebugInput.bind { vm.useDate.value = true; vm.expiryText.value = it; vm.refreshPreview() }
                    },
                    label = { Text("到期日") },
                    placeholder = { Text("YYYY-MM-DD") },
                )
            }
            OutlinedTextField(production, { vm.productionText.value = it; vm.refreshPreview() }, modifier = Modifier.fillMaxWidth().testTag("item_production"), label = { Text("生产日期（选填）") }, placeholder = { Text("YYYY-MM-DD") })
            if (preview.isNotEmpty()) Text(preview, color = TextSecondary, fontSize = 16.sp)
            Text("提醒档位，默认 30 天和 5 天，可全部取消。", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            tiers.forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp).clickable {
                    vm.tiers.value = if (key in selectedTiers) selectedTiers - key else selectedTiers + key
                }) {
                    Checkbox(checked = key in selectedTiers, onCheckedChange = {
                        vm.tiers.value = if (it) selectedTiers + key else selectedTiers - key
                    })
                    Text(label, fontSize = 16.sp)
                }
            }
            Text("备注（选填）", fontWeight = FontWeight.Bold)
            OutlinedTextField(note, { vm.note.value = FieldLimits.takeCodePoints(it, FieldLimits.NOTE_MAX) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("例如：打开后放回冷藏") })
            error?.let { Text(it, color = Danger, fontSize = 16.sp) }
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = { vm.save(onSaved) },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp).testTag("item_save"),
            shape = button,
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
        ) { Text(if (editing) "保存" else "保存并入库", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

@Composable
private fun HistoryPage(repo: PantryRepository, vm: HistoryViewModel, online: Boolean) {
    val items by repo.items.collectAsState()
    val filter by vm.filter.collectAsState()
    val selecting by vm.selecting.collectAsState()
    val selected by vm.selected.collectAsState()
    val hint by vm.hint.collectAsState()
    val rows = items.filter { it.status == "used_up" || it.status == "discarded" }.let { all ->
        when (filter) {
            "used_up" -> all.filter { it.status == "used_up" }
            "discarded" -> all.filter { it.status == "discarded" }
            else -> all
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (!online) vm.hint.value = "恢复网络后再修改" else vm.selecting.value = !selecting
            }, modifier = Modifier.testTag("history_manage")) { Text(if (selecting) "取消" else "管理") }
        }
        if (!online) OfflineBanner()
        hint?.let { Text(it, color = Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "used_up" to "已用完", "discarded" to "已处理").forEach { (key, label) ->
                FilterChip(selected = filter == key, onClick = { vm.filter.value = key }, label = { Text(label) })
            }
        }
        if (rows.isEmpty()) Text("还没有已用完或已处理的物品", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(card).background(Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        Checkbox(item.id in selected, { vm.selected.value = if (item.id in selected) selected - item.id else selected + item.id }, modifier = Modifier.testTag("hist_${item.name}"))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${if (item.status == "used_up") "已用完" else "已处理"} · 原到期 ${item.expiryDate}", color = TextSecondary, fontSize = 16.sp)
                    }
                    OutlinedButton(onClick = { vm.restore(listOf(item.id)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("restore_${item.name}").alpha(if (online) 1f else 0.4f)) { Text("恢复") }
                }
            }
        }
    }
}

@Composable
private fun MinePage(repo: PantryRepository, online: Boolean, onMembers: () -> Unit, onStub: (String) -> Unit, onNickname: () -> Unit, onLogout: () -> Unit) {
    val family by repo.family.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("我的", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(repo.session.phoneMask.orEmpty().ifEmpty { "已登录" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(repo.session.displayName ?: "用户", color = TextSecondary)
        com.damy.pantry.capture.MateDiagnostics.Entry()
        Choice(repo.session.displayName?.takeIf { it.isNotBlank() && it != "用户" } ?: "设置昵称", "家庭成员看到的名字", onNickname, "mine_nickname")
        Choice(family?.name ?: "家庭", "查看成员和邀请码", onMembers, "mine_family")
        Choice("每天 09:00", "提醒设置将在后续版本接入", { onStub("remind-time") }, "mine_remind")
        val context = LocalContext.current
        TextButton(onClick = {
            if (!online) toast(context, "当前无网络。恢复网络后再退出，才能注销服务器上的会话。") else onLogout()
        }, modifier = Modifier.testTag("logout").alpha(if (online) 1f else 0.4f)) { Text("退出登录", color = Danger) }
    }
}

@Composable
fun MembersScreen(repo: PantryRepository, onBack: () -> Unit, onLeft: () -> Unit) {
    val family by repo.family.collectAsState()
    val online by repo.online.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var memberNotice by remember { mutableStateOf<String?>(null) }
    var successor by remember { mutableStateOf<Member?>(null) }
    var target by remember { mutableStateOf<Member?>(null) }
    var rename by remember { mutableStateOf("") }
    val current = family
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        TopBar("家庭成员", onBack)
        if (!online) OfflineBanner()
        memberNotice?.let { Text(it, color = Danger, fontSize = 16.sp, modifier = Modifier.testTag("member_notice")) }
        if (current == null) {
            Text("还没有家庭")
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (current.role == "admin") {
                TextButton(onClick = { rename = current.name; dialog = "rename" }, enabled = online, modifier = Modifier.heightIn(min = 48.dp).testTag("rename_family")) { Text("编辑") }
            }
        }
        Column(Modifier.fillMaxWidth().clip(card).background(Surface).padding(16.dp)) {
            Text("邀请码", color = TextSecondary)
            Text(current.inviteCode.orEmpty(), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.testTag("invite_code"))
            Text("有效期至 ${current.inviteExpiresAt?.take(10).orEmpty()}，所有成员可复制和分享。", color = TextSecondary, fontSize = 16.sp)
            Row {
                OutlinedButton(onClick = { copy(context, current.inviteCode.orEmpty()); toast(context, "邀请码已复制") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "邀请你加入${current.name}，邀请码 ${current.inviteCode}")
                    context.startActivity(Intent.createChooser(send, "分享邀请码"))
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("分享") }
                if (current.role == "admin") {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { dialog = "reset" }, enabled = online, modifier = Modifier.heightIn(min = 48.dp).testTag("invite_reset")) { Text("重置") }
                }
            }
        }
        current.members.forEach { member ->
            val self = member.userId == repo.session.userId
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(card).background(Surface).padding(12.dp)) {
                Text(member.visibleName(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${if (member.role == "admin") "管理员" else "成员"} · ${member.joinedLabel()}", color = TextSecondary)
                if (current.role == "admin" && !self) {
                    Row {
                        TextButton(onClick = { target = member; dialog = "admin" }, enabled = online, modifier = Modifier.heightIn(min = 48.dp).testTag("make_admin_${member.displayName}")) { Text("设为管理员") }
                        TextButton(onClick = { target = member; dialog = "remove" }, enabled = online, modifier = Modifier.heightIn(min = 48.dp).testTag("remove_member")) { Text("移除", color = Danger) }
                    }
                }
            }
        }
        Button(
            onClick = {
                val others = current.members.filter { it.userId != repo.session.userId }
                dialog = when {
                    current.role != "admin" -> "leave-member"
                    others.isEmpty() -> "leave-last"
                    else -> {
                        successor = others.minByOrNull { it.joinedAt }
                        "leave-transfer"
                    }
                }
            },
            enabled = online,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("leave_family"),
            colors = ButtonDefaults.buttonColors(containerColor = ExpiredSoft, contentColor = Danger),
            shape = button,
        ) { Text("退出家庭") }
    }
    when (dialog) {
        "rename" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("家庭名称") },
            text = { OutlinedTextField(rename, { rename = FieldLimits.takeCodePoints(it, FieldLimits.FAMILY_NAME_MAX) }, modifier = Modifier.testTag("rename_field")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.renameFamily(rename) }
                            .onSuccess { dialog = null }
                            .onFailure { memberNotice = userFacingMessage(it); dialog = null }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "reset" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("重置邀请码？") },
            text = { Text("重置后旧邀请码立即失效。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.resetInvite() }
                            .onSuccess { toast(context, "邀请码已重置，有效期重新计算为 7 天"); dialog = null }
                            .onFailure { memberNotice = userFacingMessage(it); dialog = null }
                    }
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "remove" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("移除成员") },
            text = { Text(keepPhrases("确定将${target?.visibleName()}移出「${current?.name}」？移出后对方将立即看不到该家庭的物品。")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { target?.let { repo.removeMember(it.userId) } }
                            .onSuccess { dialog = null }
                            .onFailure { memberNotice = userFacingMessage(it); dialog = null }
                    }
                }, modifier = Modifier.testTag("confirm_remove")) { Text("移除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "admin" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("设为管理员") },
            text = { Text(keepPhrases("确定将${target?.visibleName()}设为管理员？转让后你将变为普通成员。")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { target?.let { repo.transferAdmin(it.userId) } }
                            .onSuccess { dialog = null }
                            .onFailure { memberNotice = userFacingMessage(it); dialog = null }
                    }
                }, modifier = Modifier.testTag("confirm_admin")) { Text("确认转让") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "leave-member" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("退出家庭？") },
            text = { Text(keepPhrases("退出后将不能再看到该家庭的物品。邀请码有效期内可再加入。")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.leaveFamily() }
                            .onSuccess { onLeft() }
                            .onFailure { memberNotice = userFacingMessage(it); dialog = null }
                    }
                }) { Text("退出", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "leave-last" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("退出家庭？") },
            text = { Text(keepPhrases("你是最后一名成员。家庭将保留 30 天后删除。保留期内不可再加入，邀请码将失效。")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.leaveFamily() }
                            .onSuccess { onLeft() }
                            .onFailure { memberNotice = userFacingMessage(it); dialog = null }
                    }
                }, modifier = Modifier.testTag("confirm_leave")) { Text("退出", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "leave-transfer" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("须先转让管理员") },
            text = {
                Column {
                    Text(keepPhrases("还有其他成员时不能直接退出。请选择接任的管理员，默认已选最早加入的人。"))
                    current?.members?.filter { it.userId != repo.session.userId }?.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { successor = member }) {
                            RadioButton(selected = successor?.userId == member.userId, onClick = { successor = member })
                            Text("${member.visibleName()} · ${member.joinedLabel()}")
                        }
                    }
                    Text(keepPhrases("退出后将不能再看到该家庭的物品。邀请码有效期内可再加入。"))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = successor ?: return@TextButton
                    scope.launch {
                        val transferred = runCatching { repo.transferAdmin(next.userId) }
                        if (transferred.isFailure) {
                            memberNotice = userFacingMessage(transferred.exceptionOrNull()!!)
                            dialog = null
                            return@launch
                        }
                        val left = runCatching { repo.leaveFamily() }
                        if (left.isSuccess) onLeft()
                        else {
                            memberNotice = "管理员已转让，但退出失败。${userFacingMessage(left.exceptionOrNull()!!)}"
                            dialog = null
                        }
                    }
                }, modifier = Modifier.testTag("confirm_transfer_leave")) { Text("转让并退出", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
    }
}

@Composable
fun StubScreen(name: String, onBack: () -> Unit) {
    val (title, body) = when (name) {
        "scan" -> "扫条形码" to "扫码将在下一版接入。没有相机也可以手动填写。"
        "photo" -> "拍包装照片" to "拍照识别将在下一版接入。没有相机也可以手动填写。"
        "remind-time" -> "提醒时间" to "每天提醒将在后续版本接入。默认 09:00。"
        else -> "稍后接入" to "这一页将在后续版本接入。"
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        TopBar(title, onBack)
        StubBody(title, body)
    }
}

@Composable
private fun StubBody(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(body, fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun BatchBar(vm: ListViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val selected by vm.selected.collectAsState()
    Row(Modifier.fillMaxWidth().background(Surface).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("已用完", "batch_used", selected.isNotEmpty()) { vm.mark("used_up") }
        Action("已处理", "batch_discard", selected.isNotEmpty()) { vm.mark("discarded") }
        Action("删除", "batch_delete", selected.isNotEmpty(), danger = true) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确定删除？") },
            text = { Text(keepPhrases("删除后无法恢复，也不会进入历史。确定删除这 ${selected.size} 件物品吗？需要可恢复请改用「已用完 / 已处理」。")) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.confirmDelete() }, modifier = Modifier.testTag("confirm_delete")) { Text("删除", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun HistoryBar(vm: HistoryViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val selected by vm.selected.collectAsState()
    Row(Modifier.fillMaxWidth().background(Surface).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("恢复", "history_restore", selected.isNotEmpty()) { vm.restore(selected.toList()) }
        Action("永久删除", "history_delete", selected.isNotEmpty(), danger = true) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确定删除？") },
            text = { Text(keepPhrases("删除后永久无法恢复，不会留在历史。确定删除这 ${selected.size} 件吗？")) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.delete(selected.toList()) }) { Text("删除", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboundSheet(online: Boolean, onDismiss: () -> Unit, onCapture: (String) -> Unit, onManual: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("入库", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SheetRow("扫条形码", "查到名称就自动带出", online, "sheet_scan") { onCapture("scan") }
            SheetRow("拍包装照片", "识别生产日期、保质期或到期日", online, "sheet_photo") { onCapture("photo") }
            SheetRow("手动填写", "手选保质期或直接选到期日", true, "sheet_manual") { onManual() }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RowScope.Action(label: String, tag: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag),
        shape = button,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Danger else Brand, contentColor = Surface),
    ) { Text(label, maxLines = 1, softWrap = false) }
}

@Composable
private fun RowScope.Tab(id: String, label: String, icon: ImageVector, current: String, onClick: () -> Unit) {
    NavigationBarItem(
        selected = current == id,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
        modifier = Modifier.testTag("tab_$id"),
    )
}

@Composable
private fun Choice(title: String, subtitle: String, onClick: () -> Unit, tag: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(card).background(Surface).clickable(onClick = onClick).padding(16.dp).testTag(tag).heightIn(min = 48.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(subtitle, color = TextSecondary, fontSize = 16.sp)
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp).testTag(tag).heightIn(min = 56.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(if (enabled) subtitle else "当前无网络，暂不可用", color = TextSecondary, fontSize = 16.sp)
    }
}

@Composable
private fun OfflineBanner(message: String? = null) {
    Text(
        message ?: "当前无网络，仅可查看，暂不能修改。",
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(MonthSoft).padding(12.dp).testTag("offline_banner"),
        color = Month,
        fontSize = 16.sp,
    )
}

@Composable
private fun NotificationOffBanner() {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    val blocked = if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        manager?.areNotificationsEnabled() == false
    }
    if (!blocked) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(BrandSoft).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(keepPhrases("未开启通知，可能错过到期提醒。"), modifier = Modifier.weight(1f), color = Text, fontSize = 16.sp, maxLines = 2)
        TextButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                context.startActivity(intent)
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("open_notification_settings"),
        ) { Text("去设置") }
    }
}

private fun Char.isCjk(): Boolean = code in 0x4E00..0x9FFF

/** Keep a Chinese word on one line, and still allow a break after punctuation or a space. */
internal fun keepPhrases(text: String): String = buildString {
    text.forEachIndexed { index, ch ->
        if (index > 0 && ch.isCjk() && text[index - 1].isCjk()) append('\u2060')
        append(ch)
    }
}

private fun Member.visibleName(): String = displayName.trim().ifEmpty { "用户" }

private fun Member.joinedLabel(): String = "加入 ${joinedAt.take(10)}"

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("返回") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    BackHandler(onBack = onBack)
}

private fun bucketIcon(bucket: ListBucket): ImageVector = when (bucket) {
    ListBucket.EXPIRED -> Icons.Filled.Warning
    ListBucket.SOON -> Icons.Filled.Info
    ListBucket.MONTH -> Icons.Filled.DateRange
    ListBucket.LATER -> Icons.Filled.CheckCircle
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("invite", text))
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
