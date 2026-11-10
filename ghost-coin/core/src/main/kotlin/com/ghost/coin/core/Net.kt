package com.ghost.coin.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Format d'échange entre l'application et le serveur. */
val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Req(
    val name: String = "", val recovery: String = "",
    val type: String = "", val i: Int = 0, val s: String = "", val seq: Long = -1,
    val to: String = "", val card: Int = 0,
)

@Serializable
data class Row(val id: String, val name: String, val avatar: Int, val village: Int, val stars: Int)

@Serializable
data class Reply(
    val ok: Boolean = true, val error: String = "",
    val state: GameState? = null, val events: List<Event> = emptyList(),
    val token: String = "", val recovery: String = "",
    val board: List<Row> = emptyList(), val friends: List<Row> = emptyList(),
)
