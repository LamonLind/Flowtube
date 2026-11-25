package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeCookieParserTest {
    private val sampleCookieFile: String
        get() = checkNotNull(javaClass.classLoader?.getResource("youtube-cookies-sample.txt")).readText()

    @Test
    fun `parses Netscape cookie files including HttpOnly entries`() {
        val parsed = YouTubeCookieParser.parse(sampleCookieFile, nowEpochSeconds = 1_800_000_000)

        assertThat(parsed).contains("SID=sample-SID")
        assertThat(parsed).contains("__Secure-3PSID=sample-secure-3")
        assertThat(parsed).doesNotContain("#HttpOnly_")
    }

    @Test
    fun `parses browser JSON cookie arrays`() {
        val expiration = System.currentTimeMillis() / 1_000 + 3_600
        val cookies =
            listOf(
                "SID",
                "HSID",
                "SSID",
                "APISID",
                "SAPISID",
                "__Secure-1PSID",
                "__Secure-3PSID",
            ).joinToString(",") { name ->
                """{"name":"$name","value":"sample-$name","domain":".youtube.com","expirationDate":$expiration}"""
            }

        assertThat(YouTubeCookieParser.parse("[$cookies]")).contains("SAPISID=sample-SAPISID")
    }

    @Test
    fun `parses Cookie headers and keeps equals signs in values`() {
        val header =
            "Cookie: SID=sample-SID; HSID=sample-HSID; SSID=sample-SSID; APISID=sample-APISID; " +
                "SAPISID=sample-SAPISID; __Secure-1PSID=one=two; __Secure-3PSID=sample-secure-3"

        assertThat(YouTubeCookieParser.parse(header)).contains("__Secure-1PSID=one=two")
    }

    @Test
    fun `rejects expired cookie entries`() {
        val expired = sampleCookieFile.replace("2000000000", "100")

        assertThat(
            assertThrows(YouTubeCookieParseException::class.java) {
                YouTubeCookieParser.parse(expired, nowEpochSeconds = 200)
            }.problem,
        ).isEqualTo(YouTubeCookieParseProblem.EXPIRED_COOKIE)
    }

    @Test
    fun `rejects unrelated domains and missing required cookies`() {
        val unrelated = sampleCookieFile.replaceFirst(".youtube.com", ".example.com")
        val missing = sampleCookieFile.replace(Regex("(?m)^\\.youtube\\.com\\tTRUE\\t/\\tFALSE\\t\\d+\\tSSID\\t[^\\n]+\\n"), "")

        assertThat(
            assertThrows(YouTubeCookieParseException::class.java) {
                YouTubeCookieParser.parse(unrelated)
            }.problem,
        ).isEqualTo(YouTubeCookieParseProblem.UNRELATED_DOMAIN)
        assertThat(
            assertThrows(YouTubeCookieParseException::class.java) {
                YouTubeCookieParser.parse(missing)
            }.problem,
        ).isEqualTo(YouTubeCookieParseProblem.MISSING_REQUIRED_COOKIES)
    }

    @Test
    fun `rejects malformed cookie input`() {
        assertThat(
            assertThrows(YouTubeCookieParseException::class.java) {
                YouTubeCookieParser.parse("not a cookie")
            }.problem,
        ).isEqualTo(YouTubeCookieParseProblem.INVALID_FORMAT)
    }
}
