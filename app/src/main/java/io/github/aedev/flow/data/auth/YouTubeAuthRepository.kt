package io.github.aedev.flow.data.auth

import android.content.Context
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.AccountInfo
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.youtubeAuthDataStore: DataStore<Preferences> by
    safePreferencesDataStore(name = "youtube_auth", scope = StoreScope.PROFILE_PRIVATE)

data class YouTubeAuthState(
    val account: AccountInfo? = null,
    val isSignedIn: Boolean = false,
)

@Singleton
class YouTubeAuthRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        val YOUTUBE_COOKIE = stringPreferencesKey("youtube_cookie")
        val GOOGLE_COOKIE = stringPreferencesKey("google_cookie")
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val ACCOUNT_HANDLE = stringPreferencesKey("account_handle")
        val ACCOUNT_AVATAR = stringPreferencesKey("account_avatar")
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val GOOGLE_ORIGIN = "https://accounts.google.com"
    }

    val state: Flow<YouTubeAuthState> =
        context.youtubeAuthDataStore.data.map { preferences ->
            val youtubeCookie = KeystoreSecretBox.open(preferences[YOUTUBE_COOKIE])
            YouTubeAuthState(
                account =
                    preferences[ACCOUNT_NAME]?.let { name ->
                        AccountInfo(
                            name = name,
                            email = preferences[ACCOUNT_EMAIL],
                            channelHandle = preferences[ACCOUNT_HANDLE],
                            thumbnailUrl = preferences[ACCOUNT_AVATAR],
                        )
                    },
                isSignedIn = youtubeCookie.isNotBlank(),
            )
        }

    suspend fun restore() {
        val preferences = context.youtubeAuthDataStore.data.first()
        val cookie = KeystoreSecretBox.open(preferences[YOUTUBE_COOKIE]).takeIf(String::isNotBlank)
        YouTube.cookie = cookie
        YouTube.useLoginForBrowse = cookie != null
        YouTube.onAuthenticationFailure = ::handleAuthenticationFailure
    }

    suspend fun saveCookies(
        youtubeCookie: String,
        googleCookie: String,
    ) {
        require(youtubeCookie.isNotBlank()) { "YouTube cookies are required" }
        context.youtubeAuthDataStore.edit { preferences ->
            preferences[YOUTUBE_COOKIE] = KeystoreSecretBox.seal(youtubeCookie)
            preferences[GOOGLE_COOKIE] = KeystoreSecretBox.seal(googleCookie)
        }
        YouTube.cookie = youtubeCookie
        YouTube.useLoginForBrowse = true
    }

    suspend fun captureWebViewCookies() {
        val cookieManager = CookieManager.getInstance()
        val youtubeCookie = cookieManager.getCookie(YOUTUBE_ORIGIN).orEmpty()
        val googleCookie = cookieManager.getCookie(GOOGLE_ORIGIN).orEmpty()
        saveCookies(youtubeCookie, googleCookie)
    }

    suspend fun refreshAccount(): Result<AccountInfo> =
        withContext(PerformanceDispatcher.networkIO) {
            YouTube.accountInfo().onSuccess { account ->
                context.youtubeAuthDataStore.edit { preferences ->
                    preferences[ACCOUNT_NAME] = account.name
                    account.email?.let { preferences[ACCOUNT_EMAIL] = it } ?: preferences.remove(ACCOUNT_EMAIL)
                    account.channelHandle?.let { preferences[ACCOUNT_HANDLE] = it } ?: preferences.remove(ACCOUNT_HANDLE)
                    account.thumbnailUrl?.let { preferences[ACCOUNT_AVATAR] = it } ?: preferences.remove(ACCOUNT_AVATAR)
                }
            }.onFailure { error ->
                if ((error as? io.ktor.client.plugins.ClientRequestException)?.response?.status?.value in setOf(401, 403)) {
                    signOut()
                }
            }
        }

    suspend fun signOut() {
        context.youtubeAuthDataStore.edit { preferences ->
            preferences.remove(YOUTUBE_COOKIE)
            preferences.remove(GOOGLE_COOKIE)
            preferences.remove(ACCOUNT_NAME)
            preferences.remove(ACCOUNT_EMAIL)
            preferences.remove(ACCOUNT_HANDLE)
            preferences.remove(ACCOUNT_AVATAR)
        }
        YouTube.cookie = null
        YouTube.dataSyncId = null
        YouTube.useLoginForBrowse = false
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    fun handleAuthenticationFailure() {
        YouTube.cookie = null
        YouTube.dataSyncId = null
        YouTube.useLoginForBrowse = false
        scope.launch { signOut() }
    }
}
