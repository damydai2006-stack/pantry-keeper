package com.pantrykeeper.server.sync

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class FamilyHub(
    private val scope: CoroutineScope,
    private val sessionOpen: (Uuid) -> Boolean,
) {
    private val sessions = ConcurrentHashMap<Uuid, MutableSet<Held>>()

    data class Held(
        val userId: Uuid,
        val familyId: Uuid,
        val sessionId: Uuid,
        val session: WebSocketSession,
    )

    fun register(userId: Uuid, familyId: Uuid, sessionId: Uuid, session: WebSocketSession) {
        sessions.compute(familyId) { _, set ->
            (set ?: ConcurrentHashMap.newKeySet()).also { it.add(Held(userId, familyId, sessionId, session)) }
        }
    }

    fun unregister(session: WebSocketSession) {
        sessions.values.forEach { it.removeIf { held -> held.session == session } }
    }

    fun changed(familyId: Uuid, rev: Long) {
        val text = """{"t":"changed","rev":$rev}"""
        sessions[familyId].orEmpty().toList().forEach { held ->
            if (sessionOpen(held.sessionId)) send(held.session, text) else revoke(held)
        }
    }

    fun removed(userId: Uuid) {
        val text = """{"t":"removed_from_family"}"""
        sessions.values.forEach { set ->
            set.filter { it.userId == userId }.toList().forEach { held ->
                set.remove(held)
                scope.launch {
                    runCatching { held.session.send(Frame.Text(text)) }
                    runCatching { held.session.close() }
                }
            }
        }
    }

    /** Logout, expiry, or a failed re-check. Other sessions of the same user stay up. */
    fun revokeSession(sessionId: Uuid) {
        sessions.values.forEach { set ->
            set.filter { it.sessionId == sessionId }.toList().forEach { held ->
                set.remove(held)
                revoke(held)
            }
        }
    }

    /** Drop sockets whose session row is gone or past `expires_at`. */
    fun watch(sessionId: Uuid): Job = scope.launch {
        while (isActive) {
            delay(1_000)
            if (!sessionOpen(sessionId)) {
                revokeSession(sessionId)
                break
            }
        }
    }

    private fun revoke(held: Held) {
        scope.launch {
            runCatching { held.session.send(Frame.Text("""{"t":"session_revoked"}""")) }
            runCatching { held.session.close() }
        }
    }

    private fun send(session: WebSocketSession, text: String) {
        scope.launch { runCatching { session.send(Frame.Text(text)) } }
    }
}
