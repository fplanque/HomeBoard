package com.evofactory.homeboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/**
 * Frigate settings: the address and login, saved once they work. Connecting from here also accepts
 * the certificate Frigate shows, as its own is self-signed: the TV then trusts only that one, or one
 * the system trusts for the address, which can be renewed without asking. After a
 * failed attempt, closing still saves what's in the form, so it needn't be typed again with a remote.
 */
@Composable
fun FrigateDialog(
    initial: FrigateServer,
    onSave: (FrigateServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initial.url) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // The last attempt from here, and where it went: it may have seen the certificate
    var attempt by remember { mutableStateOf<FrigateClient?>(null) }
    var attemptUrl by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val urlField = remember { FocusRequester() }
    val usernameField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }
    val connectButton = remember { FocusRequester() }
    val fields = remember { ListFocus() }
    // Without a password, the saved one is used (if it's for this username)
    val reusePassword = password.isEmpty() && initial.password.isNotEmpty() && username.trim() == initial.username
    val canConnect = normalizeFrigateUrl(url) != null && username.isNotBlank() && (password.isNotEmpty() || reusePassword)

    fun connect() {
        val baseUrl = normalizeFrigateUrl(url) ?: return
        if (!canConnect || connecting) return
        keyboard?.hide()
        url = baseUrl
        status = "Connecting to $baseUrl…"
        connecting = true
        scope.launch {
            try {
                val server = FrigateServer(baseUrl, username.trim(), password.ifEmpty { initial.password })
                val client = FrigateClient(server)
                attempt = client
                attemptUrl = baseUrl
                client.alerts()
                onSave(server.copy(certPin = client.seenCertPin))
                onDismiss()
            } catch (e: FrigateException) {
                status = e.message
            } finally {
                connecting = false
            }
        }
    }

    fun close() {
        attempt?.let { client ->
            val baseUrl = normalizeFrigateUrl(url)
            onSave(
                FrigateServer(
                    baseUrl ?: url.trim(),
                    username.trim(),
                    password.ifEmpty { initial.password },
                    // Only for the address it was seen at
                    if (baseUrl == attemptUrl) client.seenCertPin else "",
                ),
            )
        }
        onDismiss()
    }

    TopDialog(
        title = "Frigate",
        onDismiss = ::close,
        buttons = listOfNotNull(
            // Greyed out until everything needed is filled in. Stays enabled while connecting
            // (presses are ignored), so the remote keeps its place on it
            DialogButton("Connect", enabled = canConnect, primary = true, focus = connectButton, onClick = ::connect),
            DialogButton("Remove") {
                onSave(FrigateServer())
                onDismiss()
            }.takeIf { initial != FrigateServer() },
        ),
        contentFocus = { fields.current(listOf("url", "username", "password")) },
    ) {
        val fieldModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        TvTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = "Address, e.g. 192.168.1.30 (uses port 8971)",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            onImeAction = {
                keyboard?.hide()
                usernameField.requestFocus()
            },
            modifier = fieldModifier.focusRequester(urlField).then(fields.row("url")).ringToTop(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "Username",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                onImeAction = {
                    keyboard?.hide()
                    passwordField.requestFocus()
                },
                // Side by side: Right goes to the password, Left comes back
                modifier = Modifier.weight(1f).focusRequester(usernameField).then(fields.row("username")).ringToTop(right = false),
            )
            TvTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = if (reusePassword) "Password (saved)" else "Password",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = {
                    keyboard?.hide()
                    if (canConnect) connectButton.requestFocus()
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f).focusRequester(passwordField).then(fields.row("password")).ringToTop(left = false),
            )
        }
        Text(
            status ?: ("The password is stored on this TV, to log in again when the login expires: " +
                "a Frigate user just for the TV, with the viewer role, is safest."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LaunchedEffect(Unit) { (if (canConnect) connectButton else urlField).requestFocus() }
    }
}

/**
 * Frigate showed a certificate that isn't the one this TV pinned, nor one the system trusts for its
 * address. Accept pins the new one and carries on with the saved login; Log in again opens the
 * Frigate settings, whose Connect also accepts it. Opens by itself, once for each new certificate.
 */
@Composable
fun FrigateCertificateDialog(
    server: FrigateServer,
    offered: String,
    onAccept: () -> Unit,
    onLogIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accept = remember { FocusRequester() }
    TopDialog(
        title = "Frigate certificate changed",
        onDismiss = onDismiss,
        buttons = listOf(
            DialogButton("Accept", primary = true, focus = accept) {
                onAccept()
                onDismiss()
            },
            DialogButton("Log in again") {
                onDismiss()
                onLogIn()
            },
        ),
    ) {
        listOf(
            "Frigate at ${server.url} shows a different certificate from the one this TV trusts, so alerts have stopped.",
            "That's expected if its certificate was renewed or replaced. If nobody changed it, something else may be " +
                "answering in Frigate's place: close this, and accepting the new certificate would send it the password.",
            "Trusted: ${formatFingerprint(server.certPin)}",
            "Now: ${formatFingerprint(offered)}",
        ).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        LaunchedEffect(Unit) { runCatching { accept.requestFocus() } }
    }
}

/** A SHA-256 fingerprint in groups of 4 hex digits, as the iPad app shows it */
fun formatFingerprint(hex: String): String = hex.chunked(4).joinToString(" ")
