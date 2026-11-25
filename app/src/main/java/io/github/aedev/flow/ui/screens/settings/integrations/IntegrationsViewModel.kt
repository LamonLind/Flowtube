package io.github.aedev.flow.ui.screens.settings.integrations

import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.contentfilter.aislist.AiListRepository
import io.github.aedev.flow.data.contentfilter.aislist.AiListSource
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.local.SponsorBlockSkipButtonStyle
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.discord.DiscordLinkResult
import io.github.aedev.flow.discord.DiscordPresenceController
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.YouTubeAccountSessionExpiredException
import io.github.aedev.flow.innertube.YouTubeAccountVerificationRequiredException
import io.github.aedev.flow.innertube.YouTubeCookieParseException
import io.github.aedev.flow.innertube.YouTubeCookieParseProblem
import io.github.aedev.flow.innertube.YouTubeCookieParser
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

internal sealed interface YouTubeCookieState {
    data object Checking : YouTubeCookieState

    data object SignedOut : YouTubeCookieState

    data class SignedIn(val accountName: String) : YouTubeCookieState

    data class Failed(val problem: YouTubeCookieProblem) : YouTubeCookieState
}

internal enum class YouTubeCookieProblem {
    EMPTY_INPUT,
    INVALID_FORMAT,
    INVALID_COOKIE,
    UNRELATED_DOMAIN,
    EXPIRED_COOKIE,
    MISSING_REQUIRED_COOKIES,
    COOKIE_EXPIRED,
    ACCOUNT_REVERIFICATION,
    NETWORK_ERROR,
    STORAGE_ERROR,
    FILE_READ_ERROR,
}

/** One segment category's behaviour and colour; a null [colorArgb] means the default colour. */
@Immutable
data class SegmentSetting(
    val action: SponsorBlockAction,
    val colorArgb: Int?,
)

