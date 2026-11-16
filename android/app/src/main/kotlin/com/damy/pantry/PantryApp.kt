package com.damy.pantry

import android.app.Application
import com.damy.pantry.data.HttpPantryApi
import com.damy.pantry.data.PantryDatabase
import com.damy.pantry.data.PantryRepository
import com.damy.pantry.data.PrefSession
import com.damy.pantry.data.openSession
import com.damy.pantry.data.SqliteItemCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PantryApp : Application() {
    lateinit var repo: PantryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val opened = openSession(load = { PrefSession(this) })
        val session = opened.session
        val pins = BuildConfig.CA_PINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val api = HttpPantryApi(BuildConfig.SERVER_URL, { session.token }, pins)
        com.damy.pantry.data.PendingPhoto.bind(java.io.File(filesDir, "pending-photos"))
        com.damy.pantry.reminder.ReminderRuntime.ensureChannel(this)
        repo = PantryRepository(
            api = api,
            cache = SqliteItemCache(PantryDatabase.open(this)),
            session = session,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        opened.message?.let { repo.notice.value = it }
        com.damy.pantry.reminder.ReminderRuntime.beforeSummary = {
            if (session.token != null) repo.pull()
        }
        repo.onLocalChange = {
            val armed = repo.session.token != null && repo.family.value != null
            if (!armed) {
                com.damy.pantry.reminder.ReminderRuntime.cancel(this)
                com.damy.pantry.reminder.ReminderRuntime.clearSent(this)
            } else com.damy.pantry.reminder.ReminderRuntime.reschedule(this, repo.session.notifyTime)
        }
        if (session.token != null && session.loadFamily() != null) {
            com.damy.pantry.reminder.ReminderRuntime.reschedule(this, session.notifyTime)
        }
    }
}
