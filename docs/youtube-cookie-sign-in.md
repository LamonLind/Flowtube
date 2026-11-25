# YouTube cookie sign-in

1. Sign in to YouTube in a regular browser, preferably with a secondary Google account.
2. Use a local cookie-export tool such as the **Get cookies.txt LOCALLY** browser extension while on `youtube.com`. Export the Netscape `cookies.txt` format and keep the file on your device. Do not upload or share it.
3. In Flow, open **Settings → Integrations → YouTube account**, then upload `cookies.txt` or paste a Netscape file, browser-exported JSON, or a `Cookie:` header into the text box.
4. Flow checks the cookies with YouTube and reports the account name. Use **Sign out / delete cookies** to remove the saved session.

Cookies grant full access to the Google account. Flow stores the imported cookie string encrypted with an Android Keystore key and does not include it in settings backups or request logs. Export only YouTube cookies, and re-export them if the session expires or Google asks for verification.
