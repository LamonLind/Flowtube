package io.github.aedev.flow.data.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeAuthHeadersTest {
    @Test
    fun sapisidHashHeader_matchesYouTubeFormat() {
        assertThat(
            sapisidHashHeader(
                timestampSeconds = 1_700_000_000L,
                sapisid = "sapisid-value",
                origin = "https://www.youtube.com",
            ),
        ).isEqualTo(
            "SAPISIDHASH 1700000000_ade2239a1ec948ec8b27fe884ba5c1cdc9388057",
        )
    }

    @Test
    fun cookieValue_readsOnlyTheRequestedCookie() {
        val cookies = "PREF=abc; SAPISID=secret; SOCS=CAE="

        assertThat(cookieValue(cookies, "SAPISID")).isEqualTo("secret")
        assertThat(cookieValue(cookies, "MISSING")).isNull()
    }
}
