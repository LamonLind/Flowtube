package io.github.aedev.flow.data.auth

import java.security.MessageDigest

internal fun sapisidHashHeader(
    timestampSeconds: Long,
    sapisid: String,
    origin: String,
): String {
    val input = "$timestampSeconds $sapisid $origin"
    val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
    val hash = digest.joinToString("") { byte -> "%02x".format(byte) }
    return "SAPISIDHASH ${timestampSeconds}_$hash"
}

internal fun cookieValue(
    cookie: String,
    name: String,
): String? =
    cookie
        .split(';')
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.takeIf(String::isNotEmpty)
