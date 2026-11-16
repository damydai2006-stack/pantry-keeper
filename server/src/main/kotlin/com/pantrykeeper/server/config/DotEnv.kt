package com.pantrykeeper.server.config

import java.io.File

object DotEnv {
    fun loadMerged(): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        val path = System.getenv("PANTRY_ENV_FILE")?.trim().orEmpty()
        if (path.isNotEmpty()) {
            val file = File(path)
            if (!file.isFile) error("PANTRY_ENV_FILE does not exist")
            merged.putAll(parse(file.readText()))
        }
        merged.putAll(System.getenv())
        return merged
    }

    fun parse(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }
}
