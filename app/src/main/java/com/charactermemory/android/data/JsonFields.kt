package com.charactermemory.android.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

private val gson = Gson()

fun jsonObject(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
    fields.forEach { (key, value) -> add(key, gson.toJsonTree(value)) }
}

fun JsonObject.text(key: String, default: String = ""): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() } ?: default

fun JsonObject.obj(key: String): JsonObject =
    objOrNull(key) ?: JsonObject()

fun JsonObject.objOrNull(key: String): JsonObject? =
    get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

fun JsonObject.items(key: String): List<JsonObject> =
    get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull {
        it.takeIf(JsonElement::isJsonObject)?.asJsonObject
    } ?: emptyList()

fun JsonObject.flag(key: String, default: Boolean = false): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive }?.let {
        when (it.asString.lowercase()) { "true" -> true; "false" -> false; else -> default }
    } ?: default

fun JsonObject.number(key: String, default: Long = 0L): Long =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.toLong() }.getOrNull() } ?: default
