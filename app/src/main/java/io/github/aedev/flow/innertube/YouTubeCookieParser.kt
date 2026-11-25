package io.github.aedev.flow.innertube

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Locale

internal enum class YouTubeCookieParseProblem {
    EMPTY_INPUT,
    INVALID_FORMAT,
    INVALID_COOKIE,
    UNRELATED_DOMAIN,
    EXPIRED_COOKIE,
    MISSING_REQUIRED_COOKIES,
}

internal class YouTubeCookieParseException(
    val problem: YouTubeCookieParseProblem,
) : Exception(problem.name)

internal object YouTubeCookieParser {
    private val requiredCookieNames =
        setOf(
            "SID",
            "HSID",
            "SSID",
            "APISID",
            "SAPISID",
            "__Secure-1PSID",
            "__Secure-3PSID",
        )
    private val cookieNamePattern = Regex("[!#$%&'*+-.^_`|~0-9A-Za-z]+")

    fun parse(
        input: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
    ): String {
        val text = input.trim()
        if (text.isEmpty()) throw YouTubeCookieParseException(YouTubeCookieParseProblem.EMPTY_INPUT)

        val cookies =
            when {
                text.startsWith("[") || text.startsWith("{") -> parseJson(text)
                looksLikeNetscape(text) -> parseNetscape(text)
                else -> parseHeader(text)
            }

        validate(cookies, nowEpochSeconds)
        return cookies
            .distinctBy(Cookie::name)
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun parseJson(text: String): List<Cookie> {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_FORMAT)
            }
        val entries =
            when (root) {
                is JsonArray -> root
                is JsonObject -> root["cookies"] as? JsonArray
                else -> null
            } ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_FORMAT)

        return entries.map { element ->
            val cookie =
                element as? JsonObject
                    ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
            val name = cookie.string("name")
            val value = cookie.string("value")
            val domain = cookie.string("domain")
                ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
            val sessionCookie = cookie.boolean("session") == true
            val expiration =
                if (sessionCookie) {
                    null
                } else {
                    cookie.expirationSeconds()
                }
            Cookie(
                name = name ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE),
                value = value ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE),
                domain = domain,
                expiresAt = expiration,
            )
        }
    }

    private fun parseNetscape(text: String): List<Cookie> =
        text.lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotBlank() && (!line.startsWith("#") || line.startsWith("#HttpOnly_")) }
            .map { line ->
                val valueLine = line.removePrefix("#HttpOnly_")
                val columns = valueLine.split('\t', limit = 7)
                if (columns.size != 7) throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_FORMAT)
                val name = columns[5]
                val value = columns[6]
                val expiry =
                    columns[4].toLongOrNull()
                        ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
                if (columns[1].uppercase(Locale.ROOT) !in setOf("TRUE", "FALSE") ||
                    !columns[2].startsWith("/") ||
                    columns[3].uppercase(Locale.ROOT) !in setOf("TRUE", "FALSE")
                ) {
                    throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
                }
                Cookie(
                    name = name,
                    value = value,
                    domain = columns[0],
                    expiresAt = expiry.takeIf { it != 0L },
                )
            }.toList()

    private fun parseHeader(text: String): List<Cookie> {
        val value = text.removePrefixIgnoreCase("Cookie:").trim()
        if (value.isEmpty()) throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_FORMAT)
        return value.split(';').map(String::trim).filter(String::isNotEmpty).map { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_FORMAT)
            Cookie(
                name = part.substring(0, separator).trim(),
                value = part.substring(separator + 1).trim(),
                domain = ".youtube.com",
                expiresAt = null,
            )
        }
    }

    private fun validate(
        cookies: List<Cookie>,
        nowEpochSeconds: Long,
    ) {
        if (cookies.isEmpty()) throw YouTubeCookieParseException(YouTubeCookieParseProblem.EMPTY_INPUT)
        if (cookies.any { !isYouTubeDomain(it.domain) }) {
            throw YouTubeCookieParseException(YouTubeCookieParseProblem.UNRELATED_DOMAIN)
        }
        if (cookies.any { it.expiresAt != null && it.expiresAt <= nowEpochSeconds }) {
            throw YouTubeCookieParseException(YouTubeCookieParseProblem.EXPIRED_COOKIE)
        }
        if (cookies.any { !cookieNamePattern.matches(it.name) || it.value.isEmpty() || it.value.any(Char::isISOControl) }) {
            throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
        }
        if (cookies.groupBy(Cookie::name).values.any { sameName -> sameName.map(Cookie::value).distinct().size > 1 }) {
            throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
        }
        if (!cookies.map(Cookie::name).containsAll(requiredCookieNames)) {
            throw YouTubeCookieParseException(YouTubeCookieParseProblem.MISSING_REQUIRED_COOKIES)
        }
    }

    private fun isYouTubeDomain(domain: String): Boolean {
        val host = domain.trim().removePrefix(".").lowercase(Locale.ROOT)
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun looksLikeNetscape(text: String): Boolean =
        text.lineSequence().any { it.contains('\t') || it.startsWith("#HttpOnly_") }

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.let { element ->
            (element as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        }

    private fun JsonObject.expirationSeconds(): Long? {
        val expiration =
            listOf("expirationDate", "expires", "expiry")
                .firstNotNullOfOrNull { key -> this[key]?.let { key to it } }
                ?: return null
        val epochSeconds =
            epochSeconds(expiration.second)
                ?: throw YouTubeCookieParseException(YouTubeCookieParseProblem.INVALID_COOKIE)
        return epochSeconds.takeIf { it != 0L }
    }

    private fun epochSeconds(element: JsonElement): Long? {
        val value = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() ?: return null
        return value.toDoubleOrNull()?.toLong() ?: runCatching { Instant.parse(value).epochSecond }.getOrNull()
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

    private data class Cookie(
        val name: String,
        val value: String,
        val domain: String,
        val expiresAt: Long?,
    )
}
