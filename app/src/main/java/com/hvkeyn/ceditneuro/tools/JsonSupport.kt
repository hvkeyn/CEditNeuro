package com.hvkeyn.ceditneuro.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Argument accessors for tool calls; the model is free to omit anything optional. */
internal fun JsonObject.stringArg(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.intArg(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

internal fun JsonObject.boolArg(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

/** Builds the JSON Schema object that is sent to the model for a tool. */
internal fun objectSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (name, schema) -> put(name, schema) }
    }
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
    put("additionalProperties", false)
}

internal fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

@Suppress("unused")
internal fun arrayProp(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

/** Renders a list of strings as the `items` schema of a string array. */
internal fun stringArrayProp(description: String): JsonObject = arrayProp(description, stringProp("value"))

@Suppress("unused")
internal fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }
