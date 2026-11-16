package com.pantrykeeper.server.sync

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class FamilyHub(private val scope: CoroutineScope) {
    private val sessions = ConcurrentHashMap<Uuid, MutableSet<Held>>()

    data class Held(val userId: Uuid, val familyId: Uuid, val session: WebSocketSession)

    fun register(userId: Uuid, familyId: Uuid, session: WebSocketSession) {
        sessions.compute(familyId) { _, set ->
            (set ?: ConcurrentHashMap.newKeySet()).also { it.add(Held(userId, familyId, session)) }
        }
    }

    fun unregister(session: WebSocketSession) {
        sessions.values.forEach { it.removeIf { held -> held.session == session } }
    }

    fun changed(familyId: Uuid, rev: Long) {
        val text = """{"t":"changed","rev":$rev}"""
        sessions[familyId].orEmpty().forEach { held -> send(held.session, text) }
    }

    fun removed(userId: Uuid) {
        val text = """{"t":"removed_from_family"}"""
        sessions.values.forEach { set ->
            set.filter { it.userId == userId }.forEach { held ->
                scope.launch {
                    runCatching { held.session.send(Frame.Text(text)) }
                    runCatching { held.session.close() }
                }
                set.remove(held)
            }
        }
    }

    private fun send(session: WebSocketSession, text: String) {
        scope.launch { runCatching { session.send(Frame.Text(text)) } }
    }
}
