package sh.hnet.comfychair.security

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * Tests für die Zertifikatsprüfung gegen einen echten lokalen HTTPS-Server.
 *
 * Statt der echten System-CAs gibt es hier eine Test-"Root-CA", die als
 * vertrauenswürdig gilt. So lässt sich jede Situation nachstellen:
 * gültiges Zertifikat, selbstsigniert, eigene CA, falscher Hostname.
 */
class ServerTrustTest {

    private val host = "localhost"
    private val server = MockWebServer()

    /** Steht für eine öffentliche CA wie Let's Encrypt, der das Gerät vertraut. */
    private val publicCa = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("Test Public CA")
        .build()

    /** Steht für eine eigene Homelab-CA, die das Gerät nicht kennt. */
    private val privateCa = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("Homelab CA")
        .build()

    private val systemTrustManager = HandshakeCertificates.Builder()
        .addTrustedCertificate(publicCa.certificate)
        .build()
        .trustManager

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun leaf(name: String, signer: HeldCertificate? = null): HeldCertificate =
        HeldCertificate.Builder()
            .commonName(name)
            .addSubjectAlternativeName(name)
            .apply { if (signer != null) signedBy(signer) }
            .build()

    private fun serve(cert: HeldCertificate, vararg chain: HeldCertificate) {
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(cert, *chain.map { it.certificate }.toTypedArray())
            .build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("ok"))
        server.start(InetAddress.getByName(host), 0)
    }

    private fun trust(pin: String? = null) = ServerTrust(
        pinnedFingerprint = { pin },
        systemTrustManager = systemTrustManager
    )

    /** true, wenn die Anfrage durchging; false, wenn die Verbindung abgelehnt wurde. */
    private fun connect(trust: ServerTrust): Boolean {
        val client = trust.configure(OkHttpClient.Builder()).build()
        val url = server.url("/").newBuilder().host(host).build()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    @Test
    fun validCertificateIsAccepted() {
        serve(leaf(host, publicCa), publicCa)
        val trust = trust()

        assertTrue(connect(trust))
        assertEquals(CertificateIssue.NONE, trust.lastIssue)
        assertNull(trust.lastUntrusted)
    }

    @Test
    fun selfSignedWithoutPinIsRejectedAndReported() {
        val cert = leaf(host)
        serve(cert)
        val trust = trust()

        // Früher wurde das stillschweigend akzeptiert
        assertTrue("Verbindung hätte abgelehnt werden müssen", !connect(trust))
        val untrusted = trust.lastUntrusted ?: return fail("Zertifikat wurde nicht gemeldet")
        assertEquals(CertificateIssue.SELF_SIGNED, untrusted.issue)
        assertEquals(ServerTrust.fingerprint(cert.certificate), untrusted.fingerprint)
        assertNull(untrusted.previousFingerprint)
    }

    @Test
    fun selfSignedWithMatchingPinIsAccepted() {
        val cert = leaf(host)
        serve(cert)
        val trust = trust(pin = ServerTrust.fingerprint(cert.certificate))

        assertTrue(connect(trust))
        assertEquals(CertificateIssue.SELF_SIGNED, trust.lastIssue)
    }

    @Test
    fun changedCertificateIsRejectedAndMarkedAsChanged() {
        val oldPin = ServerTrust.fingerprint(leaf(host).certificate)
        serve(leaf(host))
        val trust = trust(pin = oldPin)

        assertTrue(!connect(trust))
        assertEquals(oldPin, trust.lastUntrusted?.previousFingerprint)
    }

    @Test
    fun privateCaIsReportedAsUnknownCa() {
        serve(leaf(host, privateCa), privateCa)
        val trust = trust()

        assertTrue(!connect(trust))
        assertEquals(CertificateIssue.UNKNOWN_CA, trust.lastUntrusted?.issue)
    }

    @Test
    fun validCertificateForOtherHostnameIsRejected() {
        // Gültig signiert, aber für einen anderen Namen: früher akzeptiert,
        // weil die Hostname-Prüfung komplett abgeschaltet war
        serve(leaf("comfy.example.com", publicCa), publicCa)
        val trust = trust()

        assertTrue(!connect(trust))
        assertEquals(CertificateIssue.HOSTNAME_MISMATCH, trust.lastUntrusted?.issue)
    }

    @Test
    fun pinnedCertificateForOtherHostnameIsAccepted() {
        val cert = leaf("comfy.example.com", publicCa)
        serve(cert, publicCa)
        val trust = trust(pin = ServerTrust.fingerprint(cert.certificate))

        assertTrue(connect(trust))
        assertEquals(CertificateIssue.HOSTNAME_MISMATCH, trust.lastIssue)
    }

    @Test
    fun fingerprintHasOpensslFormat() {
        val fp = ServerTrust.fingerprint(leaf(host).certificate)
        // 32 Bytes SHA-256 als Hex-Paare mit Doppelpunkt, wie bei "openssl x509 -fingerprint -sha256"
        assertTrue(fp, Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(fp))
    }
}
