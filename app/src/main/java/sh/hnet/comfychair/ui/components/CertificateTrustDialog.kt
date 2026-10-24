package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.security.CertificateIssue
import sh.hnet.comfychair.security.UntrustedCertificate
import java.text.DateFormat

/**
 * Fragt den Nutzer, ob er einem Server-Zertifikat vertrauen möchte.
 *
 * Angezeigt wird der SHA-256-Fingerabdruck. Den kann man mit dem Server vergleichen, z. B.:
 *   openssl x509 -in cert.pem -noout -fingerprint -sha256
 * Stimmt er überein, ist man sicher mit dem eigenen Server verbunden.
 */
@Composable
fun CertificateTrustDialog(
    certificate: UntrustedCertificate,
    serverAddress: String,
    onTrust: () -> Unit,
    onDismiss: () -> Unit
) {
    val changed = certificate.previousFingerprint != null
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (changed) R.string.title_certificate_changed else R.string.title_certificate_untrusted
                )
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (changed) R.string.msg_certificate_changed_detail else R.string.msg_certificate_untrusted,
                        serverAddress
                    ),
                    color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        when (certificate.issue) {
                            CertificateIssue.SELF_SIGNED -> R.string.cert_reason_self_signed
                            CertificateIssue.HOSTNAME_MISMATCH -> R.string.cert_reason_hostname_mismatch
                            else -> R.string.cert_reason_unknown_ca
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Column {
                        Label(stringResource(R.string.cert_label_fingerprint))
                        Text(
                            certificate.fingerprint,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Label(stringResource(R.string.cert_label_subject))
                        Text(certificate.subject, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Label(stringResource(R.string.cert_label_issuer))
                        Text(certificate.issuer, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Label(stringResource(R.string.cert_label_validity))
                        Text(
                            "${dateFormat.format(certificate.notBefore)} – ${dateFormat.format(certificate.notAfter)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onTrust) {
                Text(stringResource(R.string.button_trust_certificate))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}
