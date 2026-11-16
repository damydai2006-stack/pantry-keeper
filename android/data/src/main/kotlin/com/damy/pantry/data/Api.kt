package com.damy.pantry.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

val pantryJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}

interface PantryApi {
    suspend fun requestCode(phone: String)
    suspend fun login(phone: String, code: String): LoginResult
    suspend fun logout()
    suspend fun me(): Me
    suspend fun createFamily(name: String): Family
    suspend fun joinFamily(code: String): Family
    suspend fun leaveFamily()
    suspend fun currentFamily(): Family
    suspend fun renameFamily(name: String): Family
    suspend fun resetInvite(): Family
    suspend fun removeMember(userId: String)
    suspend fun transferAdmin(userId: String): Family
    suspend fun createItem(draft: ItemDraft, today: String): CachedItem
    suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject): PatchBody
    suspend fun batch(ids: List<String>, action: String): BatchOutcome
    suspend fun undo(token: String): UndoOutcome
    suspend fun sync(since: Long): SyncPage
    suspend fun barcode(code: String): BarcodeHit
    fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit
}

@Serializable
data class BarcodeHit(
    val found: Boolean = false,
    val name: String? = null,
    val defaultCategory: String? = null,
    val reason: String? = null,
)

@Serializable
data class PatchBody(val item: CachedItem, val message: String? = null)

class HttpPantryApi(
    baseUrl: String,
    private val token: () -> String?,
    pins: List<String> = emptyList(),
) : PantryApi {
    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .apply {
            val usable = pins.filter { it.isNotBlank() }
            if (usable.isNotEmpty() && base.startsWith("https://")) {
                val host = base.removePrefix("https://").substringBefore('/').substringBefore(':')
                certificatePinner(
                    CertificatePinner.Builder().apply {
                        usable.forEach { add(host, it) }
                    }.build(),
                )
            }
        }
        .build()

    override suspend fun requestCode(phone: String) {
        exchange("POST", "/api/v1/auth/code", pantryJson.encodeToString(CodeBody.serializer(), CodeBody(phone)), auth = false)
    }

    override suspend fun login(phone: String, code: String): LoginResult {
        val raw = exchange(
            "POST",
            "/api/v1/auth/login",
            pantryJson.encodeToString(LoginBody.serializer(), LoginBody(phone, code, "app")),
            auth = false,
        )
        val body = pantryJson.decodeFromString(LoginWire.serializer(), raw)
        val token = body.token ?: throw PantryCallException(500, "no_token", "登录没有返回令牌")
        return LoginResult(body.userId, body.displayName, token)
    }

    override suspend fun logout() {
        exchange("POST", "/api/v1/auth/logout", "{}")
    }

    override suspend fun me(): Me = decode(exchange("GET", "/api/v1/me"))

    override suspend fun createFamily(name: String): Family =
        decode(exchange("POST", "/api/v1/families", pantryJson.encodeToString(NameBody.serializer(), NameBody(name))))

    override suspend fun joinFamily(code: String): Family =
        decode(exchange("POST", "/api/v1/families/join", pantryJson.encodeToString(JoinBody.serializer(), JoinBody(code))))

    override suspend fun leaveFamily() {
        exchange("POST", "/api/v1/families/leave", "{}")
    }

    override suspend fun currentFamily(): Family = decode(exchange("GET", "/api/v1/families/current"))

    override suspend fun renameFamily(name: String): Family =
        decode(exchange("PATCH", "/api/v1/families/current", pantryJson.encodeToString(NameBody.serializer(), NameBody(name))))

    override suspend fun resetInvite(): Family = decode(exchange("POST", "/api/v1/families/current/invite/reset", "{}"))

    override suspend fun removeMember(userId: String) {
        exchange("DELETE", "/api/v1/families/current/members/$userId")
    }

    override suspend fun transferAdmin(userId: String): Family =
        decode(exchange("POST", "/api/v1/families/current/admin", pantryJson.encodeToString(UserBody.serializer(), UserBody(userId))))

    override suspend fun createItem(draft: ItemDraft, today: String): CachedItem =
        decode(exchange("POST", "/api/v1/items", pantryJson.encodeToString(CreateBody.serializer(), draft.toCreate(today))))

    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject): PatchBody =
        decode(exchange("PATCH", "/api/v1/items/$id", pantryJson.encodeToString(PatchRequest.serializer(), PatchRequest(baseRev, fields))))

    override suspend fun batch(ids: List<String>, action: String): BatchOutcome =
        decode(exchange("POST", "/api/v1/items/batch", pantryJson.encodeToString(BatchBody.serializer(), BatchBody(ids, action))))

    override suspend fun undo(token: String): UndoOutcome =
        decode(exchange("POST", "/api/v1/items/batch/undo", pantryJson.encodeToString(UndoBody.serializer(), UndoBody(token))))

    override suspend fun sync(since: Long): SyncPage = decode(exchange("GET", "/api/v1/sync?since=$since"))

    override suspend fun barcode(code: String): BarcodeHit = decode(exchange("GET", "/api/v1/barcodes/$code"))

    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit {
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        val request = Request.Builder()
            .url("$wsBase/api/v1/ws")
            .header("Authorization", "Bearer ${token().orEmpty()}")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed()
            }
        })
        return { socket.close(1000, null) }
    }

    private fun exchange(method: String, path: String, body: String? = null, auth: Boolean = true): String {
        val builder = Request.Builder().url(base + path)
        if (auth) token()?.let { builder.header("Authorization", "Bearer $it") }
        val requestBody = body?.toRequestBody(jsonType)
        builder.method(method, if (method == "GET" || method == "DELETE") null else requestBody ?: "{}".toRequestBody(jsonType))
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = runCatching { pantryJson.decodeFromString(ErrorWire.serializer(), text) }.getOrNull()
                    throw PantryCallException(response.code, err?.error ?: "http", err?.message ?: "请求失败")
                }
                return text
            }
        } catch (e: PantryCallException) {
            throw e
        } catch (e: IOException) {
            throw e
        }
    }

    private inline fun <reified T> decode(raw: String): T = pantryJson.decodeFromString(raw)
}

