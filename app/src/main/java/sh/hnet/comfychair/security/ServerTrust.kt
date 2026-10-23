package sh.hnet.comfychair.security

import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Art des Zertifikatsproblems, das beim Verbindungsaufbau erkannt wurde.
 */
enum class CertificateIssue {
    /** Zertifikat ist gültig und von einer vertrauenswürdigen CA signiert. */
    NONE,
    /** Zertifikat hat sich selbst signiert (Aussteller == Inhaber). */
    SELF_SIGNED,
    /** Zertifikat stammt von einer CA, die das Gerät nicht kennt (z. B. eigene Homelab-CA). */
    UNKNOWN_CA,
    /** Zertifikat ist gültig, aber für einen anderen Namen ausgestellt (z. B. Zugriff per IP). */
    HOSTNAME_MISMATCH
}

/**
 * Ein Zertifikat, dem (noch) nicht vertraut wird. Wird dem Nutzer zur Bestätigung angezeigt.
 *
 * @property fingerprint SHA-256-Fingerabdruck im Format "AB:CD:…", wie ihn auch
 *   `openssl x509 -noout -fingerprint -sha256` oder der Browser anzeigt.
 * @property previousFingerprint Gesetzt, wenn für diesen Server schon ein anderes
 *   Zertifikat bestätigt war, d. h. das Zertifikat hat sich geändert.
 */
data class UntrustedCertificate(
    val fingerprint: String,
    val issue: CertificateIssue,
    val subject: String,
    val issuer: String,
    val notBefore: Date,
    val notAfter: Date,
    val previousFingerprint: String?
)

/**
 * Prüft Server-Zertifikate nach dem Prinzip "Trust on first use" (wie SSH).
 *
 * 1. Zuerst die ganz normale Prüfung des Systems (gültige CA, passender Hostname).
 * 2. Schlägt die fehl, wird das Zertifikat nur akzeptiert, wenn sein Fingerabdruck
 *    genau dem entspricht, den der Nutzer für diesen Server bestätigt hat.
 * 3. Sonst wird die Verbindung abgelehnt und das Zertifikat in [lastUntrusted]
 *    gemerkt, damit die Oberfläche den Nutzer fragen kann.
 *
 * Vorher wurde jedes Zertifikat ungeprüft akzeptiert und auch der Hostname nie
 * verglichen. Damit hätte sich jeder im selben Netz als der Server ausgeben und
 * Zugangsdaten mitlesen können.
 *
 * Jede [sh.hnet.comfychair.ComfyUIClient]-Instanz hat ein eigenes Objekt, damit sich
 * gleichzeitige Verbindungen zu verschiedenen Servern nicht gegenseitig beeinflussen.
 *
 * @param pinnedFingerprint Liefert den bestätigten Fingerabdruck für diesen Server (oder null).
 * @param systemTrustManager Die normale Prüfung des Systems. In Tests austauschbar.
 * @param systemHostnameVerifier Die normale Hostname-Prüfung. In Tests austauschbar.
 */
class ServerTrust(
    private val pinnedFingerprint: () -> String?,
    private val systemTrustManager: X509TrustManager = defaultSystemTrustManager(),
    // Dieselbe Hostname-Prüfung, die OkHttp ohne eigene Einstellung verwendet
    private val systemHostnameVerifier: HostnameVerifier = OkHostnameVerifier
) {

    /** Problem des zuletzt akzeptierten Zertifikats (nur zur Anzeige eines Hinweises). */
    @Volatile
    var lastIssue: CertificateIssue = CertificateIssue.NONE
        private set

    /** Zuletzt abgelehntes Zertifikat, oder null. */
    @Volatile
    var lastUntrusted: UntrustedCertificate? = null
        private set

    fun reset() {
        lastIssue = CertificateIssue.NONE
        lastUntrusted = null
    }

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            systemTrustManager.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Server hat kein Zertifikat geschickt")

            try {
                systemTrustManager.checkServerTrusted(chain, authType)
                lastIssue = CertificateIssue.NONE
                return
            } catch (systemRejection: CertificateException) {
                val leaf = chain[0]
                val fingerprint = fingerprint(leaf)
                val pinned = pinnedFingerprint()
                val issue = classify(leaf)

                if (pinned != null && pinned.equals(fingerprint, ignoreCase = true)) {
                    lastIssue = issue
                    return
                }

                lastUntrusted = UntrustedCertificate(
                    fingerprint = fingerprint,
                    issue = issue,
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                    notBefore = leaf.notBefore,
                    notAfter = leaf.notAfter,
                    previousFingerprint = pinned
                )
                throw systemRejection
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    /**
     * Normale Hostname-Prüfung. Ausnahme: Ist das Zertifikat genau das vom Nutzer
     * bestätigte, wird der Hostname nicht verglichen. Self-signed-Zertifikate im
     * Heimnetz passen oft nicht zur IP-Adresse, unter der der Server erreichbar ist.
     */
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { hostname, session ->
        val leaf = leafOf(session)
        val pinned = pinnedFingerprint()
        val leafFingerprint = leaf?.let { fingerprint(it) }

        when {
            pinned != null && pinned.equals(leafFingerprint, ignoreCase = true) -> {
                if (lastIssue == CertificateIssue.NONE && !systemHostnameVerifier.verify(hostname, session)) {
                    lastIssue = CertificateIssue.HOSTNAME_MISMATCH
                }
                true
            }
            systemHostnameVerifier.verify(hostname, session) -> true
            else -> {
                // Gültiges Zertifikat, aber für einen anderen Namen: dem Nutzer zur Bestätigung anbieten
                if (leaf != null && leafFingerprint != null) {
                    lastUntrusted = UntrustedCertificate(
                        fingerprint = leafFingerprint,
                        issue = CertificateIssue.HOSTNAME_MISMATCH,
                        subject = leaf.subjectX500Principal.name,
                        issuer = leaf.issuerX500Principal.name,
                        notBefore = leaf.notBefore,
                        notAfter = leaf.notAfter,
                        previousFingerprint = pinned
                    )
                }
                false
            }
        }
    }

    private fun leafOf(session: SSLSession): X509Certificate? = try {
        session.peerCertificates.firstOrNull() as? X509Certificate
    } catch (e: Exception) {
        null
    }

    /** Hängt diese Prüfung an einen OkHttp-Client. */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
    }

    companion object {
        /** SHA-256 über das DER-kodierte Zertifikat, als "AB:CD:…". */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }

        fun classify(cert: X509Certificate): CertificateIssue =
            if (cert.issuerX500Principal == cert.subjectX500Principal) CertificateIssue.SELF_SIGNED
            else CertificateIssue.UNKNOWN_CA

        fun defaultSystemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
