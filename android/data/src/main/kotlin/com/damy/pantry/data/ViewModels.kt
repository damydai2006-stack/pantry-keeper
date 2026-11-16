package com.damy.pantry.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrykeeper.core.date.ShelfUnit
import com.pantrykeeper.core.model.FieldLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import java.io.IOException

class LoginViewModel(private val repo: PantryRepository) : ViewModel() {
    val phone = MutableStateFlow("")
    val code = MutableStateFlow("")
    val agreed = MutableStateFlow(false)
    val secondsLeft = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private var ticker: Job? = null

    fun canRequestCode(): Boolean = phone.value.length == 11 && secondsLeft.value == 0 && !busy.value

    fun canLogin(): Boolean = phone.value.length == 11 && code.value.length == 6 && code.value.all(Char::isDigit) && agreed.value && !busy.value

    fun requestCode() {
        if (!agreed.value) {
            error.value = "请先勾选用户协议和隐私政策"
            return
        }
        if (!canRequestCode()) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                repo.requestCode(phone.value)
                secondsLeft.value = 60
                ticker?.cancel()
                ticker = viewModelScope.launch {
                    while (secondsLeft.value > 0) {
                        delay(1_000)
                        secondsLeft.value = secondsLeft.value - 1
                    }
                }
            } catch (e: IOException) {
                error.value = "当前无网络，请稍后重试"
            } catch (e: Exception) {
                error.value = e.message ?: "获取验证码失败"
            } finally {
                busy.value = false
            }
        }
    }

    fun login(onDone: (hasFamily: Boolean) -> Unit) {
        if (!agreed.value) {
            error.value = "请先勾选用户协议和隐私政策"
            return
        }
        if (!canLogin()) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                repo.login(phone.value, code.value.trim())
                onDone(repo.family.value != null)
            } catch (e: IOException) {
                error.value = "当前无网络，请稍后重试"
            } catch (e: Exception) {
                error.value = if (e.message.isNullOrBlank()) "验证码错误" else e.message
            } finally {
                busy.value = false
            }
        }
    }
}

class ListViewModel(private val repo: PantryRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val selecting = MutableStateFlow(false)
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val undo = MutableStateFlow<UndoUi?>(null)
    val hint = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)
    private var undoJob: Job? = null

    fun groups(today: LocalDate): List<ItemGroup> =
        groupItems(today, repo.items.value.filter { it.status == "in_stock" }, query.value)

    fun toggleManage() {
        if (!repo.online.value) {
            hint.value = "恢复网络后再修改"
            return
        }
        selecting.value = !selecting.value
        if (!selecting.value) selected.value = emptySet()
    }

    fun toggle(id: String) {
        val now = selected.value.toMutableSet()
        if (!now.add(id)) now.remove(id)
        selected.value = now
    }

    fun mark(action: String) {
        if (!repo.online.value) {
            hint.value = "恢复网络后再修改"
            return
        }
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = repo.batch(ids, action)
                selecting.value = false
                selected.value = emptySet()
                val token = result.undoToken
                if (token != null) startUndo(token, ids.size, action)
            } catch (e: Exception) {
                hint.value = userFacingMessage(e)
            }
        }
    }

    fun confirmDelete() = mark("delete")

    private fun startUndo(token: String, count: Int, action: String) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            var left = 5
            undo.value = UndoUi(token, count, action, left, 0)
            while (left > 0) {
                delay(1_000)
                left -= 1
                val current = undo.value ?: return@launch
                undo.value = current.copy(seconds = left)
            }
            undo.value = null
        }
    }

    fun undo() {
        val bar = undo.value ?: return
        viewModelScope.launch {
            try {
                val result = repo.undo(bar.token)
                undoJob?.cancel()
                undo.value = null
                if (result.skipped == 0) {
                    toast.value = "已撤销，物品已回到清单"
                } else {
                    toast.value = result.message ?: "${result.skipped} 件已被他人修改，未撤销"
                }
            } catch (e: Exception) {
                undo.value = null
                hint.value = userFacingMessage(e)
            }
        }
    }
}

data class UndoUi(val token: String, val count: Int, val action: String, val seconds: Int, val skipped: Int) {
    fun text(): String = undoBarText(action, count, seconds, skipped)
}

class ItemFormViewModel(private val repo: PantryRepository, private val today: () -> LocalDate) : ViewModel() {
    val name = MutableStateFlow("")
    val category = MutableStateFlow("food")
    val quantity = MutableStateFlow(1)
    val location = MutableStateFlow<String?>("冰箱")
    val customLocation = MutableStateFlow("")
    val note = MutableStateFlow("")
    val useDate = MutableStateFlow(false)
    val expiryText = MutableStateFlow("")
    val productionText = MutableStateFlow("")
    val shelfValue = MutableStateFlow<Int?>(null)
    val shelfUnit = MutableStateFlow(ShelfUnit.DAY)
    val tiers = MutableStateFlow(setOf("D30", "D5"))
    val error = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow("")
    var editing: CachedItem? = null

