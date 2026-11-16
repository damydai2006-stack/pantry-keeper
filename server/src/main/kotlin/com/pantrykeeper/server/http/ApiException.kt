package com.pantrykeeper.server.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

@Serializable
data class ErrorBody(val error: String, val message: String)

val appJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}
