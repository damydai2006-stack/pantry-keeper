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
        repo = PantryRepository(
            api = api,
            cache = SqliteItemCache(PantryDatabase.open(this)),
            session = session,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        opened.message?.let { repo.notice.value = it }
    }
}
