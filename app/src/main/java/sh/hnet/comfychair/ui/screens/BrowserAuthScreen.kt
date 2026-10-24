package sh.hnet.comfychair.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import sh.hnet.comfychair.R
import sh.hnet.comfychair.security.CertificateIssue
import sh.hnet.comfychair.security.CertificatePinStore
import sh.hnet.comfychair.security.ServerTrust
import sh.hnet.comfychair.security.UntrustedCertificate
import sh.hnet.comfychair.ui.components.CertificateTrustDialog

/**
 * Full-screen WebView for browser-based SSO authentication.
 *
 * Loads [serverUrl] in a WebView so the user can complete any required
 * sign-in flow (Authentik, Authelia, Cloudflare Access, OAuth2-proxy, etc.).
 * When the user taps "Done", the session cookies for [serverUrl] are captured
 * and returned via [onAuthComplete].
 *
 * SSL errors are treated the same as in the OkHttp configuration: only
 * certificates the user has confirmed (by SHA-256 fingerprint) are accepted.
 * For an unknown certificate the same confirmation dialog is shown here, e.g.
 * when the SSO login runs on a different host than ComfyUI.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAuthScreen(
    serverUrl: String,
    onAuthComplete: (cookies: String) -> Unit,
    onCancel: () -> Unit
) {
    // Zertifikat, das gerade im WebView zur Bestätigung ansteht
    var pendingSsl by remember { mutableStateOf<PendingSslDecision?>(null) }

    pendingSsl?.let { pending ->
        CertificateTrustDialog(
            certificate = pending.certificate,
            serverAddress = "${pending.host}:${pending.port}",
            onTrust = {
                CertificatePinStore(pending.context).setPin(pending.host, pending.port, pending.certificate.fingerprint)
                pendingSsl = null
                pending.handler.proceed()
            },
            onDismiss = {
                pendingSsl = null
                pending.handler.cancel()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_browser_auth)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.content_description_close)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val cookies = CookieManager.getInstance()
                                .getCookie(serverUrl)
                                .orEmpty()
                            onAuthComplete(cookies)
                        }
                    ) {
                        Text(stringResource(R.string.button_browser_auth_done))
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    with(CookieManager.getInstance()) {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = false  // Let WebView handle all navigation

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError
                        ) {
                            // Nur Zertifikate durchlassen, die der Nutzer für genau diesen
                            // Host/Port in der App bestätigt hat. Vorher wurde hier jedes
                            // Zertifikat akzeptiert - ausgerechnet auf der Seite, auf der man
                            // sein SSO-Passwort eingibt.
                            val certificate = error.certificate?.x509Certificate
                            val uri = Uri.parse(error.url)
                            val host = uri.host
                            val port = if (uri.port != -1) uri.port else 443
                            if (certificate == null || host == null) {
                                handler.cancel()
                                return
                            }
                            val fingerprint = ServerTrust.fingerprint(certificate)
                            val pinned = CertificatePinStore(view.context).getPin(host, port)
                            if (pinned != null && pinned.equals(fingerprint, ignoreCase = true)) {
                                handler.proceed()
                            } else if (pendingSsl == null) {
                                // Unbekanntes oder geändertes Zertifikat: Nutzer fragen
                                pendingSsl = PendingSslDecision(
                                    handler = handler,
                                    context = view.context.applicationContext,
                                    host = host,
                                    port = port,
                                    certificate = UntrustedCertificate(
                                        fingerprint = fingerprint,
                                        issue = when {
                                            error.primaryError == SslError.SSL_IDMISMATCH -> CertificateIssue.HOSTNAME_MISMATCH
                                            else -> ServerTrust.classify(certificate)
                                        },
                                        subject = certificate.subjectX500Principal.name,
                                        issuer = certificate.issuerX500Principal.name,
                                        notBefore = certificate.notBefore,
                                        notAfter = certificate.notAfter,
                                        previousFingerprint = pinned
                                    )
                                )
                            } else {
                                // Es wartet schon eine Frage; diese Anfrage abbrechen
                                handler.cancel()
                            }
                        }
                    }

                    loadUrl(serverUrl)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

/** Eine offene Zertifikatsfrage aus dem WebView. */
private class PendingSslDecision(
    val handler: SslErrorHandler,
    val context: Context,
    val host: String,
    val port: Int,
    val certificate: UntrustedCertificate
)