@HiltViewModel
class IntegrationsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        private val discord: DiscordPresenceController,
        private val aiList: AiListRepository,
    ) : SettingsViewModel() {
        val sponsorBlock = preferences.sponsorBlockEnabled.asState(false)
        val submitButton = preferences.sbSubmitEnabled.asState(false)
        val userId = preferences.sbUserId.asState(null)
        val skipNotice = preferences.sbSkipNoticeEnabled.asState(true)
        val skipButtonStyle = preferences.sbSkipButtonStyle.asState(SponsorBlockSkipButtonStyle.FULL)
        val deArrow = preferences.deArrowEnabled.asState(false)
        val deArrowBadge = preferences.deArrowBadgeEnabled.asState(false)
        val deArrowRandomFrame = preferences.deArrowRandomFrame.asState(true)
        val deArrowThumbnailsOnly = preferences.deArrowThumbnailsOnly.asState(false)
        val dislikes = preferences.rytdEnabled.asState(true)
        val aiListBlock = aiList.blockEnabled.asState(false)
        val aiListWarn = aiList.warnEnabled.asState(false)
        val aiMusic = aiList.musicEnabled.asState(false)
        val aiListStatus = aiList.status.asState(null)
        val aiListSource = aiList.source.asState(AiListSource.DEFAULT)
        val aiListSourceInput = preferences.aiListSource.asState(null to null)
        val aiReport = preferences.aiListReportEnabled.asState(false)
        val aiReportName = preferences.aiListReportName.asState("")
        val discordState = discord.settingsState

        private val _youtubeCookieState = MutableStateFlow<YouTubeCookieState>(YouTubeCookieState.Checking)
        val youtubeCookieState: StateFlow<YouTubeCookieState> = _youtubeCookieState

        /** Every category's flows are built once here, not per recomposition. */
        val segments =
            combine(
                SponsorBlockCategories.all.map { category ->
                    combine(preferences.sbActionForCategory(category), preferences.sbColorForCategory(category)) { action, color ->
                        category to SegmentSetting(action, color)
                    }
                },
            ) { pairs -> pairs.toMap() }
                .asState(
                    SponsorBlockCategories.all.associateWith { SegmentSetting(SponsorBlockCategories.defaultAction(it), null) },
                )

        private val _discordFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)

        /** The message of a Discord action that failed, for the page to show once. */
        val discordFailures: SharedFlow<String> = _discordFailures.asSharedFlow()

        fun loadYouTubeAccount() =
            write {
                val cookie =
                    try {
                        preferences.youtubeCookie.first()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        _youtubeCookieState.value = YouTubeCookieState.Failed(YouTubeCookieProblem.STORAGE_ERROR)
                        return@write
                    }
                if (cookie == null) {
                    YouTube.cookie = null
                    YouTube.useLoginForBrowse = false
                    _youtubeCookieState.value = YouTubeCookieState.SignedOut
                } else {
                    YouTube.cookie = cookie
                    YouTube.useLoginForBrowse = true
                    checkYouTubeAccount(cookie)
                }
            }

        fun importYouTubeCookies(input: String) =
            write {
                val cookie =
                    try {
                        withContext(Dispatchers.Default) { YouTubeCookieParser.parse(input) }
                    } catch (error: YouTubeCookieParseException) {
                        _youtubeCookieState.value = YouTubeCookieState.Failed(error.problem.toUiProblem())
                        return@write
                    }

                _youtubeCookieState.value = YouTubeCookieState.Checking
                val account =
                    YouTube.accountInfo(cookie).getOrElse { error ->
                        if (error is CancellationException) throw error
                        _youtubeCookieState.value = YouTubeCookieState.Failed(error.toUiProblem())
                        return@write
                    }

                try {
                    preferences.setYoutubeCookie(cookie)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _youtubeCookieState.value = YouTubeCookieState.Failed(YouTubeCookieProblem.STORAGE_ERROR)
                    return@write
                }
                YouTube.cookie = cookie
                YouTube.useLoginForBrowse = true
                _youtubeCookieState.value = YouTubeCookieState.SignedIn(account.name)
            }

        fun signOutYouTube() =
            write {
                try {
                    preferences.setYoutubeCookie(null)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _youtubeCookieState.value = YouTubeCookieState.Failed(YouTubeCookieProblem.STORAGE_ERROR)
                    return@write
                }
                YouTube.cookie = null
                YouTube.useLoginForBrowse = false
                _youtubeCookieState.value = YouTubeCookieState.SignedOut
            }

        fun reportCookieFileReadError() {
            _youtubeCookieState.value = YouTubeCookieState.Failed(YouTubeCookieProblem.FILE_READ_ERROR)
        }

        private suspend fun checkYouTubeAccount(cookie: String) {
            val result = YouTube.accountInfo(cookie)
            _youtubeCookieState.value =
                result.fold(
                    onSuccess = { YouTubeCookieState.SignedIn(it.name) },
                    onFailure = {
                        if (it is CancellationException) throw it
                        YouTubeCookieState.Failed(it.toUiProblem())
                    },
                )
        }

        private fun Throwable.toUiProblem(): YouTubeCookieProblem {
            val message = message.orEmpty().lowercase(Locale.ROOT)
            return when {
                this is YouTubeAccountVerificationRequiredException -> YouTubeCookieProblem.ACCOUNT_REVERIFICATION
                this is YouTubeAccountSessionExpiredException -> YouTubeCookieProblem.COOKIE_EXPIRED
                listOf("verify it's you", "verify your identity", "reverification", "re-verification", "unusual activity")
                    .any(message::contains) -> YouTubeCookieProblem.ACCOUNT_REVERIFICATION
                this is java.io.IOException -> YouTubeCookieProblem.NETWORK_ERROR
                else -> YouTubeCookieProblem.COOKIE_EXPIRED
            }
        }

        private fun YouTubeCookieParseProblem.toUiProblem(): YouTubeCookieProblem =
            when (this) {
                YouTubeCookieParseProblem.EMPTY_INPUT -> YouTubeCookieProblem.EMPTY_INPUT
                YouTubeCookieParseProblem.INVALID_FORMAT -> YouTubeCookieProblem.INVALID_FORMAT
                YouTubeCookieParseProblem.INVALID_COOKIE -> YouTubeCookieProblem.INVALID_COOKIE
                YouTubeCookieParseProblem.UNRELATED_DOMAIN -> YouTubeCookieProblem.UNRELATED_DOMAIN
                YouTubeCookieParseProblem.EXPIRED_COOKIE -> YouTubeCookieProblem.EXPIRED_COOKIE
                YouTubeCookieParseProblem.MISSING_REQUIRED_COOKIES -> YouTubeCookieProblem.MISSING_REQUIRED_COOKIES
            }

        fun setSponsorBlock(value: Boolean) = write { preferences.setSponsorBlockEnabled(value) }

        fun setSegmentAction(
            category: String,
            action: SponsorBlockAction,
        ) = write { preferences.setSbActionForCategory(category, action) }

        fun setSegmentColor(
            category: String,
            colorArgb: Int?,
        ) = write { preferences.setSbColorForCategory(category, colorArgb) }

        fun setSubmitButton(value: Boolean) = write { preferences.setSbSubmitEnabled(value) }

        fun setSkipNotice(value: Boolean) = write { preferences.setSbSkipNoticeEnabled(value) }

        fun setSkipButtonStyle(style: SponsorBlockSkipButtonStyle) = write { preferences.setSbSkipButtonStyle(style) }

        /** A blank id asks for a fresh one rather than clearing the identity. */
        fun setUserId(input: String) =
            write {
                preferences.setSbUserId(input.trim().ifBlank { preferences.getOrCreateSbUserId() })
            }

        fun setDeArrow(value: Boolean) = write { preferences.setDeArrowEnabled(value) }

        fun setDeArrowBadge(value: Boolean) = write { preferences.setDeArrowBadgeEnabled(value) }

        fun setAiListBlock(value: Boolean) = write { aiList.setBlockEnabled(value) }

        fun setAiListWarn(value: Boolean) = write { aiList.setWarnEnabled(value) }

        fun setAiMusic(value: Boolean) = write { aiList.setMusicEnabled(value) }

        fun setAiReport(value: Boolean) = write { preferences.setAiListReportEnabled(value) }

        fun setAiReportName(name: String) = write { preferences.setAiListReportName(name) }

        /** Saves the source and calls [onResult] with false, saving nothing, when it is not valid. */
        fun saveAiListSource(
            url: String,
            key: String,
            onResult: (Boolean) -> Unit,
        ) = write { onResult(aiList.setSource(url, key)) }

        fun setDeArrowRandomFrame(value: Boolean) = write { preferences.setDeArrowRandomFrame(value) }

        fun setDeArrowThumbnailsOnly(value: Boolean) = write { preferences.setDeArrowThumbnailsOnly(value) }

        fun setDislikes(value: Boolean) = write { preferences.setRytdEnabled(value) }

        fun setDiscordEnabled(value: Boolean) = write { discord.setEnabled(value) }

        fun connectDiscord() = write { report(discord.connectAccount()) }

        fun retryDiscord() = write { report(discord.retry()) }

        fun unlinkDiscord() = write { discord.unlink() }

        private fun report(result: DiscordLinkResult) {
            if (result is DiscordLinkResult.Failure && result.message.isNotBlank()) _discordFailures.tryEmit(result.message)
        }
    }
