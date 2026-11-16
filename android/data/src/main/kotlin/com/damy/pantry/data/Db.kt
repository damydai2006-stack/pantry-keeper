package com.damy.pantry.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

interface ItemCache {
    fun snapshot(): List<CachedItem>
    fun replace(items: List<CachedItem>)
    fun cursor(): Long
    fun setCursor(value: Long)
}

class MemoryCache : ItemCache {
    private val rows = linkedMapOf<String, CachedItem>()
    private var rev = 0L
    override fun snapshot(): List<CachedItem> = rows.values.toList()
    override fun replace(items: List<CachedItem>) {
        rows.clear()
        items.forEach { rows[it.id] = it }
    }

    override fun cursor(): Long = rev
    override fun setCursor(value: Long) {
        rev = value
    }
}

interface SessionStore {
    var token: String?
    var userId: String?
    var displayName: String?
    var phoneMask: String?
    var notifyTime: String?
    fun saveFamily(raw: String?)
    fun loadFamily(): String?
    fun clear()
}

class MemorySession : SessionStore {
    override var token: String? = null
    override var userId: String? = null
    override var displayName: String? = null
    override var phoneMask: String? = null
    override var notifyTime: String? = "09:00"
    private var family: String? = null
    override fun saveFamily(raw: String?) {
        family = raw
    }

    override fun loadFamily(): String? = family
    override fun clear() {
        token = null
        userId = null
        displayName = null
        phoneMask = null
        notifyTime = "09:00"
        family = null
    }
}

class PrefSession(context: Context, private val crypto: SessionCrypto = AndroidKeystoreSession.crypto()) : SessionStore {
    private val prefs = context.getSharedPreferences("pantry", Context.MODE_PRIVATE)
    override var token: String?
        get() = prefs.getString("token", null)?.let { stored ->
            runCatching { crypto.decrypt(stored) }.getOrElse {
                prefs.edit().remove("token").apply()
                null
            }
        }
        set(value) {
            prefs.edit().putString("token", value?.let { crypto.encrypt(it) }).apply()
        }
    override var userId: String?
        get() = prefs.getString("user", null)
        set(value) = prefs.edit().putString("user", value).apply()
    override var displayName: String?
        get() = prefs.getString("name", null)
        set(value) = prefs.edit().putString("name", value).apply()
    override var phoneMask: String?
        get() = prefs.getString("mask", null)
        set(value) = prefs.edit().putString("mask", value).apply()
    override var notifyTime: String?
        get() = prefs.getString("notify", null) ?: "09:00"
        set(value) = prefs.edit().putString("notify", value).apply()

    override fun saveFamily(raw: String?) {
        prefs.edit().putString("family", raw).apply()
    }

    override fun loadFamily(): String? = prefs.getString("family", null)

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Local cache. Room's processor cannot read Kotlin 2.4 metadata yet, so this
 * uses the platform SQLite with the same items/meta tables a Room database would.
 */
class PantryDatabase(context: Context) : SQLiteOpenHelper(context, "pantry.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
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
              photo_id TEXT,
              opened_date TEXT,
              after_open_days INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE items ADD COLUMN photo_id TEXT")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE items ADD COLUMN opened_date TEXT")
            db.execSQL("ALTER TABLE items ADD COLUMN after_open_days INTEGER")
        }
    }

    companion object {
        fun open(context: Context): PantryDatabase = PantryDatabase(context.applicationContext)
    }
}

class SqliteItemCache(private val db: PantryDatabase) : ItemCache {
    override fun snapshot(): List<CachedItem> = db.readableDatabase.rawQuery("SELECT * FROM items", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toItem())
        }
    }

    override fun replace(items: List<CachedItem>) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM items")
            items.forEach { item ->
                database.insert("items", null, ContentValues().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("category", item.category)
                    put("quantity", item.quantity)
                    put("location", item.location)
                    put("note", item.note)
                    put("expiry_date", item.expiryDate)
                    put("production_date", item.productionDate)
                    put("shelf_life_value", item.shelfLifeValue)
                    put("shelf_life_unit", item.shelfLifeUnit)
                    put("expiry_basis", item.expiryBasis)
                    put("opened_date", item.openedDate)
                    put("after_open_days", item.afterOpenDays)
                    put("reminder_tiers", item.reminderTiers.joinToString(","))
                    put("status", item.status)
                    put("rev", item.rev)
                    put("photo_id", item.photoId)
                })
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun cursor(): Long = db.readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf("cursor")).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
    }

    override fun setCursor(value: Long) {
        db.writableDatabase.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf("cursor", value.toString()))
    }
}

private fun android.database.Cursor.text(column: String): String? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return getString(index)
}

private fun android.database.Cursor.toItem(): CachedItem = CachedItem(
    id = text("id").orEmpty(),
    name = text("name").orEmpty(),
    category = text("category").orEmpty(),
    quantity = getInt(getColumnIndex("quantity")),
    location = text("location"),
    note = text("note"),
    expiryDate = text("expiry_date").orEmpty(),
    productionDate = text("production_date"),
    shelfLifeValue = text("shelf_life_value")?.toIntOrNull(),
    shelfLifeUnit = text("shelf_life_unit"),
    expiryBasis = text("expiry_basis") ?: "manual_date",
    openedDate = text("opened_date"),
    afterOpenDays = text("after_open_days")?.toIntOrNull(),
    reminderTiers = text("reminder_tiers").orEmpty().split(',').filter { it.isNotBlank() },
    status = text("status").orEmpty(),
    rev = text("rev")?.toLongOrNull() ?: 0L,
    photoId = text("photo_id"),
)

