package com.banner.hintcontrol.gateway

import kotlinx.serialization.json.Json

object GatewayJson {
    /** Reading TMI responses (same options as the old app's unified client). */
    val read: Json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
        isLenient = true
    }

    /** Reading Nokia responses. */
    val readNokia: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * Writing request bodies. Matches Ktor's DefaultJson, which the old app used for every
     * POST: defaults and nulls are sent (e.g. Nokia's "action" field depends on this).
     */
    val write: Json = Json {
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        prettyPrint = false
        useArrayPolymorphism = false
    }

    val pretty: Json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }
}
