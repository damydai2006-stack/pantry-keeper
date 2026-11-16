package com.damy.pantry.ui

import android.database.sqlite.SQLiteDatabase
import com.damy.pantry.data.PantryDatabase
import com.damy.pantry.data.SqliteItemCache
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SchemaUpgradeTest {
    @Test
    fun upgradeClearsTheSyncCursor() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("pantry.db")
        val file = context.getDatabasePath("pantry.db")
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.version = 2
        raw.execSQL(
            """
            CREATE TABLE items (
              id TEXT PRIMARY KEY NOT NULL,
              name TEXT NOT NULL,
              category TEXT NOT NULL,
              quantity INTEGER NOT NULL,
              location TEXT,
              note TEXT,
              expiry_date TEXT NOT NULL,
              production_date TEXT,
              shelf_life_value INTEGER,
              shelf_life_unit TEXT,
              expiry_basis TEXT NOT NULL,
              reminder_tiers TEXT NOT NULL,
              status TEXT NOT NULL,
              rev INTEGER NOT NULL,
              photo_id TEXT
            )
            """.trimIndent(),
        )
        raw.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        raw.execSQL("INSERT INTO meta (key, value) VALUES ('cursor', '42')")
        raw.close()
        val opened = PantryDatabase.open(context)
        val cache = SqliteItemCache(opened)
        check(cache.cursor() == 0L) { "cursor stayed at ${cache.cursor()}" }
        opened.close()
        context.deleteDatabase("pantry.db")
    }
}
