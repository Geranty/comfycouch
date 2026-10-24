package sh.hnet.comfychair.security

import android.content.Context

/**
 * Speichert pro Server (Host + Port):
 * - den vom Nutzer bestätigten Zertifikats-Fingerabdruck ("Pin")
 * - ob der Server schon einmal per HTTPS erreichbar war
 *
 * Das Zweite verhindert einen stillen Wechsel auf unverschlüsseltes HTTP:
 * Wer sich in die Verbindung einklinkt, könnte sonst HTTPS blockieren und die App
 * schickt Zugangsdaten danach im Klartext (ähnlich wie HSTS im Browser).
 */
class CertificatePinStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPin(hostname: String, port: Int): String? =
        prefs.getString(pinKey(hostname, port), null)

    fun setPin(hostname: String, port: Int, fingerprint: String) {
        prefs.edit().putString(pinKey(hostname, port), fingerprint).apply()
    }

    fun isHttpsKnown(hostname: String, port: Int): Boolean =
        prefs.getBoolean(httpsKey(hostname, port), false)

    fun markHttpsKnown(hostname: String, port: Int) {
        if (!isHttpsKnown(hostname, port)) {
            prefs.edit().putBoolean(httpsKey(hostname, port), true).apply()
        }
    }

    /** Vergisst alles zu diesem Server, z. B. wenn er gelöscht wird. */
    fun forget(hostname: String, port: Int) {
        prefs.edit()
            .remove(pinKey(hostname, port))
            .remove(httpsKey(hostname, port))
            .apply()
    }

    private fun id(hostname: String, port: Int) = "${hostname.lowercase()}:$port"
    private fun pinKey(hostname: String, port: Int) = "pin:${id(hostname, port)}"
    private fun httpsKey(hostname: String, port: Int) = "https:${id(hostname, port)}"

    companion object {
        private const val PREFS_NAME = "certificate_pins"
    }
}