@Serializable
private data class CodeBody(val phone: String)

@Serializable
private data class LoginBody(val phone: String, val code: String, val clientKind: String)

@Serializable
private data class LoginWire(val userId: String, val displayName: String, val token: String? = null)

@Serializable
private data class NameBody(val name: String)

@Serializable
private data class JoinBody(val code: String)

@Serializable
private data class UserBody(val userId: String)

@Serializable
private data class CreateBody(
    val name: String,
    val category: String,
    val quantity: Int,
    val location: String? = null,
    val note: String? = null,
    val expiryDate: String? = null,
    val productionDate: String? = null,
    val shelfLifeValue: Int? = null,
    val shelfLifeUnit: String? = null,
    val expiryBasis: String? = null,
    val reminderTiers: List<String>,
    val clientToday: String,
)

@Serializable
private data class PatchRequest(val baseRev: Long, val fields: JsonObject)

@Serializable
private data class BatchBody(val ids: List<String>, val action: String)

@Serializable
private data class UndoBody(val undoToken: String)

@Serializable
private data class ErrorWire(val error: String, val message: String)

private fun ItemDraft.toCreate(today: String) = CreateBody(
    name = name.trim(),
    category = category,
    quantity = quantity,
    location = location?.trim()?.ifEmpty { null },
    note = note?.trim()?.ifEmpty { null },
    expiryDate = expiryDate,
    productionDate = productionDate?.trim()?.ifEmpty { null },
    shelfLifeValue = shelfLifeValue,
    shelfLifeUnit = shelfLifeUnit,
    expiryBasis = expiryBasis,
    reminderTiers = reminderTiers,
    clientToday = today,
)