    fun prefill(name: String, expiry: String?, production: String?, category: String?) {
        this.name.value = name
        if (!category.isNullOrBlank()) this.category.value = category
        if (!expiry.isNullOrBlank()) {
            useDate.value = true
            expiryText.value = expiry
        }
        if (!production.isNullOrBlank()) productionText.value = production
        refreshPreview()
    }

    fun load(item: CachedItem?) {
        editing = item
        if (item == null) return
        name.value = item.name
        category.value = item.category
        quantity.value = item.quantity
        location.value = item.location
        note.value = item.note.orEmpty()
        useDate.value = true
        expiryText.value = item.expiryDate
        productionText.value = item.productionDate.orEmpty()
        shelfValue.value = item.shelfLifeValue
        shelfUnit.value = when (item.shelfLifeUnit) {
            "month" -> ShelfUnit.MONTH
            "year" -> ShelfUnit.YEAR
            else -> ShelfUnit.DAY
        }
        tiers.value = item.reminderTiers.toSet()
        refreshPreview()
    }

    fun chooseShelf(value: Int, unit: ShelfUnit) {
        useDate.value = false
        shelfValue.value = value
        shelfUnit.value = unit
        refreshPreview()
    }

    fun refreshPreview() {
        val production = productionText.value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val value = shelfValue.value
        preview.value = if (useDate.value) {
            val typed = expiryText.value.trim()
            if (typed.isEmpty()) "" else "到期日 $typed"
        } else if (value == null) {
            ""
        } else {
            val expiry = shelfExpiry(today(), production, value, shelfUnit.value)
            expiryText.value = expiry.toString()
            shelfOrigin(today(), production) + "按此计算，到期日为 $expiry"
        }
    }

    fun save(onDone: (String?) -> Unit) {
        if (!repo.online.value) {
            error.value = "恢复网络后再修改"
            return
        }
        viewModelScope.launch {
            try {
                refreshPreview()
                val loc = customLocation.value.trim().ifEmpty { location.value }
                val draft = ItemDraft(
                    name = name.value,
                    category = category.value,
                    quantity = quantity.value,
                    location = loc,
                    note = note.value,
                    expiryDate = expiryText.value.trim().ifEmpty { null },
                    productionDate = productionText.value.trim().ifEmpty { null },
                    shelfLifeValue = if (useDate.value) null else shelfValue.value,
                    shelfLifeUnit = if (useDate.value) null else unitWire(shelfUnit.value),
                    expiryBasis = if (useDate.value) "manual_date" else if (productionText.value.isBlank()) "manual_duration_from_today" else "manual_duration_from_prod",
                    reminderTiers = tiers.value.toList(),
                )
                val original = editing
                val message = if (original == null) {
                    repo.saveNew(draft)
                    null
                } else {
                    repo.saveEdit(original, draft)
                }
                onDone(message)
            } catch (e: Exception) {
                error.value = userFacingMessage(e)
            }
        }
    }

    fun nameError(): String? = if (name.value.isBlank()) null else FieldLimits.itemNameError(name.value)
}

private fun unitWire(unit: ShelfUnit): String = when (unit) {
    ShelfUnit.DAY -> "day"
    ShelfUnit.MONTH -> "month"
    ShelfUnit.YEAR -> "year"
}

class HistoryViewModel(private val repo: PantryRepository) : ViewModel() {
    val filter = MutableStateFlow("all")
    val selecting = MutableStateFlow(false)
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val hint = MutableStateFlow<String?>(null)
    val toast = MutableStateFlow<String?>(null)

    fun visible(): List<CachedItem> {
        val rows = repo.items.value.filter { it.status == "used_up" || it.status == "discarded" }
        return when (filter.value) {
            "used_up" -> rows.filter { it.status == "used_up" }
            "discarded" -> rows.filter { it.status == "discarded" }
            else -> rows
        }.sortedByDescending { it.rev }
    }

    fun restore(ids: List<String>) {
        if (!repo.online.value) {
            hint.value = "恢复网络后再修改"
            return
        }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.batch(ids, "restore")
                selecting.value = false
                selected.value = emptySet()
                toast.value = "已恢复到清单"
            } catch (e: Exception) {
                hint.value = userFacingMessage(e)
            }
        }
    }

    fun delete(ids: List<String>) {
        if (!repo.online.value) {
            hint.value = "恢复网络后再修改"
            return
        }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.batch(ids, "delete")
                selecting.value = false
                selected.value = emptySet()
            } catch (e: Exception) {
                hint.value = userFacingMessage(e)
            }
        }
    }
}
