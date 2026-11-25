package io.github.aedev.flow.ui.screens.settings.integrations

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsListScope

internal fun SettingsListScope.youtubeCookiesSection(
    state: YouTubeCookieState,
    viewModel: IntegrationsViewModel,
    onUpload: (Uri) -> Unit,
) {
    header("integrations.youtube.cookies.header", R.string.youtube_cookies_title)
    item("integrations.youtube.cookies.import") {
        YouTubeCookiesCard(state = state, viewModel = viewModel, onUpload = onUpload)
    }
}

@Composable
private fun YouTubeCookiesCard(
    state: YouTubeCookieState,
    viewModel: IntegrationsViewModel,
    onUpload: (Uri) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var revealCookies by remember { mutableStateOf(false) }
    val isBusy = state is YouTubeCookieState.Checking
    LaunchedEffect(state) {
        if (state is YouTubeCookieState.SignedIn) {
            input = ""
            revealCookies = false
        }
    }
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onUpload(uri)
        }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.youtube_cookies_description), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.youtube_cookies_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.youtube_cookies_input_label)) },
                supportingText = { Text(stringResource(R.string.youtube_cookies_input_hint)) },
                trailingIcon = {
                    IconButton(onClick = { revealCookies = !revealCookies }) {
                        Icon(
                            imageVector = if (revealCookies) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription =
                                stringResource(
                                    if (revealCookies) R.string.youtube_cookies_hide else R.string.youtube_cookies_show,
                                ),
                        )
                    }
                },
                visualTransformation = if (revealCookies) VisualTransformation.None else PasswordVisualTransformation(),
                minLines = 4,
                maxLines = 8,
                enabled = !isBusy,
            )
            Button(
                onClick = {
                    viewModel.importYouTubeCookies(input)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy && input.isNotBlank(),
            ) {
                Text(stringResource(R.string.youtube_cookies_sign_in))
            }
            OutlinedButton(
                onClick = { openDocument.launch(arrayOf("text/plain", "application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
            ) {
                Text(stringResource(R.string.youtube_cookies_upload))
            }

            val statusText =
                when (state) {
                    YouTubeCookieState.Checking -> stringResource(R.string.youtube_cookies_checking)
                    YouTubeCookieState.SignedOut -> stringResource(R.string.youtube_cookies_signed_out)
                    is YouTubeCookieState.SignedIn -> stringResource(R.string.youtube_cookies_signed_in, state.accountName)
                    is YouTubeCookieState.Failed -> stringResource(state.problem.stringResource())
                }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is YouTubeCookieState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (state is YouTubeCookieState.SignedIn || state is YouTubeCookieState.Failed) {
                TextButton(
                    onClick = {
                        viewModel.signOutYouTube()
                        input = ""
                        revealCookies = false
                    },
                    enabled = !isBusy,
                ) {
                    Text(stringResource(R.string.youtube_cookies_sign_out))
                }
            }
        }
    }
}

private fun YouTubeCookieProblem.stringResource(): Int =
    when (this) {
        YouTubeCookieProblem.EMPTY_INPUT -> R.string.youtube_cookies_error_empty
        YouTubeCookieProblem.INVALID_FORMAT -> R.string.youtube_cookies_error_format
        YouTubeCookieProblem.INVALID_COOKIE -> R.string.youtube_cookies_error_cookie
        YouTubeCookieProblem.UNRELATED_DOMAIN -> R.string.youtube_cookies_error_domain
        YouTubeCookieProblem.EXPIRED_COOKIE -> R.string.youtube_cookies_error_expired
        YouTubeCookieProblem.MISSING_REQUIRED_COOKIES -> R.string.youtube_cookies_error_missing
        YouTubeCookieProblem.COOKIE_EXPIRED -> R.string.youtube_cookies_error_session
        YouTubeCookieProblem.ACCOUNT_REVERIFICATION -> R.string.youtube_cookies_error_verification
        YouTubeCookieProblem.NETWORK_ERROR -> R.string.youtube_cookies_error_network
        YouTubeCookieProblem.STORAGE_ERROR -> R.string.youtube_cookies_error_storage
        YouTubeCookieProblem.FILE_READ_ERROR -> R.string.youtube_cookies_error_file
    }
